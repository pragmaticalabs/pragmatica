// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.util.List;

import org.pragmatica.aether.http.handler.HttpRouteDefinition;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.slice.blueprint.SecurityOverridePolicy;
import org.pragmatica.aether.slice.blueprint.SecurityOverrides;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@SuppressWarnings({"JBCT-UTIL-02", "JBCT-ZONE-02"})
public interface SecurityOverrideApplier {
    Logger LOG = LoggerFactory.getLogger(SecurityOverrideApplier.class);

    static List<HttpRouteDefinition> applyOverrides(List<HttpRouteDefinition> routes, SecurityOverrides overrides) {
        if (overrides.isEmpty()) {
            return routes;
        }

        return routes.stream()
                     .map(route -> applyOverrideToRoute(route, overrides))
                     .toList();
    }

    private static HttpRouteDefinition applyOverrideToRoute(HttpRouteDefinition route, SecurityOverrides overrides) {
        return overrides.findMatch(route.httpMethod(),
                                   route.pathPrefix())
                        .map(SecurityPolicy::fromBlueprintString)
                        .map(newPolicy -> applyWithPolicy(route,
                                                          newPolicy,
                                                          overrides.policy()))
                        .or(route);
    }

    private static HttpRouteDefinition applyWithPolicy(HttpRouteDefinition route,
                                                       SecurityPolicy newPolicy,
                                                       SecurityOverridePolicy policy) {
        return switch (policy) {
            case FULL -> applyAndLog(route, newPolicy);
            case STRENGTHEN_ONLY -> applyIfStronger(route, newPolicy);
            case NONE -> rejectOverride(route, newPolicy);
        };
    }

    private static HttpRouteDefinition applyIfStronger(HttpRouteDefinition route, SecurityPolicy newPolicy) {
        if (route.security() instanceof SecurityPolicy.Unspecified) {
            return applyToUndeclaredRoute(route, newPolicy);
        }

        if (newPolicy.strength() >= route.security().strength()) {
            return applyAndLog(route, newPolicy);
        }

        LOG.warn("Security override rejected (STRENGTHEN_ONLY): {} {} would weaken from {} to {}",
                 route.httpMethod(),
                 route.pathPrefix(),
                 route.security().asString(),
                 newPolicy.asString());

        return route;
    }

    /// An undeclared route's declared strength (-1) is not its EFFECTIVE strength: that depends on
    /// the deployment's global security mode, which this publish-time call site cannot see
    /// (`aether-invoke` does not depend on `aether-config`'s `SecurityMode`). The raw strength
    /// comparison is therefore meaningless here, and only the DIRECTION of the requested change can
    /// be judged.
    ///
    /// `public` is the one override that weakens the route under every global mode, because it is
    /// the floor. It is refused. Every other override is applied, where the previous revision refused
    /// them all.
    ///
    /// ## What this rule does NOT do -- read before relying on it (#866 review G2)
    ///
    /// It does not make an operator override effective on the node HOSTING the route. This function's
    /// output reaches only the replicated KV entry: `HttpRoutePublisher` writes RAW routes into
    /// `publishedRoutes` and applies overrides into a local `effectiveRoutes` used solely to build
    /// that entry. The local authorization decision (`AppHttpServer.findRouteSecurityPolicy` ->
    /// `HttpRoutePublisher.findLocalRoute`) reads `publishedRoutes`, i.e. PRE-override state; and the
    /// node's own KV entry is excluded from its own `remoteRoutes` by identity, so the overridden
    /// value cannot arrive that way either.
    ///
    /// Consequence: an override governs requests that arrive at OTHER nodes (which resolve it from
    /// the KV entry and enforce it before forwarding) and does NOT govern requests that arrive
    /// directly at the hosting node. Enforcement depends on which node the client connects to. That
    /// mis-plumbing is PRE-EXISTING, not introduced here, and is tracked as #887; fixing it means
    /// deciding where override resolution belongs, which is a design change with its own review.
    ///
    /// So: this rule restores the applier's half of the F1 fix -- a strengthening override is no
    /// longer refused outright -- and does not by itself close the privilege escalation F1 described.
    ///
    /// ## Residuals this rule leaves open
    ///
    ///   1. Under `security_mode = "api-key"`, an override to `authenticated` (strength 10) on a
    ///      route whose effective policy is `ApiKeyRequired` (20) is a weakening still allowed.
    ///   2. The same holds under `security_mode = "jwt"`, where the effective policy is
    ///      `BearerTokenRequired` (also 20). `SecurityMode` has exactly three constants, so 1 and 2
    ///      together cover the whole enforcing set; only `none` is unaffected.
    ///   3. A credential-TYPE mismatch is not a strength weakening at all and so is invisible to the
    ///      comparison above: `bearer_token` under `api-key` mode, or `api_key` under `jwt` mode,
    ///      scores 20 either way. Those now fail CLOSED at the enforcement end
    ///      (`SecurityError.UNENFORCEABLE_POLICY`); before #866 review G1 they were served with no
    ///      credential inspected at all.
    ///
    /// Closing 1 and 2 requires the applier to know the global security mode at publish time.
    private static HttpRouteDefinition applyToUndeclaredRoute(HttpRouteDefinition route, SecurityPolicy newPolicy) {
        if (newPolicy instanceof SecurityPolicy.Public) {
            LOG.warn("Security override rejected (STRENGTHEN_ONLY): {} {} has no declared policy; "
                    + "refusing override to {}, which weakens the route under every global security mode",
                     route.httpMethod(),
                     route.pathPrefix(),
                     newPolicy.asString());

            return route;
        }

        return applyAndLog(route, newPolicy);
    }

    private static HttpRouteDefinition applyAndLog(HttpRouteDefinition route, SecurityPolicy newPolicy) {
        LOG.info("Security override applied: {} {} changed from {} to {}",
                 route.httpMethod(),
                 route.pathPrefix(),
                 route.security().asString(),
                 newPolicy.asString());

        return HttpRouteDefinition.httpRouteDefinition(route.httpMethod(),
                                                       route.pathPrefix(),
                                                       route.artifactCoord(),
                                                       route.sliceMethod(),
                                                       newPolicy);
    }

    private static HttpRouteDefinition rejectOverride(HttpRouteDefinition route, SecurityPolicy newPolicy) {
        LOG.warn("Security override rejected (policy=NONE): {} {} override to {} ignored",
                 route.httpMethod(),
                 route.pathPrefix(),
                 newPolicy.asString());

        return route;
    }
}
