// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.security.AuthorizationRole;
import org.pragmatica.aether.http.handler.security.Role;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.http.handler.security.SecurityContextHolder;
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
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1778 / #1102: `DELETE /repository/<group>/<artifact>/<version>` was declared (`ARTIFACT_DELETE`) and
/// served by nobody. It now archives the version, behind the same OPERATOR-or-ADMIN admission as a push.
class MavenProtocolRoutesArchiveTest {
    private static final TimeSpan SHORT_TIMEOUT = timeSpan(2).seconds();
    private static final String VERSION_PATH = ManagementRoute.ARTIFACT_DELETE.prefix() + "/org/example/app/1.0.0";

    private final CopyOnWriteArrayList<String> deleted = new CopyOnWriteArrayList<>();

    @Test
    void handle_deleteWithoutAnOperator_isRejected_andNothingIsArchived() {
        var response = new CapturingResponseWriter();

        var handled = routes(MavenResponse.json(new byte[0])).handle(deleteRequest(), response);

        assertThat(handled).as("the route claims DELETE under /repository/").isTrue();
        assertThat(response.awaitStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(deleted).as("an unauthenticated caller must not reach the archive").isEmpty();
    }

    @Test
    void handle_deleteWithAViewer_isRejected() {
        var response = new CapturingResponseWriter();
        var viewer = SecurityContext.securityContext("viewer-key", Set.of(Role.SERVICE), AuthorizationRole.VIEWER)
                                    .unwrap();

        ScopedValue.where(SecurityContextHolder.scopedValue(), viewer)
                   .run(() -> routes(MavenResponse.json(new byte[0])).handle(deleteRequest(), response));

        assertThat(response.awaitStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(deleted).isEmpty();
    }

    @Test
    void handle_deleteWithAnOperator_archivesThePath_andPassesTheHandlerStatusThrough() {
        var operator = SecurityContext.securityContext("operator-key", Set.of(Role.SERVICE), AuthorizationRole.OPERATOR)
                                      .unwrap();

        assertThat(statusOf(operator, MavenResponse.json(new byte[0]))).isEqualTo(HttpStatus.OK);
        assertThat(statusOf(operator, MavenResponse.conflict("too young"))).as("409 for a young version")
                                                                           .isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf(operator, MavenResponse.notFound("never written"))).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(deleted).containsOnly(VERSION_PATH);
    }

    @Test
    void handle_getOfAnArchivedVersion_answersGone() {
        var response = new CapturingResponseWriter();

        routes(MavenResponse.json(new byte[0]), MavenResponse.gone("archived")).handle(getRequest(), response);

        assertThat(response.awaitStatus()).isEqualTo(HttpStatus.GONE);
    }

    private HttpStatus statusOf(SecurityContext context, MavenResponse answer) {
        var response = new CapturingResponseWriter();

        ScopedValue.where(SecurityContextHolder.scopedValue(), context)
                   .run(() -> routes(answer).handle(deleteRequest(), response));

        return response.awaitStatus();
    }

    private MavenProtocolRoutes routes(MavenResponse deleteAnswer) {
        return routes(deleteAnswer, MavenResponse.ok(new byte[0], "text/plain"));
    }

    private MavenProtocolRoutes routes(MavenResponse deleteAnswer, MavenResponse getAnswer) {
        return MavenProtocolRoutes.mavenProtocolRoutes(() -> nodeWith(handler(deleteAnswer, getAnswer)),
                                                       SHORT_TIMEOUT,
                                                       () -> false,
                                                       () -> true);
    }

    private MavenProtocolHandler handler(MavenResponse deleteAnswer, MavenResponse getAnswer) {
        return new MavenProtocolHandler() {
            @Override
            public Promise<MavenResponse> handleGet(String path) {
                return Promise.success(getAnswer);
            }

            @Override
            public Promise<MavenResponse> handlePut(String path, byte[] content) {
                return Promise.success(MavenResponse.json(new byte[0]));
            }

            @Override
            public Promise<MavenResponse> handleDelete(String path) {
                deleted.add(path);

                return Promise.success(deleteAnswer);
            }
        };
    }

    private static HttpRequest deleteRequest() {
        return request(HttpMethod.DELETE, VERSION_PATH);
    }

    private static HttpRequest getRequest() {
        return request(HttpMethod.GET, VERSION_PATH + "/app-1.0.0.jar");
    }

    private static HttpRequest request(HttpMethod method, String path) {
        return new HttpRequest() {
            @Override
            public String requestId() {
                return "req_test";
            }

            @Override
            public HttpMethod method() {
                return method;
            }

            @Override
            public String path() {
                return path;
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
                                                       (_, method, _) -> answer(handler, method.getName()));
    }

    private static Object answer(MavenProtocolHandler handler, String methodName) {
        if ("mavenProtocolHandler".equals(methodName)) {
            return handler;
        }

        throw new UnsupportedOperationException("Not implemented in test proxy: " + methodName);
    }

    private static final class CapturingResponseWriter implements ResponseWriter {
        private final CountDownLatch written = new CountDownLatch(1);
        private volatile HttpStatus status;

        HttpStatus awaitStatus() {
            try {
                written.await(5, TimeUnit.SECONDS);
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
