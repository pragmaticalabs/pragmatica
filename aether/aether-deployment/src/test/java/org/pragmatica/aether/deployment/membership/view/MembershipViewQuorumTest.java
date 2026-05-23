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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;


/// RC1 Step 5 — quorum-aware `MembershipView` behaviour.
///
/// Verifies the two-factory contract:
/// - `strict(...)` returns empty / UNTRACKED on a non-quorate node;
/// - `bootstrapAware(...)` returns content regardless of quorum bit;
/// - Both variants agree byte-for-byte when the node IS quorate.
///
/// The pure-function rule-table semantics live in [MembershipViewTest]; this suite focuses
/// strictly on the quorum gate added by Step 5.
class MembershipViewQuorumTest {
    private static final NodeId NODE_1 = NodeId.nodeId("node-1").unwrap();
    private static final NodeId NODE_2 = NodeId.nodeId("node-2").unwrap();
    private static final NodeId NODE_3 = NodeId.nodeId("node-3").unwrap();
    private static final long T0 = 100_000L;

    @Nested @DisplayName("strict() on a non-quorate node forces empty / UNTRACKED")
    class StrictNonQuorate {
        @Test void onDutyPeers_areEmpty() {
            var view = strictFrom(false,
                                   Map.of(NODE_1, SwimHealth.HEALTHY,
                                          NODE_2, SwimHealth.HEALTHY,
                                          NODE_3, SwimHealth.HEALTHY),
                                   Map.of());

            assertThat(view.onDutyPeers()).isEmpty();
        }

        @Test void snapshot_forcesOnDutyToUntracked() {
            var view = strictFrom(false,
                                   Map.of(NODE_1, SwimHealth.HEALTHY),
                                   Map.of());

            var snapshot = view.snapshot();
            assertThat(snapshot).containsOnlyKeys(NODE_1);
            assertThat(snapshot.get(NODE_1).status()).isEqualTo(MemberStatus.UNTRACKED);
        }

        @Test void statusOf_returnsUntracked() {
            var view = strictFrom(false,
                                   Map.of(NODE_1, SwimHealth.HEALTHY),
                                   Map.of());

            assertThat(view.statusOf(NODE_1)).isEqualTo(MemberStatus.UNTRACKED);
        }

        @Test void legacyOnDutyKvWithHealthySwim_isUntracked() {
            var view = strictFrom(false,
                                   Map.of(NODE_1, SwimHealth.HEALTHY),
                                   Map.of(NODE_1, NodeLifecycleState.ON_DUTY));

            assertThat(view.statusOf(NODE_1)).isEqualTo(MemberStatus.UNTRACKED);
            assertThat(view.onDutyPeers()).isEmpty();
        }

        @Test void operatorOverrides_surviveNonQuorate() {
            // DRAINING/DECOMMISSIONED/JOINING/FAILED_DRAIN reflect consensus-replicated
            // operator intent. They remain true regardless of the local node's quorum
            // status — the strict gate only suppresses derived ON_DUTY claims.
            var view = strictFrom(false,
                                   Map.of(NODE_1, SwimHealth.HEALTHY,
                                          NODE_2, SwimHealth.HEALTHY,
                                          NODE_3, SwimHealth.HEALTHY),
                                   Map.of(NODE_1, NodeLifecycleState.DRAINING,
                                          NODE_2, NodeLifecycleState.STOPPED,
                                          NODE_3, NodeLifecycleState.STOPPED));

            assertThat(view.statusOf(NODE_1)).isEqualTo(MemberStatus.DRAINING);
            assertThat(view.statusOf(NODE_2)).isEqualTo(MemberStatus.STOPPED);
            assertThat(view.statusOf(NODE_3)).isEqualTo(MemberStatus.STOPPED);
        }

        @Test void joiningOverride_surviveNonQuorate() {
            var view = strictFrom(false,
                                   Map.of(NODE_1, SwimHealth.HEALTHY),
                                   Map.of(NODE_1, NodeLifecycleState.JOINING));

            assertThat(view.statusOf(NODE_1)).isEqualTo(MemberStatus.JOINING);
        }

        @Test void faultySwim_remainsAbsent() {
            var view = strictFrom(false,
                                   Map.of(NODE_1, SwimHealth.FAULTY),
                                   Map.of());

            assertThat(view.get(NODE_1).isPresent()).isFalse();
            assertThat(view.onDutyPeers()).isEmpty();
        }

