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

    /// Publish-path form: resolves every route's override and ANNOUNCES each decision, because a
    /// publication is a discrete operator-facing event worth one log line per route.
    static List<HttpRouteDefinition> applyOverrides(List<HttpRouteDefinition> routes, SecurityOverrides overrides) {
        if (overrides.isEmpty()) {
            return routes;
        }

        return routes.stream()
                     .map(route -> applyOverrideToRoute(route, overrides, Announce.LOG))
                     .toList();
    }

    /// Request-path form (#887): resolves ONE route's override without logging.
    ///
    /// Same rule, same code, different verbosity — the decision below has exactly one
    /// implementation, and the `Announce` flag chooses only whether it narrates itself. Splitting
    /// the RULE in two so the request path could stay quiet is precisely how the published entry and
    /// the local decision came to disagree in the first place; that must not be re-introduced to
    /// save a log line.
    ///
    /// Quiet because this runs per REQUEST: `HttpRoutePublisherImpl.findLocalRoute` calls it on the
    /// matched route so the hosting node's authorization decision reads the same overridden policy
    /// the KV entry advertises. Announcing here would emit a line per request.
    static HttpRouteDefinition applyOverride(HttpRouteDefinition route, SecurityOverrides overrides) {
        if (overrides.isEmpty()) {
            return route;
        }

        return applyOverrideToRoute(route, overrides, Announce.QUIET);
    }

    /// Whether an override decision narrates itself. Publication announces; per-request resolution
    /// does not.
    enum Announce {
        LOG,
        QUIET
    }

    private static HttpRouteDefinition applyOverrideToRoute(HttpRouteDefinition route,
                                                            SecurityOverrides overrides,
                                                            Announce announce) {
        return overrides.findMatch(route.httpMethod(),
                                   route.pathPrefix())
                        .map(SecurityPolicy::fromBlueprintString)
                        .map(newPolicy -> applyWithPolicy(route,
                                                          newPolicy,
                                                          overrides.policy(),
                                                          announce))
                        .or(route);
    }

    private static HttpRouteDefinition applyWithPolicy(HttpRouteDefinition route,
                                                       SecurityPolicy newPolicy,
                                                       SecurityOverridePolicy policy,
                                                       Announce announce) {
        return switch (policy) {
            case FULL -> applyAndLog(route, newPolicy, announce);
            case STRENGTHEN_ONLY -> applyIfStronger(route, newPolicy, announce);
            case NONE -> rejectOverride(route, newPolicy, announce);
        };
    }

    private static HttpRouteDefinition applyIfStronger(HttpRouteDefinition route,
                                                       SecurityPolicy newPolicy,
                                                       Announce announce) {
        if (route.security() instanceof SecurityPolicy.Unspecified) {
            return applyToUndeclaredRoute(route, newPolicy, announce);
        }

        if (newPolicy.strength() >= route.security().strength()) {
            return applyAndLog(route, newPolicy, announce);
        }

        if (announce == Announce.LOG) {
            LOG.warn("Security override rejected (STRENGTHEN_ONLY): {} {} would weaken from {} to {}",
                     route.httpMethod(),
                     route.pathPrefix(),
                     route.security().asString(),
                     newPolicy.asString());
        }

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
    /// ## Reach of this rule on the HOSTING node -- CHANGED by #887, read the dates
    ///
    /// Until #887 this rule's output reached ONLY the replicated KV entry, while the hosting node's
    /// own authorization decision read the raw pre-override routes -- so an override governed
    /// requests arriving at other nodes and not requests arriving at the node actually serving them.
    /// The #866 review G2 note recording that is superseded and has been removed rather than left
    /// standing, because a comment describing a hole that is now closed reads as a live warning.
    ///
    /// It now reaches both. `HttpRoutePublisherImpl.findLocalRoute` calls [#applyOverride] on the
    /// matched route at REQUEST time, so the local decision and the published entry are derived by
    /// this same function from the same `activeOverrides`. Read-time resolution -- rather than
    /// applying overrides into `publishedRoutes` at publication -- is deliberate: `activeOverrides`
    /// changes at runtime, so any second collection holding a pre-resolved copy would be a snapshot
    /// that can disagree with the current overrides, which is the shape of the original defect.
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
    /// Closing 1 and 2 requires the applier to know the global security mode. #887 did NOT close
    /// them: it changed WHERE this rule is consulted, not WHAT it decides, and `aether-invoke` still
    /// cannot see `aether-config`'s `SecurityMode` from either call site. They remain open, and they
    /// remain reachable on the hosting node exactly as they are on any other.
    private static HttpRouteDefinition applyToUndeclaredRoute(HttpRouteDefinition route,
                                                               SecurityPolicy newPolicy,
                                                               Announce announce) {
        if (newPolicy instanceof SecurityPolicy.Public) {
            if (announce == Announce.LOG) {
                LOG.warn("Security override rejected (STRENGTHEN_ONLY): {} {} has no declared policy; "
                        + "refusing override to {}, which weakens the route under every global security mode",
                         route.httpMethod(),
                         route.pathPrefix(),
                         newPolicy.asString());
            }

            return route;
        }

        return applyAndLog(route, newPolicy, announce);
    }

    private static HttpRouteDefinition applyAndLog(HttpRouteDefinition route,
                                                   SecurityPolicy newPolicy,
                                                   Announce announce) {
        if (announce == Announce.LOG) {
            LOG.info("Security override applied: {} {} changed from {} to {}",
                     route.httpMethod(),
                     route.pathPrefix(),
                     route.security().asString(),
                     newPolicy.asString());
        }

        return HttpRouteDefinition.httpRouteDefinition(route.httpMethod(),
                                                       route.pathPrefix(),
                                                       route.artifactCoord(),
                                                       route.sliceMethod(),
                                                       newPolicy);
    }

    private static HttpRouteDefinition rejectOverride(HttpRouteDefinition route,
                                                      SecurityPolicy newPolicy,
                                                      Announce announce) {
        if (announce == Announce.LOG) {
            LOG.warn("Security override rejected (policy=NONE): {} {} override to {} ignored",
                     route.httpMethod(),
                     route.pathPrefix(),
                     newPolicy.asString());
        }

        return route;
    }
}
