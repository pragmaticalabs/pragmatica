package org.pragmatica.aether.http;

import org.pragmatica.aether.http.handler.HttpRouteDefinition;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.slice.blueprint.SecurityOverridePolicy;
import org.pragmatica.aether.slice.blueprint.SecurityOverrides;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Applies blueprint security overrides to route definitions at publication time.
///
/// Override application is subject to the blueprint's override policy:
/// - STRENGTHEN_ONLY: override applied only if new strength >= original strength
/// - FULL: any override is applied
/// - NONE: all overrides are rejected (logged as warnings)
@SuppressWarnings({"JBCT-UTIL-02", "JBCT-ZONE-02"})
public interface SecurityOverrideApplier {
    Logger LOG = LoggerFactory.getLogger(SecurityOverrideApplier.class);

    /// Apply security overrides to a list of route definitions.
    /// Returns new list with overrides applied subject to the policy.
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
        if (newPolicy.strength() >= route.security()
                                         .strength()) {
            return applyAndLog(route, newPolicy);
        }
        LOG.warn("Security override rejected (STRENGTHEN_ONLY): {} {} would weaken from {} to {}",
                 route.httpMethod(),
                 route.pathPrefix(),
                 route.security()
                      .asString(),
                 newPolicy.asString());
        return route;
    }

    private static HttpRouteDefinition applyAndLog(HttpRouteDefinition route, SecurityPolicy newPolicy) {
        LOG.info("Security override applied: {} {} changed from {} to {}",
                 route.httpMethod(),
                 route.pathPrefix(),
                 route.security()
                      .asString(),
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