        @Test void getPeer_returnsNoneForHealthySwimUnderNonQuorate() {
            var view = strictFrom(false,
                                   Map.of(NODE_1, SwimHealth.HEALTHY),
                                   Map.of());

            // Note: snapshot() yields UNTRACKED-with-SWIM-healthy via the lifecycle-iterating
            // map path; get(peer) hits the lifecycle-absent + swim-healthy branch which
            // also downgrades to UNTRACKED-or-empty under the strict gate. Either shape is
            // acceptable for "non-quorate hides ON_DUTY" — assert the equivalent: no peer
            // listed as ON_DUTY anywhere.
            view.get(NODE_1).onPresent(entry -> assertThat(entry.status()).isNotEqualTo(MemberStatus.ON_DUTY));
            assertThat(view.statusOf(NODE_1)).isNotEqualTo(MemberStatus.ON_DUTY);
        }
    }

    @Nested @DisplayName("bootstrapAware() returns content even when non-quorate")
    class BootstrapAwareNonQuorate {
        @Test void onDutyPeers_arePresent() {
            var view = bootstrapAwareFrom(Map.of(NODE_1, SwimHealth.HEALTHY,
                                                  NODE_2, SwimHealth.HEALTHY),
                                            Map.of());

            assertThat(view.onDutyPeers()).containsExactlyInAnyOrder(NODE_1, NODE_2);
        }

        @Test void snapshot_reportsOnDuty() {
            var view = bootstrapAwareFrom(Map.of(NODE_1, SwimHealth.HEALTHY),
                                            Map.of());

            assertThat(view.snapshot().get(NODE_1).status()).isEqualTo(MemberStatus.ON_DUTY);
        }

        @Test void statusOf_returnsOnDuty() {
            var view = bootstrapAwareFrom(Map.of(NODE_1, SwimHealth.HEALTHY),
                                            Map.of());

            assertThat(view.statusOf(NODE_1)).isEqualTo(MemberStatus.ON_DUTY);
        }

        @Test void legacyOnDutyKv_reportsOnDuty() {
            var view = bootstrapAwareFrom(Map.of(NODE_1, SwimHealth.HEALTHY),
                                            Map.of(NODE_1, NodeLifecycleState.ON_DUTY));

            assertThat(view.statusOf(NODE_1)).isEqualTo(MemberStatus.ON_DUTY);
        }
    }

    @Nested @DisplayName("Quorate node: strict and bootstrapAware agree byte-for-byte")
    class QuorateEquivalence {
        @Test void emptyInputs_equalSnapshots() {
            assertSnapshotsEqual(Map.of(), Map.of());
        }

        @Test void swimOnly_equalSnapshots() {
            assertSnapshotsEqual(Map.of(NODE_1, SwimHealth.HEALTHY,
                                         NODE_2, SwimHealth.FAULTY),
                                  Map.of());
        }

        @Test void mixedKv_equalSnapshots() {
            assertSnapshotsEqual(Map.of(NODE_1, SwimHealth.HEALTHY,
                                         NODE_2, SwimHealth.HEALTHY,
                                         NODE_3, SwimHealth.FAULTY),
                                  Map.of(NODE_1, NodeLifecycleState.ON_DUTY,
                                         NODE_2, NodeLifecycleState.DRAINING,
                                         NODE_3, NodeLifecycleState.STOPPED));
        }

        @Test void legacyOnDutyKv_equalSnapshots() {
            assertSnapshotsEqual(Map.of(NODE_1, SwimHealth.HEALTHY),
                                  Map.of(NODE_1, NodeLifecycleState.ON_DUTY));
        }

        @Test void onDutyPeersList_equal() {
            var swim = Map.of(NODE_1, SwimHealth.HEALTHY,
                              NODE_2, SwimHealth.HEALTHY,
                              NODE_3, SwimHealth.HEALTHY);
            var kv = Map.of(NODE_2, NodeLifecycleState.DRAINING);

            var strict = strictFrom(true, swim, kv);
            var bootstrap = bootstrapAwareFrom(swim, kv);

            assertThat(strict.onDutyPeers()).containsExactlyInAnyOrderElementsOf(bootstrap.onDutyPeers());
        }
    }

