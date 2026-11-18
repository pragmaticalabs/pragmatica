// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Map;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// #3296 class B: the worker community choice (J5, B-070) reads the `[community.<id>]` placement policies of the COMMITTED cluster
/// config. Before the fix they reached the FSM only through a seam the node wired to an empty map, so every worker of a policy source,
/// a placement-provisioned one included, took the derived `<source>-w-0` community and the placement operation's readiness gate
/// (`CommunityPlacementReconciler.targetReady`, directive community == operation community) never held: the operation stayed
/// AWAITING_READY. The config text is the one HierarchicalCommunityMovementTest commits.
class CommittedPlacementPolicyTest {
    private static final NodeId WORKER = node("placement-worker");
    private static final String POLICY = """
        config_version = "1.0.0"
        [cluster]
        name = "movement"
        version = "1.0.0"
        [source.default]
        type = "forge"
        [source.default.core]
        count = 3
        [source.east]
        type = "forge"
        [source.east.worker]
        count = 0
        [source.west]
        type = "forge"
        [source.west.worker]
        count = 0
        [community.stable]
        target_size = 1
        [community.stable.placement.destination]
        source = "east"
        """;

    private static ClusterConfigValue config(long version) {
        return new ClusterConfigValue(Option.some(POLICY), "movement", "1.0.0", List.of(), 3, 3, "forge", version, 0L);
    }

    private static MbWorld eastWorkerWorld() {
        return MbWorld.world(CORE_1, CORE_2, CORE_3)
                      .seams(seams -> seams.withMemberSource(Map.of(WORKER, "east")));
    }

    private static String communityOf(MbWorld w) {
        return w.h.store.get(new ActivationDirectiveKey(WORKER))
                        .filter(ActivationDirectiveValue.class::isInstance)
                        .map(value -> ((ActivationDirectiveValue) value).communityId())
                        .or("");
    }

    /// Red when: the committed config's community placement policy does not reach the choice, so the worker takes the derived
    /// `east-w-0` community instead of the policy's.
    @Test
    void workerJoin_committedPlacementPolicy_decidesTheCommunity() {
        var w = eastWorkerWorld();

        w.h.seedQuietly(ClusterConfigKey.CURRENT, config(1L));
        w.activate();
        w.h.deliver(new ClusterInput.WorkerJoin(WORKER, w.admit(WORKER, 21L)));

        assertThat(communityOf(w)).isEqualTo("stable");
    }

    /// Red when: a policy committed while the leader is active reaches the choice only through a later sample rather than in commit order:
    /// the join right behind the config commit, with no seam refresh between them, takes the derived community.
    @Test
    void workerJoin_policyCommittedJustBefore_decidesTheCommunityWithoutASeamRefresh() {
        var w = eastWorkerWorld().activate();

        w.h.seed(ClusterConfigKey.CURRENT, config(1L));
        w.h.deliver(new ClusterInput.WorkerJoin(WORKER, w.admit(WORKER, 21L)));

        assertThat(communityOf(w)).isEqualTo("stable");
    }
}
