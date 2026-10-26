// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.pragmatica.aether.api.ManagementServerError;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.resource.artifact.MavenProtocolHandler;
import org.pragmatica.aether.resource.artifact.MavenProtocolHandler.MavenResponse;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.QueryParams;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Every write verb of the Maven protocol routes (PUT, POST, DELETE-archive) sends a failure that is not a timeout or a
/// tier refusal through the management status funnel, with the request path as `instance` and the request id in the
/// problem body: a cause that states its status answers it, an unclassified one answers 500. One case per verb and outcome.
/// Push admission is satisfied by dev mode, so the failure path itself is what is under test.
class MavenProtocolRoutesFailureFunnelTest {
    private static final TimeSpan TIMEOUT = timeSpan(150).millis();
    private static final String ARTIFACT_PATH = ManagementRoute.ARTIFACT_GET.prefix() + "/org/example/app/1.0.0/app-1.0.0.jar";
    private static final String VERSION_PATH = ManagementRoute.ARTIFACT_DELETE.prefix() + "/org/example/app/1.0.0";

    private record Verb(HttpMethod method, String path) {}

    @TestFactory
    Stream<DynamicTest> everyWriteVerb_typedCause_answersItsStatus_withPathAndRequestId() {
        return verbs().map(verb -> DynamicTest.dynamicTest(verb.method() + " typed",
                                                           () -> assertFailure(verb,
                                                                               new ManagementServerError.NotFound("no such artifact"),
                                                                               HttpStatus.NOT_FOUND)));
    }

    @TestFactory
    Stream<DynamicTest> everyWriteVerb_unclassifiedCause_answers500_withPathAndRequestId() {
        return verbs().map(verb -> DynamicTest.dynamicTest(verb.method() + " unclassified",
                                                           () -> assertFailure(verb, Causes.cause("boom"), HttpStatus.INTERNAL_SERVER_ERROR)));
    }

    private static Stream<Verb> verbs() {
        return List.of(new Verb(HttpMethod.PUT, ARTIFACT_PATH),
                       new Verb(HttpMethod.POST, ARTIFACT_PATH),
                       new Verb(HttpMethod.DELETE, VERSION_PATH))
                   .stream();
    }

    private static void assertFailure(Verb verb, Cause cause, HttpStatus expected) {
        var routes = MavenProtocolRoutes.mavenProtocolRoutes(() -> nodeWith(failing(cause)), TIMEOUT, () -> true, () -> false);
        var response = new CapturingResponseWriter();

        routes.handle(request(verb), response);

        assertThat(response.awaitStatus()).as(verb.method().toString()).isEqualTo(expected);
        assertThat(response.body()).contains("\"instance\":\"" + verb.path() + "\"").contains("req_test");
    }

    private static MavenProtocolHandler failing(Cause cause) {
        return new MavenProtocolHandler() {
            @Override
            public Promise<MavenResponse> handleGet(String path) {
                return Promise.failure(cause);
            }

            @Override
            public Promise<MavenResponse> handlePut(String path, byte[] content) {
                return Promise.failure(cause);
            }

            @Override
            public Promise<MavenResponse> handleDelete(String path) {
                return Promise.failure(cause);
            }
        };
    }

    private static HttpRequest request(Verb verb) {
        return new HttpRequest() {
            @Override
            public String requestId() {
                return "req_test";
            }

            @Override
            public HttpMethod method() {
                return verb.method();
            }

            @Override
            public String path() {
                return verb.path();
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
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> {
                                                           if ("mavenProtocolHandler".equals(method.getName())) {
                                                               return handler;
                                                           }
                                                           throw new UnsupportedOperationException(method.getName());
                                                       });
    }

    private static final class CapturingResponseWriter implements ResponseWriter {
        private final CountDownLatch written = new CountDownLatch(1);
        private volatile HttpStatus status;
        private volatile String body = "";

        HttpStatus awaitStatus() {
            try {
                written.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return status;
        }

        String body() {
            return body;
        }

        @Override
        public void write(HttpStatus status, byte[] body, ContentType contentType) {
            this.body = new String(body, StandardCharsets.UTF_8);
            this.status = status;
            written.countDown();
        }

        @Override
        public ResponseWriter header(String name, String value) {
            return this;
        }
    }
}
