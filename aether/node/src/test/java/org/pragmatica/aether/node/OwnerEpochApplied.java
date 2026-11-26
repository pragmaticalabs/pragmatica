// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.pragmatica.aether.api.ManagementApiResponses.ComponentHealth;
import org.pragmatica.aether.api.routes.StatusRoutes;

import java.time.Duration;

import static org.pragmatica.testing.PollUntil.pollUntil;

/// #3720: waits, on a booted single node, for the committed DHT ownership record to be applied in the node's own store.
///
/// Node state ACTIVE does not imply it. A lone node is bootstrapped by [LoneNodeGenesis], and the core ownership record is
/// committed only AFTER the genesis incarnation is delivered (`ClusterBootstrapAssembly.onIncarnationCommit` re-drives
/// `BootstrapModule`), through consensus, on its own thread. Until the record is applied the node stamps its DHT puts with the
/// ZERO owner epoch, and a replica that has applied the record refuses them ("1 refused by owner-epoch fences"). A test that
/// writes to storage right after ACTIVE therefore races the apply.
///
/// The predicate is the production one: `/health/ready`'s `owner-epoch` component (#2535), read through the node's real
/// [StatusRoutes], so the tests wait for exactly what readiness waits for and the condition has one definition.
public sealed interface OwnerEpochApplied {
    long BOUND_MS = 60_000;
    String COMPONENT = "owner-epoch";

    /// Blocks until the `owner-epoch` readiness component is UP (bounded by [#BOUND_MS]), failing with the wait's own report, which names the component's
    /// first and final state.
    static void await(AetherNode node) {
        pollUntil(() -> component(routes(node)), OwnerEpochApplied::isUp, Duration.ofMillis(BOUND_MS)).assertMet("the node applied its DHT ownership record ("
                                                                                                                  + COMPONENT
                                                                                                                  + " readiness component UP)");
    }

    private static StatusRoutes routes(AetherNode node) {
        return StatusRoutes.statusRoutes(() -> node, node::appHttpServer);
    }

    private static boolean isUp(ComponentHealth component) {
        return "UP".equals(component.status());
    }

    private static ComponentHealth component(StatusRoutes routes) {
        return routes.buildReadinessResponse()
                     .components()
                     .stream()
                     .filter(component -> COMPONENT.equals(component.name()))
                     .findFirst()
                     .orElseThrow(() -> new AssertionError("/health/ready must carry the " + COMPONENT + " component"));
    }

    record unused() implements OwnerEpochApplied {}
}
