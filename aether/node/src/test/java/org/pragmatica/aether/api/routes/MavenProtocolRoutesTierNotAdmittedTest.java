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
import org.pragmatica.storage.StorageError;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #874/#875 round-1 fix: `StorageError.TierNotAdmitted` reaching the maven-protocol surface is
/// transient by construction (a request raced a DHT tier's post-formation admission check), NOT a
/// server defect -- so it must answer 503 Service Unavailable with a `Retry-After` hint, telling
/// `mvn deploy` to retry, rather than 500 Internal Server Error, which tells it the opposite. Uses
/// the GET path (mirrors `MavenProtocolRoutesTimeoutTest`) because `get` was already gated on
/// `admission()` before #874 -- no auth setup needed to reach `sendFailureResponse`.
class MavenProtocolRoutesTierNotAdmittedTest {
    private static final TimeSpan SHORT_TIMEOUT = timeSpan(150).millis();
    private static final String GET_PATH = ManagementRoute.ARTIFACT_GET.prefix() + "/org/example/app/1.0.0/app-1.0.0.jar";

    @Test
    void handle_getWhoseHandlerFailsWithTierNotAdmitted_writes503WithRetryAfter() {
        var cause = new StorageError.TierNotAdmitted("content", 30_000L);
        var routes = MavenProtocolRoutes.mavenProtocolRoutes(() -> nodeWith(failingHandler(cause)), SHORT_TIMEOUT);
        var response = new CapturingResponseWriter();

        var handled = routes.handle(getRequest(), response);

        assertThat(handled).isTrue();
        assertThat(response.awaitStatus()).as("a gated-write/read race is transient, not a server bug")
                                          .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.header("Retry-After")).as("must tell the caller to retry, not just that it failed")
                                                   .isEqualTo("1");
    }

    @Test
    void handle_getWhoseHandlerFailsWithSomeOtherCause_stillWrites500() {
        var cause = new StorageError.WriteError("disk full");
        var routes = MavenProtocolRoutes.mavenProtocolRoutes(() -> nodeWith(failingHandler(cause)), SHORT_TIMEOUT);
        var response = new CapturingResponseWriter();

        routes.handle(getRequest(), response);

        assertThat(response.awaitStatus()).as("TierNotAdmitted's remap must not swallow genuine server errors")
                                          .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.header("Retry-After")).isNull();
    }

    private static MavenProtocolHandler failingHandler(StorageError cause) {
        return new MavenProtocolHandler() {
            @Override
            public Promise<MavenResponse> handleGet(String path) {
                return Promise.failure(cause);
            }

            @Override
            public Promise<MavenResponse> handlePut(String path, byte[] content) {
                return Promise.failure(cause);
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
        private final Map<String, String> headers = new LinkedHashMap<>();
        private volatile HttpStatus status;

        HttpStatus awaitStatus() {
            try {
                written.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            return status;
        }

        String header(String name) {
            return headers.get(name);
        }

        @Override
        public void write(HttpStatus status, byte[] body, ContentType contentType) {
            this.status = status;
            written.countDown();
        }

        @Override
        public ResponseWriter header(String name, String value) {
            headers.put(name, value);

            return this;
        }
    }
}
