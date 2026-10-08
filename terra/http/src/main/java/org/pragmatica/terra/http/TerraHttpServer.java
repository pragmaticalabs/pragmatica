// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.terra.http;

import java.util.List;

import org.pragmatica.aether.http.adapter.SliceRouterFactory;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.aether.http.security.HttpAuthenticator;
import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.ContentCategory;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.server.HttpServer;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.terra.TerraApplication;


/// Owns a started application and authenticator. Close this host, rather than the application,
/// to stop admission, drain handlers and response flushes, stop transport, and release resources.
public final class TerraHttpServer {
    private final TerraApplication application;
    private final HttpAuthenticator authenticator;
    private final TerraHttpRoutes routes;
    private final Admission admission = new Admission();
    private final Promise<Unit> closed = Promise.promise();
    private Option<HttpServer> transport = Option.empty();
    private boolean closing;

    private TerraHttpServer(TerraApplication application, HttpAuthenticator authenticator, TerraHttpRoutes routes) {
        this.application = application;
        this.authenticator = authenticator;
        this.routes = routes;
    }

    public static Promise<TerraHttpServer> start(TerraApplication application,
                                                 TerraHttpConfig config,
                                                 HttpAuthenticator authenticator) {
        return TerraHttpRoutes.discover().fold(cause -> failedStart(cause.result(), application, authenticator),
                                               factories -> start(application,
                                                                  config,
                                                                  authenticator,
                                                                  factories,
                                                                  JsonMapper.defaultJsonMapper()));
    }

    /// Ownership transfers on entry, including on failed startup. Factories outside the selected
    /// application are ignored; ambiguous or incompatible selected routes fail before binding.
    public static Promise<TerraHttpServer> start(TerraApplication application,
                                                 TerraHttpConfig config,
                                                 HttpAuthenticator authenticator,
                                                 List<SliceRouterFactory<?>> factories,
                                                 JsonMapper mapper) {
        return TerraHttpConfig.terraHttpConfig(config.transport(),
                                               config.mountMode(),
                                               config.defaultPolicy())
                              .flatMap(valid -> TerraHttpRoutes.terraHttpRoutes(application,
                                                                                factories,
                                                                                valid,
                                                                                authenticator,
                                                                                mapper))
                              .async()
                              .flatMap(routes -> new TerraHttpServer(application, authenticator, routes).bind(config))
                              .fold(result -> result.isSuccess()
                                              ? result.async()
                                              : failedStart(result, application, authenticator));
    }

    private static Promise<TerraHttpServer> failedStart(Result<TerraHttpServer> result,
                                                        TerraApplication app,
                                                        HttpAuthenticator authenticator) {
        return closeOwned(app, authenticator).fold(cleanup -> Result.all(result, cleanup)
                                                                    .map((server, _) -> server)
                                                                    .async());
    }

    private Promise<TerraHttpServer> bind(TerraHttpConfig config) {
        return HttpServer.httpServer(config.transport(),
                                     this::handle)
                         .map(server -> {
                             transport = Option.some(server);
                             admission.start();

                             return this;
                         });
    }

    public int port() {
        return transport.map(HttpServer::port)
                        .or(0);
    }

    /// Snapshot includes response flushes in the in-flight count. No route or principal details leak.
    public Status status() {
        return admission.status();
    }

    public record Status(boolean ready, int inFlight, long accepted, long failedWrites) {}

    @Contract
    private void handle(HttpRequest request, ResponseWriter writer) {
        if (request.path().equals("/__terra/health/live")) {
            writer.okText("live");
        } else if (request.path().equals("/__terra/health/ready")) {
            writer.write(status().ready()
                         ? HttpStatus.OK
                         : HttpStatus.SERVICE_UNAVAILABLE,
                         status().ready()
                         ? "ready".getBytes(java.nio.charset.StandardCharsets.UTF_8)
                         : "draining".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                         CommonContentType.TEXT_PLAIN);
        } else if (admission.admit()) {
            dispatch(request).recover(_ -> HttpResponseData.httpResponseData(500, "Internal server error"))
                    .flatMap(response -> write(writer, response))
                    .withResult(admission::complete);
        } else {
            writer.error(HttpStatus.SERVICE_UNAVAILABLE, "Terra is not accepting requests");
        }
    }

