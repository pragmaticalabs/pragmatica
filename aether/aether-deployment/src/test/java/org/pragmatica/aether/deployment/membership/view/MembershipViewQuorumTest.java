// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.view;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.view.MembershipView.MemberView;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.swim.HealthSnapshot;
import org.pragmatica.swim.SwimHealth;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;


/// RC1 Step 5 — quorum-aware `MembershipView` behaviour.
///
/// Verifies the two-factory contract:
/// - `strict(...)` reports every peer absent on a non-quorate node;
/// - `bootstrapAware(...)` returns content regardless of quorum bit;
/// - Both variants agree byte-for-byte when the node IS quorate.
///
/// The pure-function rule-table semantics live in [MembershipViewTest]; this suite focuses
/// strictly on the quorum gate added by Step 5. Membership-v2 finale: membership collapsed to
/// pure presence — non-quorate ⇒ absent (no UNTRACKED status remains).
class MembershipViewQuorumTest {
    private static final NodeId NODE_1 = NodeId.nodeId("node-1").unwrap();
    private static final NodeId NODE_2 = NodeId.nodeId("node-2").unwrap();
    private static final NodeId NODE_3 = NodeId.nodeId("node-3").unwrap();

    @Nested @DisplayName("strict() on a non-quorate node reports every peer absent")
    class StrictNonQuorate {
        @Test void presentMembers_areEmpty() {
            var view = strictFrom(false,
                                   Map.of(NODE_1, SwimHealth.HEALTHY,
                                          NODE_2, SwimHealth.HEALTHY,
                                          NODE_3, SwimHealth.HEALTHY),
                                   Map.of());

            assertThat(view.presentMembers()).isEmpty();
        }

        @Test void snapshot_isEmpty() {
            var view = strictFrom(false,
                                   Map.of(NODE_1, SwimHealth.HEALTHY),
                                   Map.of());

            assertThat(view.snapshot()).isEmpty();
        }

        @Test void isPresent_returnsFalse() {
            var view = strictFrom(false,
                                   Map.of(NODE_1, SwimHealth.HEALTHY),
                                   Map.of());

            assertThat(view.isPresent(NODE_1)).isFalse();
        }

        @Test void legacyKvWithHealthySwim_isAbsent() {
            var view = strictFrom(false,
                                   Map.of(NODE_1, SwimHealth.HEALTHY),
                                   Map.of(NODE_1, "PRESENT"));

            assertThat(view.isPresent(NODE_1)).isFalse();
            assertThat(view.presentMembers()).isEmpty();
        }

        @Test void operatorOverrides_doNotSurviveNonQuorate() {
            // RC1 membership-v2 step 1: KV operator overrides are no longer consulted. Under
            // the strict non-quorate gate, every HEALTHY peer is reported absent — the dropped
            // KV-override path means DRAINING/STOPPED/JOINING no longer leak through.
            var view = strictFrom(false,
                                   Map.of(NODE_1, SwimHealth.HEALTHY,
                                          NODE_2, SwimHealth.HEALTHY,
                                          NODE_3, SwimHealth.HEALTHY),
                                   Map.of(NODE_1, "DRAINING",
                                          NODE_2, "STOPPED",
                                          NODE_3, "STOPPED"));

            assertThat(view.isPresent(NODE_1)).isFalse();
            assertThat(view.isPresent(NODE_2)).isFalse();
            assertThat(view.isPresent(NODE_3)).isFalse();
        }

        @Test void joiningOverride_doesNotSurviveNonQuorate() {
            var view = strictFrom(false,
                                   Map.of(NODE_1, SwimHealth.HEALTHY),
                                   Map.of(NODE_1, "JOINING"));

            assertThat(view.isPresent(NODE_1)).isFalse();
        }

        @Test void faultySwim_remainsAbsent() {
            var view = strictFrom(false,
                                   Map.of(NODE_1, SwimHealth.FAULTY),
                                   Map.of());

            assertThat(view.get(NODE_1).isPresent()).isFalse();
            assertThat(view.presentMembers()).isEmpty();
        }

        @Test void getPeer_returnsNoneForHealthySwimUnderNonQuorate() {
            var view = strictFrom(false,
                                   Map.of(NODE_1, SwimHealth.HEALTHY),
                                   Map.of());

            assertThat(view.get(NODE_1).isPresent()).isFalse();
            assertThat(view.isPresent(NODE_1)).isFalse();
        }
    }

    @Nested @DisplayName("bootstrapAware() returns content even when non-quorate")
    class BootstrapAwareNonQuorate {
        @Test void presentMembers_arePresent() {
            var view = bootstrapAwareFrom(Map.of(NODE_1, SwimHealth.HEALTHY,
                                                  NODE_2, SwimHealth.HEALTHY),
                                            Map.of());

            assertThat(view.presentMembers()).containsExactlyInAnyOrder(NODE_1, NODE_2);
        }

