// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.view;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.swim.HealthSnapshot;
import org.pragmatica.swim.SwimHealth;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;


/// Pure-function tests for the membership view.
///
/// **RC1 membership-v2 finale.** Membership collapsed to pure presence — the KV lifecycle path
/// is gone and the synthetic status enum was removed. The view is derived purely from SWIM: a
/// peer is present iff it is HEALTHY in SWIM (when quorate). The KV map passed to `viewFrom` is
/// ignored (the factory still accepts the reader for source compatibility). No FSM, no
/// consensus, no scheduler.
class MembershipViewTest {
    private static final NodeId NODE_1 = NodeId.nodeId("node-1").unwrap();
    private static final NodeId NODE_2 = NodeId.nodeId("node-2").unwrap();
    private static final NodeId NODE_3 = NodeId.nodeId("node-3").unwrap();

    @Nested @DisplayName("SWIM-only inputs (no KV entries)")
    class SwimOnly {
        @Test void healthySwimNoKv_isPresent() {
            var view = viewFrom(Map.of(NODE_1, SwimHealth.HEALTHY), Map.of());

            assertThat(view.isPresent(NODE_1)).isTrue();
            assertThat(view.presentMembers()).containsExactly(NODE_1);
        }

        @Test void faultySwimNoKv_isAbsent() {
            var view = viewFrom(Map.of(NODE_1, SwimHealth.FAULTY), Map.of());

            assertThat(view.isPresent(NODE_1)).isFalse();
            assertThat(view.presentMembers()).isEmpty();
            assertThat(view.snapshot()).doesNotContainKey(NODE_1);
        }

        @Test void unknownSwimNoKv_isAbsent() {
            var view = viewFrom(Map.of(NODE_1, SwimHealth.UNKNOWN), Map.of());

            assertThat(view.isPresent(NODE_1)).isFalse();
            assertThat(view.presentMembers()).isEmpty();
        }

        @Test void emptySwimEmptyKv_isEmptyView() {
            var view = viewFrom(Map.of(), Map.of());

            assertThat(view.snapshot()).isEmpty();
            assertThat(view.presentMembers()).isEmpty();
        }
    }

    @Nested @DisplayName("KV override is ignored — SWIM is authoritative (v2 end state)")
    class KvOverrideIgnored {
        @Test void healthySwimWithDraining_isPresent() {
            var view = viewFrom(Map.of(NODE_1, SwimHealth.HEALTHY),
                                 Map.of(NODE_1, "DRAINING"));

            assertThat(view.isPresent(NODE_1)).isTrue();
            assertThat(view.presentMembers()).containsExactly(NODE_1);
        }

        @Test void healthySwimWithStopped_isPresent() {
            var view = viewFrom(Map.of(NODE_1, SwimHealth.HEALTHY),
                                 Map.of(NODE_1, "STOPPED"));

            assertThat(view.isPresent(NODE_1)).isTrue();
            assertThat(view.presentMembers()).containsExactly(NODE_1);
        }

        @Test void faultySwimWithStopped_isAbsent() {
            // SWIM not HEALTHY ⇒ absent regardless of any KV state.
            var view = viewFrom(Map.of(NODE_1, SwimHealth.FAULTY),
                                 Map.of(NODE_1, "STOPPED"));

            assertThat(view.isPresent(NODE_1)).isFalse();
        }

        @Test void healthySwimWithJoining_isPresent() {
            var view = viewFrom(Map.of(NODE_1, SwimHealth.HEALTHY),
                                 Map.of(NODE_1, "JOINING"));

            assertThat(view.isPresent(NODE_1)).isTrue();
        }
    }

    @Nested @DisplayName("Presence follows live SWIM, not a stale KV entry")
    class PresenceFollowsSwim {
        @Test void presentKvWithHealthySwim_isPresent() {
            var view = viewFrom(Map.of(NODE_1, SwimHealth.HEALTHY),
                                 Map.of(NODE_1, "PRESENT"));

            assertThat(view.isPresent(NODE_1)).isTrue();
            assertThat(view.presentMembers()).containsExactly(NODE_1);
        }

        @Test void presentKvWithFaultySwim_isAbsent() {
            // v2: KV is no longer consulted — a SWIM-faulty peer is absent even if a stale
            // KV entry exists. SWIM is the single source of "alive".
            var view = viewFrom(Map.of(NODE_1, SwimHealth.FAULTY),
                                 Map.of(NODE_1, "PRESENT"));

            assertThat(view.isPresent(NODE_1)).isFalse();
            assertThat(view.presentMembers()).isEmpty();
        }

        @Test void presentKvWithUnknownSwim_isAbsent() {
            var view = viewFrom(Map.of(NODE_1, SwimHealth.UNKNOWN),
                                 Map.of(NODE_1, "PRESENT"));

            assertThat(view.isPresent(NODE_1)).isFalse();
        }
    }

    @Nested @DisplayName("Mixed cluster snapshot")
    class MixedCluster {
        @Test void fivePeersAcrossStates_emitsCorrectMix() {
            // v2 SWIM-only derivation (KV input ignored):
            //   node-1: HEALTHY → present
            //   node-2: HEALTHY (KV DRAINING ignored) → present
            //   node-3: FAULTY → absent
            //   replacement: HEALTHY → present
            var swim = new LinkedHashMap<NodeId, SwimHealth>();
            swim.put(NODE_1, SwimHealth.HEALTHY);
            swim.put(NODE_2, SwimHealth.HEALTHY);
            swim.put(NODE_3, SwimHealth.FAULTY);
            var replacement = NodeId.nodeId("aether-default-core-node-0-abc123").unwrap();
            swim.put(replacement, SwimHealth.HEALTHY);

            var kv = new LinkedHashMap<NodeId, String>();
            kv.put(NODE_2, "DRAINING");

            var view = viewFrom(swim, kv);

            assertThat(view.isPresent(NODE_1)).isTrue();
            assertThat(view.isPresent(NODE_2)).isTrue();
            assertThat(view.isPresent(NODE_3)).isFalse();
            assertThat(view.isPresent(replacement)).isTrue();
            assertThat(view.presentMembers()).containsExactlyInAnyOrder(NODE_1, NODE_2, replacement);
        }
    }

    @Nested @DisplayName("`get(peer)` single-peer convenience")
    class SinglePeerLookup {
        @Test void absentPeer_returnsNone() {
            var view = viewFrom(Map.of(), Map.of());

            assertThat(view.get(NODE_1).isPresent()).isFalse();
        }

        @Test void healthyPeer_returnsSomePresent() {
            var view = viewFrom(Map.of(NODE_1, SwimHealth.HEALTHY), Map.of());

            var entry = view.get(NODE_1);
            assertThat(entry.isPresent()).isTrue();
            assertThat(entry.unwrap().swimHealth()).isEqualTo(SwimHealth.HEALTHY);
        }

        @Test void stoppedKvHealthySwim_returnsSwimDerivedView() {
            // v2: KV STOPPED is ignored; HEALTHY SWIM ⇒ present.
            var view = viewFrom(Map.of(NODE_1, SwimHealth.HEALTHY),
                                 Map.of(NODE_1, "STOPPED"));

            var entry = view.get(NODE_1).unwrap();
            assertThat(entry.swimHealth()).isEqualTo(SwimHealth.HEALTHY);
        }
    }

    private static MembershipView viewFrom(Map<NodeId, SwimHealth> swim,
                                            @SuppressWarnings("unused") Map<NodeId, String> kv) {
        var snapshot = HealthSnapshot.healthSnapshot(swim);

        return MembershipView.membershipView(() -> Option.some(snapshot));
    }
}
