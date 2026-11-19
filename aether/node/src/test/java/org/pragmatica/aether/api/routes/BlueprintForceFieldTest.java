// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.deployment.cluster.BlueprintService;
import org.pragmatica.aether.deployment.cluster.PublishedBlueprint;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.blueprint.Blueprint;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.http.routing.RequestContext;
import org.pragmatica.http.routing.Route;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #834: the route body gained `force`, default false. The CLI sends `{"artifact":"..."}` without it unless `--force` is given, so
/// an absent field must read as false (the guard stays on), and the field must reach `BlueprintService.publishFromArtifact` on BOTH
/// entry points: `/blueprints/deploy` and `/blueprints/publish`. The CLI half is `BlueprintForceOptionTest`.
class BlueprintForceFieldTest {
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();
    private static final String COORDS = "org.example:orders-app:1.0.0:blueprint";

    @Test
    void absentForceField_readsFalse_soTheGuardStaysOn() {
        var parsed = MAPPER.readString("{\"artifact\":\"" + COORDS + "\"}", SliceRoutes.BlueprintDeployRequest.class);

        assertThat(parsed.unwrap().force()).isFalse();
    }

    @Test
    void forceField_theCliSends_isRead() {
        var parsed = MAPPER.readString("{\"artifact\":\"" + COORDS + "\",\"force\":true}", SliceRoutes.BlueprintDeployRequest.class);

        assertThat(parsed.unwrap().force()).isTrue();
        assertThat(parsed.unwrap().artifact()).isEqualTo(COORDS);
    }

    @Test
    void deployRoute_passesForceToTheService() {
        assertThat(forceSeenBy(ManagementRoute.BLUEPRINT_DEPLOY, true)).containsExactly(false, true);
    }

    @Test
    void publishRoute_passesForceToTheService() {
        assertThat(forceSeenBy(ManagementRoute.BLUEPRINT_PUBLISH_ARTIFACT, true)).containsExactly(true, true);
    }

    @Test
    void routes_passNoForce_whenTheBodyHasNone() {
        assertThat(forceSeenBy(ManagementRoute.BLUEPRINT_DEPLOY, false)).containsExactly(false, false);
        assertThat(forceSeenBy(ManagementRoute.BLUEPRINT_PUBLISH_ARTIFACT, false)).containsExactly(true, false);
    }

    /// Runs the real handler over a service that records `(registerOnly, force)` and refuses, and returns what it saw.
    private static List<Boolean> forceSeenBy(ManagementRoute route, boolean force) {
        var seen = new AtomicReference<List<Boolean>>(List.of());

        routeOf(route, seen).handler()
                            .handle(requestContext(force))
                            .await();

        return seen.get();
    }

    private static Route<?> routeOf(ManagementRoute route, AtomicReference<List<Boolean>> seen) {
        return SliceRoutes.sliceRoutes(() -> node(seen))
                          .routes()
                          .filter(candidate -> candidate.name()
                                                        .equals(route.name()))
                          .findFirst()
                          .orElseThrow();
    }

    private static ManageableNode node(AtomicReference<List<Boolean>> seen) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> "blueprintService".equals(method.getName())
                                                                         ? recordingService(seen)
                                                                         : unsupported(method.getName()));
    }

    private static Object unsupported(String methodName) {
        throw new UnsupportedOperationException("Not implemented in test proxy: " + methodName);
    }

    private static BlueprintService recordingService(AtomicReference<List<Boolean>> seen) {
        return new BlueprintService() {
            @Override public Promise<PublishedBlueprint> publish(String dsl) {
                return Causes.cause("unused").promise();
            }

            @Override public Promise<PublishedBlueprint> publishFromArtifact(String artifactCoords) {
                return Causes.cause("the route must use the three-argument form").promise();
            }

            @Override public Promise<PublishedBlueprint> publishFromArtifact(String artifactCoords, boolean registerOnly) {
                return Causes.cause("the route must use the three-argument form").promise();
            }

            @Override
            public Promise<PublishedBlueprint> publishFromArtifact(String artifactCoords, boolean registerOnly, boolean force) {
                seen.set(List.of(registerOnly, force));

                return Causes.cause("refused after recording").promise();
            }

            @Override public Option<ExpandedBlueprint> get(BlueprintId id) {
                return Option.none();
            }

            @Override public Option<DeploymentOutcomeValue> attributedOutcome(BlueprintId id) {
                return Option.none();
            }

            @Override public Option<DeploymentOutcomeValue> outcome(BlueprintId id) {
                return Option.none();
            }

            @Override public List<ExpandedBlueprint> list() {
                return List.of();
            }

            @Override public Promise<Unit> delete(BlueprintId id) {
                return Promise.unitPromise();
            }

            @Override public Result<Blueprint> validate(String dsl) {
                return Causes.cause("unused").result();
            }
        };
    }

    private static RequestContext requestContext(boolean force) {
        return (RequestContext) Proxy.newProxyInstance(RequestContext.class.getClassLoader(),
                                                       new Class[]{RequestContext.class},
                                                       (proxy, method, args) -> "fromJson".equals(method.getName())
                                                                                 ? Result.success(new SliceRoutes.BlueprintDeployRequest(COORDS, force))
                                                                                 : method.isDefault()
                                                                                   ? InvocationHandler.invokeDefault(proxy, method, args)
                                                                                   : unsupported(method.getName()));
    }
}