    private Promise<HttpResponseData> dispatch(HttpRequest request) {
        return Promise.lift(Causes::fromThrowable,
                            () -> routes.handle(HttpRequestContext.httpRequestContext(request.path(),
                                                                                      request.method().name(),
                                                                                      request.queryParams().asMap(),
                                                                                      request.headers().asMap(),
                                                                                      request.body(),
                                                                                      request.requestId())))
                      .flatMap(promise -> promise);
    }

    private static Promise<Unit> write(ResponseWriter writer, HttpResponseData response) {
        return Result.lift(Causes::fromThrowable,
                           () -> {
                               response.headers()
                                       .entrySet()
                                       .stream()
                                       .filter(entry -> !entry.getKey()
                                                              .equalsIgnoreCase("Content-Type"))
                                       .forEach(entry -> writer.header(entry.getKey(),
                                                                       entry.getValue()));
                               var type = response.headers()
                                                  .entrySet()
                                                  .stream()
                                                  .filter(entry -> entry.getKey()
                                                                        .equalsIgnoreCase("Content-Type"))
                                                  .map(java.util.Map.Entry::getValue)
                                                  .findFirst()
                                                  .orElse("application/octet-stream");

                               return writer.writeAsync(HttpStatus.httpStatus(response.statusCode()),
                                                        response.body(),
                                                        ContentType.contentType(type, ContentCategory.BINARY));
                           })
                     .fold(Promise::failure, promise -> promise);
    }

    public synchronized Promise<Unit> close() {
        if (!closing) {
            closing = true;
            admission.drain()
                     .flatMap(_ -> transport.map(HttpServer::stop)
                                            .or(Promise.unitPromise()))
                     .fold(stopped -> closeOwned(application, authenticator).fold(released -> Result.all(stopped,
                                                                                                         released)
                                                                                                    .map((_, _) -> Unit.unit())
                                                                                                    .async()))
                     .withResult(closed::resolve);
        }
        // A caller timeout must not mutate the internal shutdown fence.
        return closed.map(value -> value);
    }

    private static Promise<Unit> closeOwned(TerraApplication app, HttpAuthenticator authenticator) {
        return app.close()
                  .fold(applicationResult -> Result.lift(Causes::fromThrowable,
                                                         () -> java.util.Objects.requireNonNull(authenticator.close(),
                                                                                                "Authenticator returned null close Promise"))
                                                   .fold(Promise::failure, promise -> promise)
                                                   .fold(authResult -> Result.all(applicationResult, authResult)
                                                                             .map((_, _) -> Unit.unit())
                                                                             .async()));
    }

    private static final class Admission {
        private final Promise<Unit> drained = Promise.promise();
        private boolean ready;
        private boolean draining;
        private int inFlight;
        private long accepted;
        private long failedWrites;

        synchronized Unit start() {
            ready = true;

            return Unit.unit();
        }

        synchronized Status status() {
            return new Status(ready, inFlight, accepted, failedWrites);
        }

        synchronized boolean admit() {
            if (!ready) {
                return false;
            }

            inFlight++;
            accepted++;

            return true;
        }

        synchronized Unit complete(Result<Unit> result) {
            inFlight--;
            if (result.isFailure()) {
                failedWrites++;
            }

            finishDrain();

            return Unit.unit();
        }

        synchronized Promise<Unit> drain() {
            ready = false;
            draining = true;
            finishDrain();

            return drained;
        }

        private void finishDrain() {
            if (draining && inFlight == 0) {
                drained.succeed(Unit.unit());
            }
        }
    }
}
