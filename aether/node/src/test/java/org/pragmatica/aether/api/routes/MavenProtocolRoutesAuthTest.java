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
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.QueryParams;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import java.lang.reflect.Proxy;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Defense-in-depth authorization for artifact publication (#282). Artifact PUT/POST flow into the
/// `ArtifactStore` that the cluster resolves and loads as code, so an unauthenticated push is an RCE.
/// This verifies the in-route guard rejects pushes that lack an authenticated OPERATOR+ context —
/// including the case where management security is disabled and no context is bound at all — while
/// permitting an authenticated OPERATOR/ADMIN push. Insecure dev mode
/// (`AETHER_INSECURE_DEV_MODE=true`) is the one relaxation: the operator has explicitly opted out of
/// management security, so the push gate allows pushes with no bound context (integration-test
/// harness posture). The injectable `BooleanSupplier` exercises both dev-mode states without
/// touching the real process environment.
class MavenProtocolRoutesAuthTest {
    private static final TimeSpan SHORT_TIMEOUT = timeSpan(2).seconds();
    private static final String PUT_PATH = ManagementRoute.ARTIFACT_GET.prefix()
                                         + "/org/example/app/1.0.0/app-1.0.0.jar";

    @Test
    void handle_putWithNoSecurityContext_rejectedUnauthorized() {
        var routes = MavenProtocolRoutes.mavenProtocolRoutes(() -> nodeWith(resolvingHandler()), SHORT_TIMEOUT);
        var response = new CapturingResponseWriter();

        var handled = routes.handle(putRequest(), response);

        assertThat(handled).isTrue();
        assertThat(response.awaitStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void handle_putWithDevModeOffAndNoSecurityContext_rejectedUnauthorized() {
        var routes = MavenProtocolRoutes.mavenProtocolRoutes(() -> nodeWith(resolvingHandler()),
                                                             SHORT_TIMEOUT,
                                                             () -> false);
        var response = new CapturingResponseWriter();

        var handled = routes.handle(putRequest(), response);

        assertThat(handled).isTrue();
        assertThat(response.awaitStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void handle_putWithDevModeOnAndNoSecurityContext_allowsPush() {
        var routes = MavenProtocolRoutes.mavenProtocolRoutes(() -> nodeWith(resolvingHandler()),
                                                             SHORT_TIMEOUT,
                                                             () -> true);
        var response = new CapturingResponseWriter();

        var handled = routes.handle(putRequest(), response);

        assertThat(handled).isTrue();
        assertThat(response.awaitStatus()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void handle_putWithViewerContext_rejectedUnauthorized() {
        var routes = MavenProtocolRoutes.mavenProtocolRoutes(() -> nodeWith(resolvingHandler()), SHORT_TIMEOUT);
        var response = new CapturingResponseWriter();
        var viewer = SecurityContext.securityContext("viewer-key", Set.of(Role.SERVICE), AuthorizationRole.VIEWER)
                                    .unwrap();

        var handled = ScopedValue.where(SecurityContextHolder.scopedValue(), viewer)
                                 .call(() -> routes.handle(putRequest(), response));

        assertThat(handled).isTrue();
        assertThat(response.awaitStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void handle_putWithOperatorContext_allowsPush() {
        var routes = MavenProtocolRoutes.mavenProtocolRoutes(() -> nodeWith(resolvingHandler()), SHORT_TIMEOUT);
        var response = new CapturingResponseWriter();
        var operator = SecurityContext.securityContext("operator-key", Set.of(Role.SERVICE), AuthorizationRole.OPERATOR)
                                      .unwrap();

        var handled = ScopedValue.where(SecurityContextHolder.scopedValue(), operator)
                                 .call(() -> routes.handle(putRequest(), response));

        assertThat(handled).isTrue();
        assertThat(response.awaitStatus()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void handle_putWithAdminContext_allowsPush() {
        var routes = MavenProtocolRoutes.mavenProtocolRoutes(() -> nodeWith(resolvingHandler()), SHORT_TIMEOUT);
        var response = new CapturingResponseWriter();
        var admin = SecurityContext.securityContext("admin-key", Set.of(Role.ADMIN), AuthorizationRole.ADMIN)
                                   .unwrap();

        var handled = ScopedValue.where(SecurityContextHolder.scopedValue(), admin)
                                 .call(() -> routes.handle(putRequest(), response));

        assertThat(handled).isTrue();
        assertThat(response.awaitStatus()).isEqualTo(HttpStatus.OK);
    }

    private static MavenProtocolHandler resolvingHandler() {
        var ok = MavenResponse.ok("body".getBytes(), "application/octet-stream");

        return new MavenProtocolHandler() {
            @Override
            public Promise<MavenResponse> handleGet(String path) {
                return Promise.success(ok);
            }

            @Override
            public Promise<MavenResponse> handlePut(String path, byte[] content) {
                return Promise.success(ok);
            }
        };
    }

    private static HttpRequest putRequest() {
        return new HttpRequest() {
            @Override
            public String requestId() {
                return "req_test";
            }

            @Override
            public HttpMethod method() {
                return HttpMethod.PUT;
            }

            @Override
            public String path() {
                return PUT_PATH;
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
                return "artifact-bytes".getBytes();
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
