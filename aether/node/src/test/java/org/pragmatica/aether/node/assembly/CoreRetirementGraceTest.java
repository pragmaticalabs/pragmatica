// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.node.assembly;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;

/// #1055: the voter-excluded core retirement drains as `OVERPROVISION_SCALE_DOWN`, a surplus trim, so it obeys the drain-safety
/// grace like the reconciler's surplus drain: a core younger than the grace is never a victim, a mature one still is, and a
/// young one is offered again on the next pass (the existing 1 s cadence) once it matures.
class CoreRetirementGraceTest {
    private static final NodeId YOUNG = new NodeId("young-excluded-core");
    private static final NodeId MATURE = new NodeId("mature-excluded-core");
    private static final NodeId HOSTING = new NodeId("mature-hosting-core");

    private static final Predicate<NodeId> HOSTS_NOTHING = node -> !node.equals(HOSTING);

    @Test
    void retirementVictims_youngExcludedCore_isNotAVictim() {
        Predicate<NodeId> pastGrace = node -> !node.equals(YOUNG);

        assertThat(TopologyAssembly.retirementVictims(Set.of(YOUNG), HOSTS_NOTHING, pastGrace))
            .as("a young, unpaired, unprotected excluded core must not be drained as surplus").isEmpty();
    }

    @Test
    void retirementVictims_matureExcludedCore_isStillAVictim() {
        Predicate<NodeId> pastGrace = node -> !node.equals(YOUNG);

        assertThat(TopologyAssembly.retirementVictims(Set.of(YOUNG, MATURE), HOSTS_NOTHING, pastGrace))
            .as("only the mature core is retired").containsExactly(MATURE);
    }

    @Test
    void retirementVictims_youngCoreMatures_isOfferedOnTheNextPass() {
        var matured = new AtomicBoolean(false);
        Predicate<NodeId> pastGrace = _ -> matured.get();

        assertThat(TopologyAssembly.retirementVictims(Set.of(YOUNG), HOSTS_NOTHING, pastGrace)).isEmpty();

        matured.set(true);

        assertThat(TopologyAssembly.retirementVictims(Set.of(YOUNG), HOSTS_NOTHING, pastGrace))
            .as("the same excluded set, next pass, past the grace").containsExactly(YOUNG);
    }

    @Test
    void retirementVictims_matureCoreHostingDeployments_isStillNotAVictim() {
        assertThat(TopologyAssembly.retirementVictims(Set.of(HOSTING), HOSTS_NOTHING, _ -> true))
            .as("the deployments guard is unchanged by the age rule").isEmpty();
    }

    @Test
    void pastDrainGrace_reconcilerNotPublished_isFailClosed() {
        assertThat(TopologyAssembly.pastDrainGrace(() -> null).test(MATURE))
            .as("an unpublished reconciler cannot vouch for any age: no core is retired").isFalse();
    }
}
