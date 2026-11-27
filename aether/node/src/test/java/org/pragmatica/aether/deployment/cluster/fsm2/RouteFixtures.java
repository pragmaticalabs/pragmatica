// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeclaredRouteKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeclaredRoute;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeclaredRouteValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.aether.slice.kvstore.RouteState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;

/// The declared-route fixtures shared by the PUBLISH, FAIL and goal-gate tests of #3389 A2 part 2.
final class RouteFixtures {
    static final String APP = "org.example:app:1.0.0";
    static final BlueprintId APP_ID = blueprintId(APP);
    static final DeclaredRoute LIST = DeclaredRoute.declaredRoute("GET", "/orders");
    static final DeclaredRoute GET_ONE = DeclaredRoute.declaredRoute("GET", "/orders/{id}");

    private RouteFixtures() {}

    static DeclaredRouteKey key(DeclaredRoute route) {
        return DeclaredRouteKey.declaredRouteKey(ORDERS,
                                                 route.httpMethod(),
                                                 route.pathTemplate())
                               .unwrap();
    }

    static DeclaredRouteValue row(String attempt, RouteState state) {
        return DeclaredRouteValue.declaredRouteValue(APP, attempt, state);
    }

    static DeclaredRouteValue declared(String attempt) {
        return row(attempt, RouteState.Declared.declared());
    }

    static DeclaredRouteValue published(String attempt) {
        return row(attempt, RouteState.Published.published());
    }

    static DeclaredRouteValue failed(String attempt, String reason) {
        return row(attempt, RouteState.Failed.failed(reason));
    }

    /// The blueprint `BlueprintService` writes for one ORDERS instance declaring `routes`.
    static AppBlueprintValue blueprintOf(String attempt, DeclaredRoute... routes) {
        var expanded = ExpandedBlueprint.expandedBlueprint(APP_ID,
                                                           List.of(slice(ORDERS, 1, 1, Set.of())));

        return AppBlueprintValue.appBlueprintValue(expanded,
                                                   false,
                                                   attempt,
                                                   Map.of(ORDERS, List.of(routes)));
    }

    static Option<AetherValue> committed(FsmHarness h, DeclaredRoute route) {
        return h.store.get(key(route));
    }

    /// A node's route publication for ORDERS, as `HttpRoutePublisher` puts it: a plain Put on the node's key.
    static void publishRoutes(FsmHarness h, NodeId node) {
        h.seed(new NodeRoutesKey(node, ORDERS), new NodeRoutesValue(List.of(), Epoch.ZERO));
        h.drain();
    }
}
