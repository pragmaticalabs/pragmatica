// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.http.handler.security.AuthorizationRole;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.http.handler.security.SecurityContextHolder;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.resource.artifact.MavenProtocolHandler.MavenResponse;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.http.ContentCategory;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.server.RequestContext;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.CoreError;
import org.pragmatica.lang.io.TimeSpan;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.pragmatica.http.HttpMethod.GET;
import static org.pragmatica.http.HttpMethod.POST;
import static org.pragmatica.http.HttpMethod.PUT;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


public final class MavenProtocolRoutes implements RouteHandler {
    private static final String DEV_MODE_ENV = "AETHER_INSECURE_DEV_MODE";
    private static final String REPOSITORY_PREFIX = ManagementRoute.ARTIFACT_GET.prefix() + "/";
    private static final String REPOSITORY_INFO_PREFIX = ManagementRoute.ARTIFACT_INFO.prefix() + "/";
    /// Per-request deadline (HTTP backstop). The maven protocol handler delegates to the
    /// artifact store, whose resolve/deploy pipelines are now individually bounded — but this
    /// deadline guarantees the connection NEVER leaks even if a future handler path is added
    /// without its own timeout, or the store's promise is otherwise abandoned. On expiry a 504
    /// Gateway Timeout is written so the connection is released (the body write +
    /// non-keep-alive close path), rather than the request leaving the socket open forever (the
    /// observed 3h Hetzner hang). Generous relative to the store's own resolve ceiling.
    private static final TimeSpan REQUEST_TIMEOUT = timeSpan(150).seconds();

    private final Supplier<ManageableNode> nodeSupplier;
    private final TimeSpan requestTimeout;
    private final BooleanSupplier devModeEnabled;

    private MavenProtocolRoutes(Supplier<ManageableNode> nodeSupplier,
                                TimeSpan requestTimeout,
                                BooleanSupplier devModeEnabled) {
        this.nodeSupplier = nodeSupplier;
        this.requestTimeout = requestTimeout;
        this.devModeEnabled = devModeEnabled;
    }

    public static MavenProtocolRoutes mavenProtocolRoutes(Supplier<ManageableNode> nodeSupplier) {
        return new MavenProtocolRoutes(nodeSupplier, REQUEST_TIMEOUT, MavenProtocolRoutes::devModeFromEnv);
    }

    /// Variant with an explicit per-request deadline. Used by tests that drive the 504-on-expiry
    /// path with a short `TimeSpan` against a never-resolving handler promise.
    public static MavenProtocolRoutes mavenProtocolRoutes(Supplier<ManageableNode> nodeSupplier,
                                                          TimeSpan requestTimeout) {
        return new MavenProtocolRoutes(nodeSupplier, requestTimeout, MavenProtocolRoutes::devModeFromEnv);
    }

    /// Test-friendly factory: callers (unit tests) inject the dev-mode flag directly rather than
    /// mutating the JVM-wide environment. Mirrors the pattern used by `CertificateRoutes`.
    public static MavenProtocolRoutes mavenProtocolRoutes(Supplier<ManageableNode> nodeSupplier,
                                                          TimeSpan requestTimeout,
                                                          BooleanSupplier devModeEnabled) {
        return new MavenProtocolRoutes(nodeSupplier, requestTimeout, devModeEnabled);
    }

    private static boolean devModeFromEnv() {
        return "true".equalsIgnoreCase(System.getenv(DEV_MODE_ENV));
    }

    @Override
    public boolean handle(RequestContext ctx, ResponseWriter response) {
        var path = ctx.path();
        var method = ctx.method();

        if (!path.startsWith(REPOSITORY_PREFIX) || path.startsWith(REPOSITORY_INFO_PREFIX)) {
            return false;
        }

        if (method == GET) {
            handleGet(response, path);

            return true;
        }

        if (method == POST || method == PUT) {
            if (!isAuthorizedForPush()) {
                rejectUnauthorizedPush(response, path);

                return true;
            }

            handlePut(response, path, ctx.body());

            return true;
        }

        return false;
    }

