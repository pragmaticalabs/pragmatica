// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.http;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.pragmatica.aether.http.adapter.SliceRouterFactory;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.http.security.HttpAuthenticator;
import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.HttpError;
import org.pragmatica.http.ContentCategory;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.server.HttpServer;
import org.pragmatica.http.server.HttpServerConfig;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
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
    private final List<TerraHttpMount> mounts;
    private final Map<String, HttpServer> mountTransports = new ConcurrentHashMap<>();
    private final Admission admission = new Admission();
    private final Promise<Unit> closed = Promise.promise();
    private Option<HttpServer> transport = Option.empty();
    private boolean closing;

    private TerraHttpServer(TerraApplication application,
                            HttpAuthenticator authenticator,
                            TerraHttpRoutes routes,
                            List<TerraHttpMount> mounts) {
        this.application = application;
        this.authenticator = authenticator;
        this.routes = routes;
        this.mounts = List.copyOf(mounts);
    }

    /// Routes take the same JSON wire form as on Aether: the router's generated codec, with
    /// `JsonMapper.valueObjectJsonMapper()` as the fallback mapper, so a nested value object is bare in
    /// both directions.
    public static Promise<TerraHttpServer> start(TerraApplication application,
                                                 TerraHttpConfig config,
                                                 HttpAuthenticator authenticator) {
        return start(application, config, authenticator, List.of());
    }

    /// As [#start(TerraApplication, TerraHttpConfig, HttpAuthenticator)], with host mounts served beside the slice
    /// routes (#3651); see [TerraHttpMount] for how a mount shares or owns a listener.
    public static Promise<TerraHttpServer> start(TerraApplication application,
                                                 TerraHttpConfig config,
                                                 HttpAuthenticator authenticator,
                                                 List<TerraHttpMount> mounts) {
        return TerraHttpRoutes.discover().fold(cause -> failedStart(cause.result(), application, authenticator),
                                               factories -> start(application,
                                                                  config,
                                                                  authenticator,
                                                                  factories,
                                                                  JsonMapper.valueObjectJsonMapper(),
                                                                  mounts));
    }

    /// Ownership transfers on entry, including on failed startup. Factories outside the selected
    /// application are ignored; ambiguous or incompatible selected routes fail before binding.
    ///
    /// `mapper` is the Jackson fallback of a slice's routes only while the slice's JSON naming is `as_declared` and
    /// its JSON options are the defaults (#2378). A slice built with `[json] naming` in its `routes.toml` replaces it
    /// with `JsonMapper.valueObjectJsonMapper(naming, options)`, so a customised `mapper` is not used for that slice.
    public static Promise<TerraHttpServer> start(TerraApplication application,
                                                 TerraHttpConfig config,
                                                 HttpAuthenticator authenticator,
                                                 List<SliceRouterFactory<?>> factories,
                                                 JsonMapper mapper) {
        return start(application, config, authenticator, factories, mapper, List.of());
    }

    /// As [#start(TerraApplication, TerraHttpConfig, HttpAuthenticator, List, JsonMapper)], with host mounts. A slice
    /// route under a shared mount's base path, or two shared mounts whose base paths overlap, fail before binding.
    public static Promise<TerraHttpServer> start(TerraApplication application,
                                                 TerraHttpConfig config,
                                                 HttpAuthenticator authenticator,
                                                 List<SliceRouterFactory<?>> factories,
                                                 JsonMapper mapper,
                                                 List<TerraHttpMount> mounts) {
        return Result.all(TerraHttpConfig.terraHttpConfig(config.transport(),
                                                          config.mountMode(),
                                                          config.defaultPolicy()),
                          checkMounts(mounts))
                     .flatMap((valid, reserved) -> TerraHttpRoutes.terraHttpRoutes(application,
                                                                                   factories,
                                                                                   valid,
                                                                                   authenticator,
                                                                                   mapper,
                                                                                   reserved))
                     .async()
                     .flatMap(routes -> new TerraHttpServer(application, authenticator, routes, mounts).bind(config))
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

    /// The reserved prefixes of the shared listener: `/__terra/` and each shared mount's base path.
    private static Result<List<String>> checkMounts(List<TerraHttpMount> mounts) {
        var reserved = new ArrayList<>(List.of("/__terra/"));

        for (var mount : mounts.stream().filter(TerraHttpMount::shared).toList()) {
            if (reserved.stream()
                        .anyMatch(prefix -> prefix.startsWith(mount.reservedPrefix()) || mount.reservedPrefix()
                                                                                              .startsWith(prefix))) {
                return new TerraHttpError.InvalidConfiguration("Overlapping mount base path: " + mount.basePath()).result();
            }

            reserved.add(mount.reservedPrefix());
        }

        return Result.success(List.copyOf(reserved));
    }

    // Own mount listeners bind first, then the slice listener; any bind failure stops whatever did bind, so the
    // failed start releases every listener before it releases the application.
    private Promise<TerraHttpServer> bind(TerraHttpConfig config) {
        var listeners = mounts.stream()
                              .flatMap(mount -> mount.listener()
                                                     .map(listener -> bindMount(mount, listener))
                                                     .stream())
                              .toList();

        return Promise.allOf(listeners)
                      .flatMap(bound -> Result.allOf(bound).async())
                      .flatMap(_ -> HttpServer.httpServer(config.transport(),
                                                          this::handle))
                      .map(this::admitOn)
                      .fold(result -> result.isSuccess()
                                      ? result.async()
                                      : stopTransports().fold(_ -> result.async()));
    }

    private Promise<Unit> bindMount(TerraHttpMount mount, HttpServerConfig listener) {
        return HttpServer.httpServer(listener, (request, writer) -> serveMount(mount, request, writer)).map(server -> registerMount(mount,
                                                                                                                                    server));
    }

    private Unit registerMount(TerraHttpMount mount, HttpServer server) {
        mountTransports.put(mount.basePath(), server);

        return Unit.unit();
    }

    private TerraHttpServer admitOn(HttpServer server) {
        transport = Option.some(server);
        admission.start();

        return this;
    }

    private Promise<Unit> stopTransports() {
        var stops = Stream.concat(transport.stream(),
                                  mountTransports.values().stream()).map(HttpServer::stop).toList();

        return Promise.allOf(stops).flatMap(stopped -> Result.allOf(stopped)
                                                             .map(_ -> Unit.unit())
                                                             .async());
    }

    public int port() {
        return transport.map(HttpServer::port)
                        .or(0);
    }

    /// The bound port of the mount with this base path when it owns a listener; empty for a shared or unknown mount.
    public Option<Integer> mountPort(String basePath) {
        return Option.option(mountTransports.get(basePath)).map(HttpServer::port);
    }

    /// Snapshot includes response flushes in the in-flight count. No route or principal details leak.
    public Status status() {
        return admission.status();
    }

    public record Status(boolean ready, int inFlight, long accepted, long failedWrites) {}

    @Contract
    private void handle(HttpRequest request, ResponseWriter writer) {
        var mount = Option.from(mounts.stream()
                                      .filter(TerraHttpMount::shared)
                                      .filter(candidate -> candidate.owns(request.path()))
                                      .findFirst());

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
        } else if (mount.isPresent()) {
            mount.onPresent(owner -> serveMount(owner, request, writer));
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
                            () -> routes.handle(requestContext(request)))
                      .flatMap(promise -> promise);
    }

    private static HttpRequestContext requestContext(HttpRequest request) {
        return HttpRequestContext.httpRequestContext(request.path(),
                                                     request.method().name(),
                                                     request.queryParams().asMap(),
                                                     request.headers().asMap(),
                                                     request.body(),
                                                     request.trailers().asMap(),
                                                     request.requestId(),
                                                     SecurityContext.securityContext());
    }

    // Fire-and-forget by the transport callback contract: the response write is the outcome, and every failure
    // above it has already been turned into a response by mountResponse.
    @Contract
    private void serveMount(TerraHttpMount mount, HttpRequest request, ResponseWriter writer) {
        if (mount.owns(request.path())) {
            authorizedMountCall(mount, requestContext(request)).fold(result -> write(writer, mountResponse(result)));
        } else {
            writer.error(HttpStatus.NOT_FOUND, "No route found");
        }
    }

    private Promise<HttpResponseData> authorizedMountCall(TerraHttpMount mount, HttpRequestContext context) {
        return Result.lift(Causes::fromThrowable,
                           () -> mount.policy()
                                      .apply(context))
                     .async()
                     .flatMap(policy -> TerraHttpRoutes.authorize(context, policy, authenticator))
                     .flatMap(security -> callMount(mount,
                                                    context.withSecurity(security)));
    }

    private Promise<HttpResponseData> callMount(TerraHttpMount mount, HttpRequestContext context) {
        return Promise.lift(Causes::fromThrowable,
                            () -> mount.handler()
                                       .apply(context,
                                              status()))
                      .flatMap(promise -> promise);
    }

    // FER: a refused or failed mount call degrades to its HTTP answer -- the refusal's status and headers (a 401 keeps
    // its challenge), any other failure a 500 -- so the request always completes with a response.
    private static HttpResponseData mountResponse(Result<HttpResponseData> result) {
        return result.fold(TerraHttpServer::refusalResponse, response -> response);
    }

    private static HttpResponseData refusalResponse(Cause cause) {
        return cause instanceof HttpError error
               ? HttpResponseData.httpResponseData(error.status().code(),
                                                   error.responseHeaders(),
                                                   error.clientMessage().getBytes(StandardCharsets.UTF_8))
               : HttpResponseData.httpResponseData(500, "Internal server error");
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
                     .flatMap(_ -> stopTransports())
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
