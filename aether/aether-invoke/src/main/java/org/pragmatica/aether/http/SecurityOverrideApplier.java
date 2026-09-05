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
        // #772 review item 3: an Unspecified route's declared strength (-1) is not its EFFECTIVE
        // strength — that depends on the deployment's global security mode, which this publish-time
        // call site cannot see (aether-invoke does not depend on aether-config's SecurityMode).
        // Comparing against the raw -1 would let every override "strengthen" an undeclared route,
        // defeating STRENGTHEN_ONLY. Refuse instead of guessing.
        if (route.security() instanceof SecurityPolicy.Unspecified) {
            LOG.warn("Security override rejected (STRENGTHEN_ONLY): {} {} has no declared policy; "
                     + "effective policy is not known at publish time, refusing override to {}",
                     route.httpMethod(),
                     route.pathPrefix(),
                     newPolicy.asString());

            return route;
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
