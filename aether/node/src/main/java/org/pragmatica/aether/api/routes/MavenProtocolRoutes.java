// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.pragmatica.aether.http.handler.security.AuthorizationRole;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.http.handler.security.SecurityContextHolder;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.resource.artifact.MavenProtocolHandler.MavenResponse;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.http.ContentCategory;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.CoreError;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.storage.StorageError;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.http.HttpMethod.GET;
import static org.pragmatica.http.HttpMethod.POST;
import static org.pragmatica.http.HttpMethod.PUT;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


public final class MavenProtocolRoutes implements RouteHandler {
    private static final Logger log = LoggerFactory.getLogger(MavenProtocolRoutes.class);
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
    /// Default posture for the factories that do not carry the node's app-HTTP security mode
    /// (timeout tests, dev-mode tests). SECURE by default: the #520 relaxation must be opted into
    /// explicitly, never inherited by a caller that simply did not know about it.
    private static final BooleanSupplier SECURITY_ENABLED = () -> true;
    /// #874/#875: `StorageError.TierNotAdmitted` means a request reached a DHT-backed tier before
    /// its post-formation marker check ([org.pragmatica.aether.node.StorageFactory#verifyDhtMarker])
    /// resolved the admission gate -- transient by construction, not a server defect, and the check
    /// itself resolves in well under a second on a live cluster. `1` is a deliberately small,
    /// unmeasured retry hint [design intent — unverified]: it need only beat `mvn deploy`'s own
    /// "do not retry a 500" default so a CI deploy racing a node restart backs off and succeeds on
    /// its own retry instead of failing hard.
    private static final int TIER_NOT_ADMITTED_RETRY_AFTER_SECONDS = 1;

    /// Security-relevant bypass notices (#520). Both name the artifact, the posture that admitted it,
    /// and what an operator must change — a WARN nobody can read past without understanding it.
    private static final String DEV_MODE_PUSH_WARNING = "SECURITY: accepted UNAUTHENTICATED artifact publication "
                                                      + "of {} — {}=true. Anyone who can reach the management port "
                                                      + "can load code into this cluster. Never use this posture "
                                                      + "in production.";

    private static final String SECURITY_DISABLED_PUSH_WARNING = "SECURITY: accepted UNAUTHENTICATED artifact "
                                                               + "publication of {} — app-HTTP security is disabled "
                                                               + "(security_mode=NONE), so no caller can hold "
                                                               + "OPERATOR and publication is unauthenticated. "
                                                               + "Anyone who can reach the management port can load "
                                                               + "code into this cluster. Set security_mode=api-key "
                                                               + "for anything but dev/eval.";

    private final Supplier<ManageableNode> nodeSupplier;
    private final TimeSpan requestTimeout;
    private final BooleanSupplier devModeEnabled;
    private final BooleanSupplier appHttpSecurityEnabled;

    private MavenProtocolRoutes(Supplier<ManageableNode> nodeSupplier,
                                TimeSpan requestTimeout,
                                BooleanSupplier devModeEnabled,
                                BooleanSupplier appHttpSecurityEnabled) {
        this.nodeSupplier = nodeSupplier;
        this.requestTimeout = requestTimeout;
        this.devModeEnabled = devModeEnabled;
        this.appHttpSecurityEnabled = appHttpSecurityEnabled;
    }

    /// Production factory. `appHttpSecurityEnabled` MUST be the node's EFFECTIVE app-HTTP security
    /// posture — `AppHttpConfig.securityEnabled()`, i.e. `security_mode != NONE` — which is the very
    /// value `ManagementServer` gates its own management auth on (`AetherNode` derives both from
    /// `config.appHttp()`). Supplied rather than re-read from the environment or re-parsed from TOML
    /// so the route and the server can never disagree about the node's posture.
    public static MavenProtocolRoutes mavenProtocolRoutes(Supplier<ManageableNode> nodeSupplier,
                                                          BooleanSupplier appHttpSecurityEnabled) {
        return new MavenProtocolRoutes(nodeSupplier,
                                       REQUEST_TIMEOUT,
                                       MavenProtocolRoutes::devModeFromEnv,
                                       appHttpSecurityEnabled);
    }

    /// Variant with an explicit per-request deadline. Used by tests that drive the 504-on-expiry
    /// path with a short `TimeSpan` against a never-resolving handler promise.
    public static MavenProtocolRoutes mavenProtocolRoutes(Supplier<ManageableNode> nodeSupplier,
                                                          TimeSpan requestTimeout) {
        return new MavenProtocolRoutes(nodeSupplier,
                                       requestTimeout,
                                       MavenProtocolRoutes::devModeFromEnv,
                                       SECURITY_ENABLED);
    }

    /// Test-friendly factory: callers (unit tests) inject the dev-mode flag directly rather than
    /// mutating the JVM-wide environment. Mirrors the pattern used by `CertificateRoutes`.
    public static MavenProtocolRoutes mavenProtocolRoutes(Supplier<ManageableNode> nodeSupplier,
                                                          TimeSpan requestTimeout,
                                                          BooleanSupplier devModeEnabled) {
        return new MavenProtocolRoutes(nodeSupplier, requestTimeout, devModeEnabled, SECURITY_ENABLED);
    }

    /// Test-friendly factory carrying BOTH dev switches, so the #520 unification can be exercised
    /// in every combination without touching the process environment or the node's config.
    public static MavenProtocolRoutes mavenProtocolRoutes(Supplier<ManageableNode> nodeSupplier,
                                                          TimeSpan requestTimeout,
                                                          BooleanSupplier devModeEnabled,
                                                          BooleanSupplier appHttpSecurityEnabled) {
        return new MavenProtocolRoutes(nodeSupplier, requestTimeout, devModeEnabled, appHttpSecurityEnabled);
    }

