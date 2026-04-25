// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.CoreMember;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.aether.slice.generation.HealthSignal;
import org.pragmatica.aether.slice.generation.OperatorIntent;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;


/// Commit 5 — explicit `start(Epoch)` / `stop(StopReason)` lifecycle and the
/// leader-epoch fence on inbound signals. See
/// `aether/docs/specs/clustersync-refactor-spec.md` commit 5.
class HealthReconcilerLifecycleTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final NodeId NODE_A = NodeId.nodeId("node-a").unwrap();
    private static final NodeId NODE_B = NodeId.nodeId("node-b").unwrap();

    private RecordingClusterNode cluster;
    private AtomicBoolean isLeader;
    private AtomicLong rabiaTerm;
    private HealthReconciler reconciler;

    @BeforeEach
    void setUp() {
        cluster = new RecordingClusterNode();
        var hlcClock = HlcClock.hlcClock(SELF.id()).unwrap();
        isLeader = new AtomicBoolean(true);
        rabiaTerm = new AtomicLong(6L);
        reconciler = HealthReconciler.healthReconciler(SELF,
                                                       cluster,
                                                       ClusterGenerationProjector.clusterGenerationProjector(),
                                                       hlcClock,
                                                       rabiaTerm::get,
                                                       isLeader::get,
                                                       AutoHealConfig.DEFAULT);
    }

    private ClusterGenerationSnapshot seedTwoCoreNodesAtEpoch(Epoch epoch) {
        var template = ClusterGenerationSnapshot.empty(epoch.rabiaTerm());
        var base = ClusterGenerationSnapshot.clusterGenerationSnapshot(epoch,
                                                                        template.committedAt(),
                                                                        template.reason(),
                                                                        2,
                                                                        Map.of(),
                                                                        Map.of(),
                                                                        Map.of(),
                                                                        template.derivedMode(),
                                                                        template.quiescence(),
                                                                        template.quiescenceDetail());
        var members = new LinkedHashMap<NodeId, CoreMember>();
        members.put(NODE_A, CoreMember.coreMember(NODE_A, "h-a", 9001, NodeLifecycleState.ON_DUTY, HealthHint.HEALTHY, epoch, epoch));
        members.put(NODE_B, CoreMember.coreMember(NODE_B, "h-b", 9002, NodeLifecycleState.ON_DUTY, HealthHint.HEALTHY, epoch, epoch));
        var seeded = base.withCoreMembers(members);
        reconciler.seedSnapshot(seeded);
        return seeded;
    }

    @Nested
    class Activation {
        @Test
        void start_with_epoch_is_required_before_signals_flow() {
            seedTwoCoreNodesAtEpoch(Epoch.epoch(6L, 0L));
            // No start(...) call → onSignal must no-op.
            reconciler.onSignal(new HealthSignal.OperatorAction(new OperatorIntent.RemoveMember(NODE_A)));

            assertThat(cluster.applied()).isEmpty();
            assertThat(reconciler.isActive()).isFalse();
        }

        @Test
        void start_makesReconcilerActive() {
            reconciler.start(Epoch.epoch(6L, 0L));

            assertThat(reconciler.isActive()).isTrue();
        }

        @Test
        void stop_with_LEADER_LOST_clears_decision_state() {
            seedTwoCoreNodesAtEpoch(Epoch.epoch(6L, 0L));
            reconciler.start(Epoch.epoch(6L, 0L));
            // Populate internal maps via a ping timeout (counter lives on PeerObservationStore singleton).
            reconciler.onSignal(new HealthSignal.PingTimeout(NODE_A, 1, Epoch.epoch(6L, 0L)));

            reconciler.stop(StopReason.LEADER_LOST);

            assertThat(reconciler.isActive()).isFalse();
            // After stop, subsequent signals must be ignored even if matching the previous epoch.
            reconciler.onSignal(new HealthSignal.OperatorAction(new OperatorIntent.RemoveMember(NODE_A)));
            assertThat(cluster.applied()).isEmpty();
        }

        @Test
        void stop_with_SHUTDOWN_idempotent() {
            reconciler.start(Epoch.epoch(6L, 0L));
            reconciler.stop(StopReason.SHUTDOWN);
            reconciler.stop(StopReason.SHUTDOWN);

            assertThat(reconciler.isActive()).isFalse();
        }

        @Test
        void start_stop_start_clears_leader_projection_state_but_preserves_node_level_counters() {
            seedTwoCoreNodesAtEpoch(Epoch.epoch(6L, 0L));
            reconciler.start(Epoch.epoch(6L, 0L));
            // Accumulate misses under the first leadership.
            reconciler.onSignal(new HealthSignal.PingTimeout(NODE_A, 1, Epoch.epoch(6L, 0L)));
            reconciler.onSignal(new HealthSignal.PingTimeout(NODE_A, 2, Epoch.epoch(6L, 0L)));

            reconciler.stop(StopReason.LEADER_LOST);
            // Re-seed as the new leader and restart.
            rabiaTerm.set(7L);
            seedTwoCoreNodesAtEpoch(Epoch.epoch(7L, 0L));
            reconciler.start(Epoch.epoch(7L, 0L));

            // Q5: ping-miss counters live on the node-singleton PeerObservationStore — they
            // survive leader thrash by design, so a third miss under the new term DOES trip
            // suspect (2 carried + 1 new = 3 ≥ DEFAULT_SUSPECT_THRESHOLD). Leader-projection
            // state (swimHints, pendingRemovals) IS cleared on stop.
            reconciler.onSignal(new HealthSignal.PingTimeout(NODE_A, 1, Epoch.epoch(7L, 0L)));

            assertThat(reconciler.currentSnapshot().coreMembers().get(NODE_A).healthHint()).isEqualTo(HealthHint.SUSPECTED);
        }
    }

    @Nested
    class EpochFence {
        @Test
        void signal_at_stale_term_is_dropped() {
            seedTwoCoreNodesAtEpoch(Epoch.epoch(6L, 0L));
            reconciler.start(Epoch.epoch(6L, 0L));

            // Observer reports under an older term (pre-leader-change). Must be dropped.
            reconciler.onSignal(new HealthSignal.SwimHint(NODE_A, HealthHint.FAULTY, Epoch.epoch(5L, 100L)));

            assertThat(reconciler.currentSnapshot().coreMembers().get(NODE_A).healthHint()).isEqualTo(HealthHint.HEALTHY);
        }

        @Test
        void signal_outside_counter_window_is_dropped() {
            // Seed snapshot with counter already advanced to 10 so we can test the window.
            var seeded = seedTwoCoreNodesAtEpoch(Epoch.epoch(6L, 10L));
            assertThat(seeded.epoch().localCounter()).isEqualTo(10L);
            reconciler.start(Epoch.epoch(6L, 0L));

            // Signal at (6, 7) — 10 - 7 = 3 > LATE_SIGNAL_WINDOW (2) → dropped.
            reconciler.onSignal(new HealthSignal.SwimHint(NODE_A, HealthHint.FAULTY, Epoch.epoch(6L, 7L)));

            assertThat(reconciler.currentSnapshot().coreMembers().get(NODE_A).healthHint()).isEqualTo(HealthHint.HEALTHY);
        }

        @Test
        void signal_inside_counter_window_is_accepted() {
            var seeded = seedTwoCoreNodesAtEpoch(Epoch.epoch(6L, 10L));
            assertThat(seeded.epoch().localCounter()).isEqualTo(10L);
            reconciler.start(Epoch.epoch(6L, 0L));

            // Signal at (6, 8) — 10 - 8 = 2, within window → accepted.
            reconciler.onSignal(new HealthSignal.SwimHint(NODE_A, HealthHint.FAULTY, Epoch.epoch(6L, 8L)));

            assertThat(reconciler.currentSnapshot().coreMembers().get(NODE_A).healthHint()).isEqualTo(HealthHint.SUSPECTED);
        }

        @Test
        void signal_with_epoch_zero_bypasses_fence() {
            // Even with a large counter gap, a ZERO-epoch signal (operator / KV notification)
            // is authoritative and must be processed.
            seedTwoCoreNodesAtEpoch(Epoch.epoch(6L, 50L));
            reconciler.start(Epoch.epoch(6L, 0L));

            reconciler.onSignal(new HealthSignal.OperatorAction(new OperatorIntent.SetDesiredSize(9)));

            assertThat(reconciler.currentSnapshot().desiredCoreSize()).isEqualTo(9);
        }
    }

    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final List<List<KVCommand<AetherKey>>> batches = new ArrayList<>();

        @Override public NodeId self() {return SELF;}

        @Override public TopologyManager topologyManager() {
            throw new UnsupportedOperationException("not used");
        }

        @Override public Promise<Unit> start() {return Promise.success(Unit.unit());}
        @Override public Promise<Unit> stop() {return Promise.success(Unit.unit());}

        @SuppressWarnings({"unchecked", "rawtypes"})
        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            batches.add(List.copyOf(commands));
            return (Promise) Promise.success(List.of());
        }

        List<KVCommand.Put<AetherKey, AetherValue>> applied() {
            var all = new ArrayList<KVCommand.Put<AetherKey, AetherValue>>();
            for (var batch : batches) {
                for (var cmd : batch) {
                    if (cmd instanceof KVCommand.Put<?, ?> put) {
                        @SuppressWarnings({"unchecked", "rawtypes"})
                        var cast = (KVCommand.Put<AetherKey, AetherValue>) (KVCommand.Put) put;
                        all.add(cast);
                    }
                }
            }
            return all;
        }

    }
}
