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


/// Pure-function tests for the membership view.
///
/// **RC1 membership-v2 step 1.** The KV `NodeLifecycleKey`-override path is dropped — the
/// view is derived purely from SWIM (+ aggregated reachability as a promoter). The KV map
/// passed to `viewFrom` is now ignored by the view (the factory still accepts the reader for
/// source compatibility), so these tests assert the SWIM-only contract: HEALTHY ⇒ ON_DUTY
/// (when quorate / not snapshot-downgraded), every other SWIM observation ⇒ UNTRACKED, and
/// `lifecycle()` is always absent. No FSM, no consensus, no scheduler.
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

    @Nested @DisplayName("KV override is ignored — SWIM is authoritative (v2 end state)")
    class KvOverrideIgnored {
        @Test void healthySwimWithDraining_isOnDuty() {
            var view = viewFrom(Map.of(NODE_1, SwimHealth.HEALTHY),
                                 Map.of(NODE_1, NodeLifecycleState.DRAINING));

            assertThat(view.statusOf(NODE_1)).isEqualTo(MemberStatus.ON_DUTY);
            assertThat(view.onDutyPeers()).containsExactly(NODE_1);
        }

        @Test void healthySwimWithStopped_isOnDuty() {
            var view = viewFrom(Map.of(NODE_1, SwimHealth.HEALTHY),
                                 Map.of(NODE_1, NodeLifecycleState.STOPPED));

            assertThat(view.statusOf(NODE_1)).isEqualTo(MemberStatus.ON_DUTY);
            assertThat(view.onDutyPeers()).containsExactly(NODE_1);
        }

        @Test void faultySwimWithStopped_isUntracked() {
            // SWIM not HEALTHY ⇒ UNTRACKED regardless of any KV state.
            var view = viewFrom(Map.of(NODE_1, SwimHealth.FAULTY),
                                 Map.of(NODE_1, NodeLifecycleState.STOPPED));

            assertThat(view.statusOf(NODE_1)).isEqualTo(MemberStatus.UNTRACKED);
        }

        @Test void healthySwimWithJoining_isOnDuty() {
            var view = viewFrom(Map.of(NODE_1, SwimHealth.HEALTHY),
                                 Map.of(NODE_1, NodeLifecycleState.JOINING));

            assertThat(view.statusOf(NODE_1)).isEqualTo(MemberStatus.ON_DUTY);
        }
    }

    @Nested @DisplayName("ON_DUTY follows live SWIM, not a stale KV entry")
    class OnDutyFollowsSwim {
        @Test void onDutyKvWithHealthySwim_emitsOnDuty() {
            var view = viewFrom(Map.of(NODE_1, SwimHealth.HEALTHY),
                                 Map.of(NODE_1, NodeLifecycleState.ON_DUTY));

            assertThat(view.statusOf(NODE_1)).isEqualTo(MemberStatus.ON_DUTY);
            assertThat(view.onDutyPeers()).containsExactly(NODE_1);
        }

        @Test void onDutyKvWithFaultySwim_isUntracked() {
            // v2: KV is no longer consulted — a SWIM-faulty peer is UNTRACKED even if a stale
            // KV ON_DUTY entry exists. SWIM is the single source of "alive".
            var view = viewFrom(Map.of(NODE_1, SwimHealth.FAULTY),
                                 Map.of(NODE_1, NodeLifecycleState.ON_DUTY));

            assertThat(view.statusOf(NODE_1)).isEqualTo(MemberStatus.UNTRACKED);
            assertThat(view.onDutyPeers()).isEmpty();
        }

        @Test void onDutyKvWithUnknownSwim_isUntracked() {
            var view = viewFrom(Map.of(NODE_1, SwimHealth.UNKNOWN),
                                 Map.of(NODE_1, NodeLifecycleState.ON_DUTY));

            assertThat(view.statusOf(NODE_1)).isEqualTo(MemberStatus.UNTRACKED);
        }
    }

    @Nested @DisplayName("Mixed cluster snapshot")
    class MixedCluster {
        @Test void fivePeersAcrossStates_emitsCorrectMix() {
            // v2 SWIM-only derivation (KV input ignored):
            //   node-1: HEALTHY → ON_DUTY
            //   node-2: HEALTHY (KV DRAINING ignored) → ON_DUTY
            //   node-3: FAULTY (KV ON_DUTY ignored) → UNTRACKED
            //   replacement: HEALTHY → ON_DUTY
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
            assertThat(view.statusOf(NODE_2)).isEqualTo(MemberStatus.ON_DUTY);
            assertThat(view.statusOf(NODE_3)).isEqualTo(MemberStatus.UNTRACKED);
            assertThat(view.statusOf(replacement)).isEqualTo(MemberStatus.ON_DUTY);
            assertThat(view.onDutyPeers()).containsExactlyInAnyOrder(NODE_1, NODE_2, replacement);
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

        @Test void stoppedKvHealthySwim_returnsSwimDerivedView() {
            // v2: KV STOPPED is ignored; HEALTHY SWIM ⇒ ON_DUTY with no lifecycle attached.
            var view = viewFrom(Map.of(NODE_1, SwimHealth.HEALTHY),
                                 Map.of(NODE_1, NodeLifecycleState.STOPPED));

            var entry = view.get(NODE_1).unwrap();
            assertThat(entry.status()).isEqualTo(MemberStatus.ON_DUTY);
            assertThat(entry.lifecycle().isPresent()).isFalse();
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