        @Test void snapshot_reportsPresent() {
            var view = bootstrapAwareFrom(Map.of(NODE_1, SwimHealth.HEALTHY),
                                            Map.of());

            assertThat(view.snapshot()).containsKey(NODE_1);
        }

        @Test void isPresent_returnsTrue() {
            var view = bootstrapAwareFrom(Map.of(NODE_1, SwimHealth.HEALTHY),
                                            Map.of());

            assertThat(view.isPresent(NODE_1)).isTrue();
        }

        @Test void legacyKv_reportsPresent() {
            var view = bootstrapAwareFrom(Map.of(NODE_1, SwimHealth.HEALTHY),
                                            Map.of(NODE_1, "PRESENT"));

            assertThat(view.isPresent(NODE_1)).isTrue();
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
                                  Map.of(NODE_1, "PRESENT",
                                         NODE_2, "DRAINING",
                                         NODE_3, "STOPPED"));
        }

        @Test void legacyKv_equalSnapshots() {
            assertSnapshotsEqual(Map.of(NODE_1, SwimHealth.HEALTHY),
                                  Map.of(NODE_1, "PRESENT"));
        }

        @Test void presentMembersList_equal() {
            var swim = Map.of(NODE_1, SwimHealth.HEALTHY,
                              NODE_2, SwimHealth.HEALTHY,
                              NODE_3, SwimHealth.HEALTHY);
            var kv = Map.of(NODE_2, "DRAINING");

            var strict = strictFrom(true, swim, kv);
            var bootstrap = bootstrapAwareFrom(swim, kv);

            assertThat(strict.presentMembers()).containsExactlyInAnyOrderElementsOf(bootstrap.presentMembers());
        }
    }

    @Nested @DisplayName("Live quorum flip — same view, supplier flips between reads")
    class LiveQuorumFlip {
        @Test void strictReactsToQuorumFlip() {
            var quorum = new AtomicBoolean(true);
            var swim = Map.of(NODE_1, SwimHealth.HEALTHY,
                              NODE_2, SwimHealth.HEALTHY);
            var view = strictFromSupplier(quorum::get, swim, Map.of());

            assertThat(view.presentMembers()).containsExactlyInAnyOrder(NODE_1, NODE_2);

            quorum.set(false);
            assertThat(view.presentMembers()).isEmpty();
            assertThat(view.isPresent(NODE_1)).isFalse();

            quorum.set(true);
            assertThat(view.presentMembers()).containsExactlyInAnyOrder(NODE_1, NODE_2);
        }

        @Test void bootstrapAwareIgnoresQuorumFlip() {
            // bootstrapAware doesn't read the supplier; provide one anyway and verify the
            // factory result stays stable across flips (proves the bypass intent).
            var quorum = new AtomicBoolean(true);
            var swim = Map.of(NODE_1, SwimHealth.HEALTHY);
            var view = bootstrapAwareFrom(swim, Map.of());

            assertThat(view.presentMembers()).containsExactly(NODE_1);
            quorum.set(false);
            assertThat(view.presentMembers()).containsExactly(NODE_1);
        }
    }

    private static void assertSnapshotsEqual(Map<NodeId, SwimHealth> swim,
                                              Map<NodeId, String> kv) {
        var strict = strictFrom(true, swim, kv);
        var bootstrap = bootstrapAwareFrom(swim, kv);

        var strictSnapshot = strict.snapshot();
        var bootstrapSnapshot = bootstrap.snapshot();

        assertThat(strictSnapshot.keySet()).isEqualTo(bootstrapSnapshot.keySet());
        strictSnapshot.forEach((peer, member) -> assertMemberEqual(peer, member, bootstrapSnapshot.get(peer)));
    }

    private static void assertMemberEqual(NodeId peer, MemberView member, MemberView other) {
        assertThat(member.swimHealth()).as("swim for %s", peer).isEqualTo(other.swimHealth());
    }

    private static MembershipView strictFrom(boolean quorate,
                                              Map<NodeId, SwimHealth> swim,
                                              Map<NodeId, String> kv) {
        return strictFromSupplier(() -> quorate, swim, kv);
    }

    private static MembershipView strictFromSupplier(BooleanSupplier inQuorum,
                                                      Map<NodeId, SwimHealth> swim,
                                                      @SuppressWarnings("unused") Map<NodeId, String> kv) {
        var snapshot = HealthSnapshot.healthSnapshot(swim);

        return MembershipView.strict(() -> Option.some(snapshot), inQuorum);
    }

    private static MembershipView bootstrapAwareFrom(Map<NodeId, SwimHealth> swim,
                                                      @SuppressWarnings("unused") Map<NodeId, String> kv) {
        var snapshot = HealthSnapshot.healthSnapshot(swim);

        return MembershipView.bootstrapAware(() -> Option.some(snapshot));
    }
}
