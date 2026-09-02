// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.routes.MavenProtocolRoutes.PushAdmission;
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

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Defense-in-depth authorization for artifact publication (#282). Artifact PUT/POST flow into the
/// `ArtifactStore` that the cluster resolves and loads as code, so an unauthenticated push is an RCE.
/// This verifies the in-route guard rejects pushes that lack an authenticated OPERATOR+ context while
/// permitting an authenticated OPERATOR/ADMIN push.
///
/// #520 unified the two dev switches that used to half-overlap here. TWO postures now relax the gate:
/// insecure dev mode (`AETHER_INSECURE_DEV_MODE=true`, the integration-test harness posture) and
/// app-HTTP `security_mode = NONE` (the documented dev/eval bootstrap posture, under which no
/// `SecurityContext` is ever bound and OPERATOR is structurally unholdable — gating publication behind
/// it made such a cluster unable to receive artifacts at all). Under `API_KEY`/`JWT` the gate is
/// unchanged: anonymous and VIEWER callers are still refused. Both injectable `BooleanSupplier`s
/// exercise every combination without touching the real process environment or the node's config.
/// Every relaxed acceptance must also be LOUD — the captured log4j2 events pin that warning.
class MavenProtocolRoutesAuthTest {
    private static final TimeSpan SHORT_TIMEOUT = timeSpan(2).seconds();
    private static final String PUT_PATH = ManagementRoute.ARTIFACT_GET.prefix()
                                         + "/org/example/app/1.0.0/app-1.0.0.jar";
    private static final String ROUTE_LOGGER = MavenProtocolRoutes.class.getName();
    /// The two independent dev switches #520 unified, named so each test states the posture it pins.
    private static final BooleanSupplier DEV_MODE_ON = () -> true;
    private static final BooleanSupplier DEV_MODE_OFF = () -> false;
    private static final BooleanSupplier SECURITY_ENABLED = () -> true;
    private static final BooleanSupplier SECURITY_DISABLED = () -> false;

    private CapturingAppender appender;
    private LoggerContext loggerContext;

    @BeforeEach
    void installLogCapture() {
        Configurator.setLevel(ROUTE_LOGGER, Level.WARN);
        loggerContext = (LoggerContext) LogManager.getContext(false);
        appender = new CapturingAppender();
        appender.start();
        loggerContext.getConfiguration()
                     .getLoggerConfig(ROUTE_LOGGER)
                     .addAppender(appender, Level.WARN, null);
        loggerContext.updateLoggers();
    }

    @AfterEach
    void removeLogCapture() {
        loggerContext.getConfiguration()
                     .getLoggerConfig(ROUTE_LOGGER)
                     .removeAppender(appender.getName());
        appender.stop();
        loggerContext.updateLoggers();
    }

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

    // #520 — security_mode = NONE implies the dev-mode posture for the publication gate.

    @Test
    void handle_putWithSecurityModeNoneAndNoSecurityContext_allowsPush() {
        var routes = routesWith(DEV_MODE_OFF, SECURITY_DISABLED);
        var response = new CapturingResponseWriter();

        var handled = routes.handle(putRequest(), response);

        assertThat(handled).isTrue();
        assertThat(response.awaitStatus()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void handle_putWithSecurityEnabledAndNoSecurityContext_rejectedUnauthorized() {
        var routes = routesWith(DEV_MODE_OFF, SECURITY_ENABLED);
        var response = new CapturingResponseWriter();

        var handled = routes.handle(putRequest(), response);

        assertThat(handled).isTrue();
        assertThat(response.awaitStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void handle_putWithSecurityEnabledAndViewerContext_rejectedUnauthorized() {
        var routes = routesWith(DEV_MODE_OFF, SECURITY_ENABLED);
        var response = new CapturingResponseWriter();
        var viewer = SecurityContext.securityContext("viewer-key", Set.of(Role.SERVICE), AuthorizationRole.VIEWER)
                                    .unwrap();

        var handled = ScopedValue.where(SecurityContextHolder.scopedValue(), viewer)
                                 .call(() -> routes.handle(putRequest(), response));

        assertThat(handled).isTrue();
        assertThat(response.awaitStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void handle_putWithSecurityEnabledAndOperatorContext_allowsPush() {
        var routes = routesWith(DEV_MODE_OFF, SECURITY_ENABLED);
        var response = new CapturingResponseWriter();
        var operator = SecurityContext.securityContext("operator-key", Set.of(Role.SERVICE), AuthorizationRole.OPERATOR)
                                      .unwrap();

        var handled = ScopedValue.where(SecurityContextHolder.scopedValue(), operator)
                                 .call(() -> routes.handle(putRequest(), response));

        assertThat(handled).isTrue();
        assertThat(response.awaitStatus()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void admitPush_withOperatorContextUnderSecurityModeNone_authenticatedOperator() {
        var routes = routesWith(DEV_MODE_OFF, SECURITY_DISABLED);
        var operator = SecurityContext.securityContext("operator-key", Set.of(Role.SERVICE), AuthorizationRole.OPERATOR)
                                      .unwrap();

        var admission = ScopedValue.where(SecurityContextHolder.scopedValue(), operator)
                                   .call(routes::admitPush);

        assertThat(admission).isEqualTo(PushAdmission.AUTHENTICATED_OPERATOR);
    }

    @Test
    void admitPush_withDevModeOnAndSecurityEnabled_insecureDevMode() {
        assertThat(routesWith(DEV_MODE_ON, SECURITY_ENABLED).admitPush()).isEqualTo(PushAdmission.INSECURE_DEV_MODE);
    }

    @Test
    void admitPush_withDevModeOffAndSecurityModeNone_securityDisabled() {
        assertThat(routesWith(DEV_MODE_OFF, SECURITY_DISABLED).admitPush()).isEqualTo(PushAdmission.SECURITY_DISABLED);
    }

    @Test
    void admitPush_withDevModeOffAndSecurityEnabled_denied() {
        assertThat(routesWith(DEV_MODE_OFF, SECURITY_ENABLED).admitPush()).isEqualTo(PushAdmission.DENIED);
    }

    // #520 requirement 3 — a relaxed acceptance is a security-relevant bypass and must be LOUD.

    @Test
    void handle_putAcceptedUnderSecurityModeNone_warnsAboutUnauthenticatedPublication() {
        var routes = routesWith(DEV_MODE_OFF, SECURITY_DISABLED);
        var response = new CapturingResponseWriter();

        routes.handle(putRequest(), response);

        assertThat(response.awaitStatus()).isEqualTo(HttpStatus.OK);
        assertThat(appender.warnings()).hasSize(1);
        assertThat(appender.warnings().getFirst()).contains("UNAUTHENTICATED artifact publication")
                                                  .contains("security_mode=NONE")
                                                  .contains(PUT_PATH);
    }

    @Test
    void handle_putAcceptedUnderDevMode_warnsAboutUnauthenticatedPublication() {
        var routes = routesWith(DEV_MODE_ON, SECURITY_ENABLED);
        var response = new CapturingResponseWriter();

        routes.handle(putRequest(), response);

        assertThat(response.awaitStatus()).isEqualTo(HttpStatus.OK);
        assertThat(appender.warnings()).hasSize(1);
        assertThat(appender.warnings().getFirst()).contains("UNAUTHENTICATED artifact publication")
                                                  .contains("AETHER_INSECURE_DEV_MODE=true")
                                                  .contains(PUT_PATH);
    }

    @Test
    void handle_putWithOperatorContextUnderSecurityModeNone_warnsNothing() {
        var routes = routesWith(DEV_MODE_OFF, SECURITY_DISABLED);
        var response = new CapturingResponseWriter();
        var operator = SecurityContext.securityContext("operator-key", Set.of(Role.SERVICE), AuthorizationRole.OPERATOR)
                                      .unwrap();

        ScopedValue.where(SecurityContextHolder.scopedValue(), operator)
                   .run(() -> routes.handle(putRequest(), response));

        assertThat(response.awaitStatus()).isEqualTo(HttpStatus.OK);
        assertThat(appender.warnings()).isEmpty();
    }

    @Test
    void handle_putRejectedUnderSecurityEnabled_warnsNothing() {
        var routes = routesWith(DEV_MODE_OFF, SECURITY_ENABLED);
        var response = new CapturingResponseWriter();

        routes.handle(putRequest(), response);

        assertThat(response.awaitStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(appender.warnings()).isEmpty();
    }

    private static MavenProtocolRoutes routesWith(BooleanSupplier devModeEnabled,
                                                  BooleanSupplier appHttpSecurityEnabled) {
        return MavenProtocolRoutes.mavenProtocolRoutes(() -> nodeWith(resolvingHandler()),
                                                       SHORT_TIMEOUT,
                                                       devModeEnabled,
                                                       appHttpSecurityEnabled);
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

    /// Captures WARN events emitted by `MavenProtocolRoutes` so the #520 loud-bypass warning can be
    /// asserted directly rather than eyeballed. The node logs through SLF4J onto log4j2, so the
    /// appender attaches to the log4j2 `LoggerConfig` for the route's logger name; `Configurator`
    /// raises that logger to WARN because the default configuration would otherwise filter it.
    private static final class CapturingAppender extends AbstractAppender {
        private final List<String> messages = new CopyOnWriteArrayList<>();

        private CapturingAppender() {
            super("maven-protocol-routes-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            messages.add(event.getMessage().getFormattedMessage());
        }

        List<String> warnings() {
            return List.copyOf(messages);
        }
    }
}
