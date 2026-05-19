// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.view;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.view.MembershipView.MemberStatus;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.swim.HealthSnapshot;
import org.pragmatica.swim.SwimHealth;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;


/// Pure-function tests for the H.1 derived membership view.
///
/// Each test seeds a SWIM snapshot and a KV map, then asserts the derived view matches the
/// rule table in `MembershipView`'s class doc. No FSM, no consensus, no scheduler.
class MembershipViewTest {
    private static final NodeId NODE_1 = NodeId.nodeId("node-1").unwrap();
    private static final NodeId NODE_2 = NodeId.nodeId("node-2").unwrap();
    private static final NodeId NODE_3 = NodeId.nodeId("node-3").unwrap();
    private static final long T0 = 100_000L;

    @Nested @DisplayName("SWIM-only inputs (no KV entries)")
    class SwimOnly {
        @Test void healthySwimNoKv_isOnDuty() {
            var view = viewFrom(Map.of(NODE_1, SwimHealth.HEALTHY), Map.of());

            assertThat(view.statusOf(NODE_1)).isEqualTo(MemberStatus.ON_DUTY);
            assertThat(view.onDutyPeers()).containsExactly(NODE_1);
        }

        @Test void faultySwimNoKv_isAbsent() {
            var view = viewFrom(Map.of(NODE_1, SwimHealth.FAULTY), Map.of());

            assertThat(view.statusOf(NODE_1)).isEqualTo(MemberStatus.UNTRACKED);
            assertThat(view.onDutyPeers()).isEmpty();
            assertThat(view.snapshot().get(NODE_1).status()).isEqualTo(MemberStatus.UNTRACKED);
        }

        @Test void unknownSwimNoKv_isAbsent() {
            var view = viewFrom(Map.of(NODE_1, SwimHealth.UNKNOWN), Map.of());

            assertThat(view.statusOf(NODE_1)).isEqualTo(MemberStatus.UNTRACKED);
            assertThat(view.onDutyPeers()).isEmpty();
        }

        @Test void emptySwimEmptyKv_isEmptyView() {
            var view = viewFrom(Map.of(), Map.of());

            assertThat(view.snapshot()).isEmpty();
            assertThat(view.onDutyPeers()).isEmpty();
        }
    }

    @Nested @DisplayName("KV override wins for operator-declared states")
    class KvOverrideWins {
        @Test void healthySwimWithDraining_emitsDraining() {
            var view = viewFrom(Map.of(NODE_1, SwimHealth.HEALTHY),
                                 Map.of(NODE_1, NodeLifecycleState.DRAINING));

            assertThat(view.statusOf(NODE_1)).isEqualTo(MemberStatus.DRAINING);
            assertThat(view.onDutyPeers()).isEmpty();
        }

        @Test void healthySwimWithDecommissioned_emitsDecommissioned() {
            var view = viewFrom(Map.of(NODE_1, SwimHealth.HEALTHY),
                                 Map.of(NODE_1, NodeLifecycleState.DECOMMISSIONED));

            assertThat(view.statusOf(NODE_1)).isEqualTo(MemberStatus.DECOMMISSIONED);
            assertThat(view.onDutyPeers()).isEmpty();
        }

        @Test void healthySwimWithFailedDrain_emitsFailedDrain() {
            var view = viewFrom(Map.of(NODE_1, SwimHealth.HEALTHY),
                                 Map.of(NODE_1, NodeLifecycleState.FAILED_DRAIN));

            assertThat(view.statusOf(NODE_1)).isEqualTo(MemberStatus.FAILED_DRAIN);
        }

        @Test void healthySwimWithJoining_emitsJoining() {
            var view = viewFrom(Map.of(NODE_1, SwimHealth.HEALTHY),
                                 Map.of(NODE_1, NodeLifecycleState.JOINING));

            assertThat(view.statusOf(NODE_1)).isEqualTo(MemberStatus.JOINING);
        }

        @Test void shuttingDownLegacyAlias_emitsDraining() {
            var view = viewFrom(Map.of(NODE_1, SwimHealth.HEALTHY),
                                 Map.of(NODE_1, NodeLifecycleState.SHUTTING_DOWN));

            assertThat(view.statusOf(NODE_1)).isEqualTo(MemberStatus.DRAINING);
        }
    }

    @Nested @DisplayName("Legacy ON_DUTY KV entries are honoured when snapshot has not voted negative")
    class LegacyOnDutyHandling {
        @Test void onDutyKvWithHealthySwim_emitsOnDuty() {
            var view = viewFrom(Map.of(NODE_1, SwimHealth.HEALTHY),
                                 Map.of(NODE_1, NodeLifecycleState.ON_DUTY));

            assertThat(view.statusOf(NODE_1)).isEqualTo(MemberStatus.ON_DUTY);
            assertThat(view.onDutyPeers()).containsExactly(NODE_1);
        }

        @Test void onDutyKvWithFaultySwim_isOnDuty() {
            // Cold-boot fix: KV says ON_DUTY, SWIM lags behind reporting FAULTY, and the
            // aggregated reachability snapshot has not produced an explicit UNREACHABLE
            // quorum (the `membershipView` factory hard-wires snapshot to `Option::none`,
            // simulating cold-boot before the first aggregator emission). The KV entry is
            // authoritative — absence of cluster-canonical negative evidence MUST NOT
            // downgrade an ON_DUTY peer. The legacy "SWIM is truth for alive" assertion
            // was the pre-fix behavior that stranded cold-boot clusters in UNTRACKED for
            // the duration of convergence.
            var view = viewFrom(Map.of(NODE_1, SwimHealth.FAULTY),
                                 Map.of(NODE_1, NodeLifecycleState.ON_DUTY));

            assertThat(view.statusOf(NODE_1)).isEqualTo(MemberStatus.ON_DUTY);
            assertThat(view.onDutyPeers()).containsExactly(NODE_1);
        }