    private static boolean devModeFromEnv() {
        return "true".equalsIgnoreCase(System.getenv(DEV_MODE_ENV));
    }

    @Override
    public boolean handle(HttpRequest ctx, ResponseWriter response) {
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
            handlePush(response, path, ctx.body());

            return true;
        }

        return false;
    }

    /// Why an artifact publication was admitted — or refused. Drives BOTH the HTTP outcome and the
    /// security-relevant warning: every value other than [#AUTHENTICATED_OPERATOR] means code was
    /// accepted into the cluster without anyone proving they were allowed to put it there.
    enum PushAdmission {
        /// A bound `SecurityContext` carrying OPERATOR or ADMIN. The only silent acceptance.
        AUTHENTICATED_OPERATOR,
        /// `AETHER_INSECURE_DEV_MODE=true` in the node's environment.
        INSECURE_DEV_MODE,
        /// The node's app-HTTP `security_mode` is `NONE`, so no caller can hold any role (#520).
        SECURITY_DISABLED,
        /// Security is on and the caller is anonymous or below OPERATOR.
        DENIED
    }

    @Contract
    private void handlePush(ResponseWriter response, String path, byte[] content) {
        var admission = admitPush();

        if (admission == PushAdmission.DENIED) {
            rejectUnauthorizedPush(response, path);

            return;
        }

        warnUnauthenticatedPush(admission, path);
        handlePut(response, path, content);
    }

    /// Defense-in-depth authorization for artifact publication (#282, #520). Artifact PUT/POST place
    /// code the cluster will resolve and load, so an unauthenticated push is an RCE. The management
    /// gate in `ManagementServer.handleRequest` already enforces OPERATOR+ on `/repository/`
    /// mutations when management security is enabled; this in-route check guarantees the same posture
    /// independently.
    ///
    /// TWO postures relax it, and they are deliberately unified (#520). Insecure dev mode
    /// (`AETHER_INSECURE_DEV_MODE=true`) is the explicit opt-out used by the integration-test harness.
    /// App-HTTP `security_mode = NONE` is the documented dev/eval posture set from bootstrap config:
    /// under it no `SecurityContext` is ever bound and API keys are ignored, so OPERATOR is
    /// structurally unholdable — gating publication behind a role nobody can hold made a NONE-mode
    /// cluster unable to receive artifacts at all (401 on `aether artifacts push`), which is the worst
    /// of both worlds: no security AND no function. A node that has declared "no security" accepts
    /// publication, loudly. Under `API_KEY`/`JWT` the gate is unchanged — anonymous and VIEWER callers
    /// are still refused. Reads (GET) are intentionally not gated: artifact resolution is also driven
    /// by internal cluster paths.
    PushAdmission admitPush() {
        if (hasAuthenticatedOperator()) {
            return PushAdmission.AUTHENTICATED_OPERATOR;
        }

        if (devModeEnabled.getAsBoolean()) {
            return PushAdmission.INSECURE_DEV_MODE;
        }

        if (!appHttpSecurityEnabled.getAsBoolean()) {
            return PushAdmission.SECURITY_DISABLED;
        }

        return PushAdmission.DENIED;
    }

    /// One WARN per accepted UNAUTHENTICATED publish, naming the artifact and the posture that
    /// admitted it. Not throttled and not demoted to a startup-only notice: publication is an
    /// operator-initiated control-plane action (a handful of PUTs per push), not a hot path, and
    /// suppressing occurrences would hide WHICH code entered the cluster unauthenticated.
    @Contract
    private static void warnUnauthenticatedPush(PushAdmission admission, String path) {
        switch (admission) {
            case INSECURE_DEV_MODE -> log.warn(DEV_MODE_PUSH_WARNING, path, DEV_MODE_ENV);
            case SECURITY_DISABLED -> log.warn(SECURITY_DISABLED_PUSH_WARNING, path);
            case AUTHENTICATED_OPERATOR, DENIED -> {}
        }
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

        node.mavenProtocolHandler()
            .handleGet(uri)
            .timeout(requestTimeout)
            .onSuccess(r -> sendProtocolResponse(response, r))
            .onFailure(cause -> sendFailureResponse(response, cause));
    }

    @Contract
    private void handlePut(ResponseWriter response, String uri, byte[] content) {
        var node = nodeSupplier.get();

        node.mavenProtocolHandler()
            .handlePut(uri, content)
            .timeout(requestTimeout)
            .onSuccess(r -> sendProtocolResponse(response, r))
            .onFailure(cause -> sendFailureResponse(response, cause));
    }

    private void sendFailureResponse(ResponseWriter response, Cause cause) {
        if (cause instanceof CoreError.Timeout) {
            response.error(HttpStatus.GATEWAY_TIMEOUT, cause.message());

            return;
        }

        if (cause instanceof StorageError.TierNotAdmitted) {
            response.header("Retry-After", String.valueOf(TIER_NOT_ADMITTED_RETRY_AFTER_SECONDS));
            response.error(HttpStatus.SERVICE_UNAVAILABLE, cause.message());

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

    // RET-06: `contentType` is a nullable HTTP response header; the null coalesce to a default
    // category is parse-don't-validate of wire input.
    @SuppressWarnings("JBCT-RET-06")
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