    /// Defense-in-depth authorization for artifact publication (#282). Artifact PUT/POST place code
    /// the cluster will resolve and load, so an unauthenticated push is an RCE. The management gate in
    /// `ManagementServer.handleRequest` already enforces OPERATOR+ on `/repository/` mutations when
    /// management security is enabled; this in-route check guarantees the same posture even if an
    /// operator has explicitly disabled management security (`SecurityMode.NONE`) — in that case no
    /// `SecurityContext` is bound and the push is rejected rather than silently accepted.
    ///
    /// Insecure dev mode (`AETHER_INSECURE_DEV_MODE=true`) is the one exception: the operator has
    /// explicitly opted out of all management security, so the push gate relaxes consistently with
    /// that posture (the integration-test harness runs `security_mode=NONE`). Outside dev mode the
    /// behavior is unchanged — an authenticated OPERATOR+ context is required. Reads (GET) are
    /// intentionally not gated here: artifact resolution is also driven by internal cluster paths.
    private boolean isAuthorizedForPush() {
        return devModeEnabled.getAsBoolean() || hasAuthenticatedOperator();
    }

    private static boolean hasAuthenticatedOperator() {
        return SecurityContextHolder.currentContext()
                                    .filter(SecurityContext::isAuthenticated)
                                    .map(MavenProtocolRoutes::hasOperatorRole)
                                    .or(false);
    }

    private static boolean hasOperatorRole(SecurityContext context) {
        return context.authorizationRole()
                      .hasAccess(AuthorizationRole.OPERATOR);
    }

    private void rejectUnauthorizedPush(ResponseWriter response, String path) {
        response.header("WWW-Authenticate", "ApiKey realm=\"Aether\"");
        response.error(HttpStatus.UNAUTHORIZED, "Artifact publication requires OPERATOR or ADMIN authentication");
    }

    @Contract
    private void handleGet(ResponseWriter response, String uri) {
        var node = nodeSupplier.get();

        node.mavenProtocolHandler().handleGet(uri).timeout(requestTimeout).onSuccess(r -> sendProtocolResponse(response,
                                                                                                               r)).onFailure(cause -> sendFailureResponse(response,
                                                                                                                                                          cause));
    }

    @Contract
    private void handlePut(ResponseWriter response, String uri, byte[] content) {
        var node = nodeSupplier.get();

        node.mavenProtocolHandler().handlePut(uri, content).timeout(requestTimeout).onSuccess(r -> sendProtocolResponse(response,
                                                                                                                        r)).onFailure(cause -> sendFailureResponse(response,
                                                                                                                                                                   cause));
    }

    private void sendFailureResponse(ResponseWriter response, Cause cause) {
        if (cause instanceof CoreError.Timeout) {
            response.error(HttpStatus.GATEWAY_TIMEOUT, cause.message());

            return;
        }

        response.internalError(cause);
    }

    private void sendProtocolResponse(ResponseWriter response, MavenResponse mavenResponse) {
        var status = findHttpStatus(mavenResponse.statusCode());

        response.write(status,
                       mavenResponse.content(),
                       ContentType.contentType(mavenResponse.contentType(), categoryFor(mavenResponse.contentType())));
    }

    private ContentCategory categoryFor(String contentType) {
        if (contentType == null) {
            return ContentCategory.BINARY;
        }

        if (contentType.startsWith("application/json") || contentType.startsWith("application/problem+json")) {
            return ContentCategory.JSON;
        }

        if (contentType.startsWith("application/xml")) {
            return ContentCategory.XML;
        }

        if (contentType.startsWith("text/")) {
            return ContentCategory.TEXT;
        }

        return ContentCategory.BINARY;
    }

    private HttpStatus findHttpStatus(int code) {
        for (var status : HttpStatus.values()) {
            if (status.code() == code) {
                return status;
            }
        }

        return HttpStatus.OK;
    }
}