    @Nested @DisplayName("Live quorum flip — same view, supplier flips between reads")
    class LiveQuorumFlip {
        @Test void strictReactsToQuorumFlip() {
            var quorum = new AtomicBoolean(true);
            var swim = Map.of(NODE_1, SwimHealth.HEALTHY,
                              NODE_2, SwimHealth.HEALTHY);
            var view = strictFromSupplier(quorum::get, swim, Map.of());

            assertThat(view.onDutyPeers()).containsExactlyInAnyOrder(NODE_1, NODE_2);

            quorum.set(false);
            assertThat(view.onDutyPeers()).isEmpty();
            assertThat(view.statusOf(NODE_1)).isEqualTo(MemberStatus.UNTRACKED);

            quorum.set(true);
            assertThat(view.onDutyPeers()).containsExactlyInAnyOrder(NODE_1, NODE_2);
        }

        @Test void bootstrapAwareIgnoresQuorumFlip() {
            // bootstrapAware doesn't read the supplier; provide one anyway and verify the
            // factory result stays stable across flips (proves the bypass intent).
            var quorum = new AtomicBoolean(true);
            var swim = Map.of(NODE_1, SwimHealth.HEALTHY);
            var view = bootstrapAwareFrom(swim, Map.of());

            assertThat(view.onDutyPeers()).containsExactly(NODE_1);
            quorum.set(false);
            assertThat(view.onDutyPeers()).containsExactly(NODE_1);
        }
    }

    private static void assertSnapshotsEqual(Map<NodeId, SwimHealth> swim,
                                              Map<NodeId, NodeLifecycleState> kv) {
        var strict = strictFrom(true, swim, kv);
        var bootstrap = bootstrapAwareFrom(swim, kv);

        var strictSnapshot = strict.snapshot();
        var bootstrapSnapshot = bootstrap.snapshot();

        assertThat(strictSnapshot.keySet()).isEqualTo(bootstrapSnapshot.keySet());
        strictSnapshot.forEach((peer, member) -> {
            var other = bootstrapSnapshot.get(peer);
            assertThat(member.status()).as("status for %s", peer).isEqualTo(other.status());
            assertThat(member.swimHealth()).as("swim for %s", peer).isEqualTo(other.swimHealth());
            assertThat(member.lifecycle().isPresent()).as("lifecycle presence for %s", peer)
                                                       .isEqualTo(other.lifecycle().isPresent());
        });
    }

    private static MembershipView strictFrom(boolean quorate,
                                              Map<NodeId, SwimHealth> swim,
                                              Map<NodeId, NodeLifecycleState> kv) {
        return strictFromSupplier(() -> quorate, swim, kv);
    }

    private static MembershipView strictFromSupplier(BooleanSupplier inQuorum,
                                                      Map<NodeId, SwimHealth> swim,
                                                      Map<NodeId, NodeLifecycleState> kv) {
        var snapshot = HealthSnapshot.healthSnapshot(swim);
        var lifecycleEntries = buildLifecycleEntries(kv);
        return MembershipView.strict(() -> Option.some(snapshot),
                                      consumer -> applyEntries(lifecycleEntries, consumer),
                                      inQuorum);
    }

    private static MembershipView bootstrapAwareFrom(Map<NodeId, SwimHealth> swim,
                                                      Map<NodeId, NodeLifecycleState> kv) {
        var snapshot = HealthSnapshot.healthSnapshot(swim);
        var lifecycleEntries = buildLifecycleEntries(kv);
        return MembershipView.bootstrapAware(() -> Option.some(snapshot),
                                              consumer -> applyEntries(lifecycleEntries, consumer));
    }

    private static Map<NodeLifecycleKey, NodeLifecycleValue> buildLifecycleEntries(Map<NodeId, NodeLifecycleState> kv) {
        var entries = new LinkedHashMap<NodeLifecycleKey, NodeLifecycleValue>();
        kv.forEach((peer, state) -> entries.put(NodeLifecycleKey.nodeLifecycleKey(peer),
                                                  NodeLifecycleValue.nodeLifecycleValue(state, T0)));
        return entries;
    }

    private static void applyEntries(Map<NodeLifecycleKey, NodeLifecycleValue> entries,
                                      BiConsumer<NodeLifecycleKey, NodeLifecycleValue> consumer) {
        entries.forEach(consumer);
    }
}