        @Test void onDutyKvWithUnknownSwim_isOnDuty() {
            // Same cold-boot semantic as the faulty-SWIM case: snapshot=Option.none() is
            // non-information, so the KV-authoritative ON_DUTY survives.
            var view = viewFrom(Map.of(NODE_1, SwimHealth.UNKNOWN),
                                 Map.of(NODE_1, NodeLifecycleState.ON_DUTY));

            assertThat(view.statusOf(NODE_1)).isEqualTo(MemberStatus.ON_DUTY);
        }
    }

    @Nested @DisplayName("Mixed cluster snapshot")
    class MixedCluster {
        @Test void fivePeersAcrossStates_emitsCorrectMix() {
            // Realistic chaos-recovery snapshot under the cold-boot semantic
            // (snapshot=Option::none() via the membershipView factory):
            //   node-1: alive in SWIM, no KV → ON_DUTY (post-takeover discovery without write)
            //   node-2: alive in SWIM, KV DRAINING → DRAINING
            //   node-3: faulty in SWIM, KV ON_DUTY (legacy stale) → ON_DUTY (no cluster-canonical
            //          UNREACHABLE quorum; KV is authoritative until snapshot votes negative)
            //   node-4: faulty in SWIM, KV DECOMMISSIONED → DECOMMISSIONED
            //   (replacement): alive in SWIM, no KV → ON_DUTY
            var swim = new LinkedHashMap<NodeId, SwimHealth>();
            swim.put(NODE_1, SwimHealth.HEALTHY);
            swim.put(NODE_2, SwimHealth.HEALTHY);
            swim.put(NODE_3, SwimHealth.FAULTY);
            var replacement = NodeId.nodeId("aether-default-core-node-0-abc123").unwrap();
            swim.put(replacement, SwimHealth.HEALTHY);

            var kv = new LinkedHashMap<NodeId, NodeLifecycleState>();
            kv.put(NODE_2, NodeLifecycleState.DRAINING);
            kv.put(NODE_3, NodeLifecycleState.ON_DUTY);

            var view = viewFrom(swim, kv);

            assertThat(view.statusOf(NODE_1)).isEqualTo(MemberStatus.ON_DUTY);
            assertThat(view.statusOf(NODE_2)).isEqualTo(MemberStatus.DRAINING);
            assertThat(view.statusOf(NODE_3)).isEqualTo(MemberStatus.ON_DUTY);
            assertThat(view.statusOf(replacement)).isEqualTo(MemberStatus.ON_DUTY);
            assertThat(view.onDutyPeers()).containsExactlyInAnyOrder(NODE_1, NODE_3, replacement);
        }
    }

    @Nested @DisplayName("`get(peer)` single-peer convenience")
    class SinglePeerLookup {
        @Test void absentPeer_returnsNone() {
            var view = viewFrom(Map.of(), Map.of());

            assertThat(view.get(NODE_1).isPresent()).isFalse();
        }

        @Test void healthyPeer_returnsSomeOnDuty() {
            var view = viewFrom(Map.of(NODE_1, SwimHealth.HEALTHY), Map.of());

            var entry = view.get(NODE_1);
            assertThat(entry.isPresent()).isTrue();
            assertThat(entry.unwrap().status()).isEqualTo(MemberStatus.ON_DUTY);
            assertThat(entry.unwrap().swimHealth()).isEqualTo(SwimHealth.HEALTHY);
            assertThat(entry.unwrap().lifecycle().isPresent()).isFalse();
        }

        @Test void decommissionedPeer_returnsKvBackedView() {
            var view = viewFrom(Map.of(NODE_1, SwimHealth.HEALTHY),
                                 Map.of(NODE_1, NodeLifecycleState.DECOMMISSIONED));

            var entry = view.get(NODE_1).unwrap();
            assertThat(entry.status()).isEqualTo(MemberStatus.DECOMMISSIONED);
            assertThat(entry.lifecycle().isPresent()).isTrue();
        }
    }

    private static MembershipView viewFrom(Map<NodeId, SwimHealth> swim,
                                            Map<NodeId, NodeLifecycleState> kv) {
        var snapshot = HealthSnapshot.healthSnapshot(swim);
        var lifecycleEntries = new HashMap<NodeLifecycleKey, NodeLifecycleValue>();
        kv.forEach((peer, state) -> lifecycleEntries.put(NodeLifecycleKey.nodeLifecycleKey(peer),
                                                          NodeLifecycleValue.nodeLifecycleValue(state, T0)));
        return MembershipView.membershipView(() -> Option.some(snapshot),
                                              consumer -> applyEntries(lifecycleEntries, consumer));
    }

    private static void applyEntries(Map<NodeLifecycleKey, NodeLifecycleValue> entries,
                                      BiConsumer<NodeLifecycleKey, NodeLifecycleValue> consumer) {
        entries.forEach(consumer);
    }
}
