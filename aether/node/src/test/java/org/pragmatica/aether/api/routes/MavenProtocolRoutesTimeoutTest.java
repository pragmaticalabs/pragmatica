// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.resource.artifact.MavenProtocolHandler;
import org.pragmatica.aether.resource.artifact.MavenProtocolHandler.MavenResponse;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.QueryParams;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Verifies the HTTP backstop: a maven-protocol request whose handler promise never resolves
/// must NOT leave the connection open forever (the observed Hetzner 3h hang). The per-request
/// deadline writes a 504 Gateway Timeout on expiry, which the response writer turns into a body
/// write + (for non-keep-alive) connection close.
class MavenProtocolRoutesTimeoutTest {
    private static final TimeSpan SHORT_TIMEOUT = timeSpan(150).millis();
    private static final String GET_PATH = ManagementRoute.ARTIFACT_GET.prefix() + "/org/example/app/1.0.0/app-1.0.0.jar";

    @Test
    void handle_getWhoseHandlerNeverResolves_writes504WithinBound() {
        var routes = MavenProtocolRoutes.mavenProtocolRoutes(() -> nodeWith(neverResolvingHandler()), SHORT_TIMEOUT);
        var response = new CapturingResponseWriter();

        var handled = routes.handle(getRequest(), response);

        assertThat(handled).isTrue();
        assertThat(response.awaitStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
    }

    @Test
    void handle_getWhoseHandlerResolves_writesUnderlyingStatus() {
        var ok = MavenResponse.ok("body".getBytes(), "application/octet-stream");
        var routes = MavenProtocolRoutes.mavenProtocolRoutes(() -> nodeWith(resolvingHandler(ok)), SHORT_TIMEOUT);
        var response = new CapturingResponseWriter();

        routes.handle(getRequest(), response);

        assertThat(response.awaitStatus()).isEqualTo(HttpStatus.OK);
    }

    private static MavenProtocolHandler neverResolvingHandler() {
        return new MavenProtocolHandler() {
            @Override
            public Promise<MavenResponse> handleGet(String path) {
                return Promise.promise();  // never resolves
            }

            @Override
            public Promise<MavenResponse> handlePut(String path, byte[] content) {
                return Promise.promise();
            }
        };
    }

    private static MavenProtocolHandler resolvingHandler(MavenResponse response) {
        return new MavenProtocolHandler() {
            @Override
            public Promise<MavenResponse> handleGet(String path) {
                return Promise.success(response);
            }

            @Override
            public Promise<MavenResponse> handlePut(String path, byte[] content) {
                return Promise.success(response);
            }
        };
    }

    private static HttpRequest getRequest() {
        return new HttpRequest() {
            @Override
            public String requestId() {
                return "req_test";
            }

            @Override
            public HttpMethod method() {
                return HttpMethod.GET;
            }

            @Override
            public String path() {
                return GET_PATH;
            }

            @Override
            public Headers headers() {
                return Headers.empty();
            }

            @Override
            public QueryParams queryParams() {
                return QueryParams.empty();
            }

            @Override
            public byte[] body() {
                return new byte[0];
            }
        };
    }

    private static ManageableNode nodeWith(MavenProtocolHandler handler) {
        return (ManageableNode) Proxy.newProxyInstance(
            ManageableNode.class.getClassLoader(),
            new Class[]{ManageableNode.class},
            (_, method, _) -> {
                if ("mavenProtocolHandler".equals(method.getName())) {
                    return handler;
                }
                throw new UnsupportedOperationException("Not implemented in test proxy: " + method.getName());
            });
    }

    private static final class CapturingResponseWriter implements ResponseWriter {
        private final java.util.concurrent.CountDownLatch written = new java.util.concurrent.CountDownLatch(1);
        private volatile HttpStatus status;

        HttpStatus awaitStatus() {
            try {
                written.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            return status;
        }

        @Override
        public void write(HttpStatus status, byte[] body, ContentType contentType) {
            this.status = status;
            written.countDown();
        }

        @Override
        public ResponseWriter header(String name, String value) {
            return this;
        }
    }
}
