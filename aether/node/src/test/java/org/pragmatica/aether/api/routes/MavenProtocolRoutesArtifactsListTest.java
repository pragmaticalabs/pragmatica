// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.api.routes;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// #523: `GET /repository/artifacts` must reach the operator as an honest **501 Not Implemented**,
/// not the `400 Cannot parse path` it used to produce.
///
/// The path-recognition logic lives in `MavenProtocolHandler` (covered by `MavenProtocolHandlerTest`
/// in the artifact-repo module). What is proven HERE is the node-side half that the handler cannot
/// prove on its own: that `MavenProtocolRoutes` still claims the path (so no other route has to),
/// and that `findHttpStatus` carries a 501 status code through to `HttpStatus.NOT_IMPLEMENTED` on
/// the wire — a code the route had never emitted before.
class MavenProtocolRoutesArtifactsListTest {
    private static final TimeSpan SHORT_TIMEOUT = timeSpan(5).seconds();
    private static final String ARTIFACTS_LIST_PATH = ManagementRoute.REPOSITORY_ARTIFACTS_LIST.prefix();

    @Test
    void handle_getArtifactsList_writesNotImplemented() {
        var routes = routesWith(notImplementedHandler());
        var response = new CapturingResponseWriter();

        var handled = routes.handle(getRequest(ARTIFACTS_LIST_PATH), response);

        assertThat(handled).isTrue();
        assertThat(response.awaitStatus()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
    }

    /// The route must keep CLAIMING the path. Returning `false` would drop it into the generic
    /// router, which has no `REPOSITORY_ARTIFACTS_LIST` handler and would answer a bare 404 —
    /// losing the message that tells the operator what to use instead.
    @Test
    void handle_getArtifactsList_isClaimedByMavenRoute() {
        var routes = routesWith(notImplementedHandler());

        assertThat(routes.handle(getRequest(ARTIFACTS_LIST_PATH), new CapturingResponseWriter())).isTrue();
    }

    /// A genuinely malformed coordinate keeps its 400 end-to-end — the 501 arm must not have
    /// widened into "anything under /repository/ that fails to parse".
    @Test
    void handle_getMalformedCoordinate_writesBadRequest() {
        var routes = routesWith(badRequestHandler());
        var response = new CapturingResponseWriter();

        var handled = routes.handle(getRequest("/repository/org/example/bad"), response);

        assertThat(handled).isTrue();
        assertThat(response.awaitStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /// Regression guard for the CLASS, not just the instance (#525).
    ///
    /// `MavenProtocolRoutes` claims everything under `/repository/` and feeds it to a maven
    /// coordinate parser. The genuine coordinate routes all declare the bare prefix `/repository`
    /// and carry their coordinate as path parameters. Any route with a LONGER `/repository/...`
    /// prefix is therefore NOT a coordinate — the parser will mis-read its literal segment as part
    /// of the groupId — so it needs an explicit answer somewhere.
    ///
    /// Both such routes exist today and are handled in two DIFFERENT modules
    /// (`ARTIFACT_INFO` via `REPOSITORY_INFO_PREFIX` here; `REPOSITORY_ARTIFACTS_LIST` via
    /// `ARTIFACTS_LIST_PATH` in artifact-repo). Nothing else would fail when a third is added —
    /// which is precisely how #523 shipped. This test fails that addition loudly.
    @Test
    void managementRoutes_underRepositoryPrefix_areBareCoordinateOrExplicitlyHandled() {
        var handled = Set.of(ManagementRoute.REPOSITORY_ARTIFACTS_LIST,  // 501 in MavenProtocolHandler (#523)
                             ManagementRoute.ARTIFACT_INFO);             // excluded via REPOSITORY_INFO_PREFIX

        var unhandled = Arrays.stream(ManagementRoute.values())
                              .filter(route -> route.prefix().startsWith("/repository"))
                              .filter(route -> !"/repository".equals(route.prefix()))
                              .filter(route -> !handled.contains(route))
                              .toList();

        assertThat(unhandled)
                .withFailMessage("These ManagementRoute entries declare a /repository/<literal> prefix, so "
                                + "MavenProtocolRoutes claims them and the maven coordinate parser will "
                                + "mis-read the literal segment as a groupId and answer a misleading 400 "
                                + "(#523). Give each one an explicit answer (see ARTIFACTS_LIST_PATH in "
                                + "MavenProtocolHandler, or REPOSITORY_INFO_PREFIX in MavenProtocolRoutes) "
                                + "and list it here: %s",
                                 unhandled)
                .isEmpty();
    }

    private static MavenProtocolRoutes routesWith(MavenProtocolHandler handler) {
        return MavenProtocolRoutes.mavenProtocolRoutes(() -> nodeWith(handler), SHORT_TIMEOUT);
    }

    private static MavenProtocolHandler notImplementedHandler() {
        return fixedHandler(MavenResponse.notImplemented("Repository-wide artifact listing is not implemented."));
    }

    private static MavenProtocolHandler badRequestHandler() {
        return fixedHandler(MavenResponse.badRequest("Cannot parse path: /repository/org/example/bad"));
    }

    private static MavenProtocolHandler fixedHandler(MavenResponse fixed) {
        return new MavenProtocolHandler() {
            @Override
            public Promise<MavenResponse> handleGet(String path) {
                return Promise.success(fixed);
            }

            @Override
            public Promise<MavenResponse> handlePut(String path, byte[] content) {
                return Promise.success(fixed);
            }
        };
    }

    private static HttpRequest getRequest(String requestPath) {
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
                return requestPath;
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
