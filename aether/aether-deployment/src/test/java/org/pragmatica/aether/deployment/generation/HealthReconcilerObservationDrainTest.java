// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.generation.fsm.HealthReconcilerContext;
import org.pragmatica.aether.deployment.generation.fsm.HealthReconcilerEvents.BecameLeader;
import org.pragmatica.aether.deployment.generation.fsm.HealthReconcilerEvents.SnapshotSeeded;
import org.pragmatica.aether.deployment.generation.fsm.HealthReconcilerState;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.metrics.observation.PeerObservationStore;
import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.CoreMember;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.GenerationChangedSink;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.cluster.metrics.ConnectivityState;
import org.pragmatica.cluster.metrics.HealthHintWire;
import org.pragmatica.cluster.metrics.PeerConnectivityObservation;
import org.pragmatica.cluster.metrics.PeerHealthObservation;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.LeaderChange;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.QuorumDisappeared;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.QuorumEstablished;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;


/// Q3 contract tests for the buffered-observation drain + live-subscribe wiring on
/// `HealthReconciler.LeadingSteady` and `LeadingReprojecting`.
///
/// Q1 introduced [`PeerObservationStore`] as the node-singleton buffer. Q4 added a wall-clock
/// staleness filter on remote observations. Q3 wires the two together: when a follower is
/// promoted to leader, the freshly-elected leader (1) drains every observation buffered
/// during follower-era on entry to `LeadingSteady` and (2) subscribes for future arrivals so
/// late observations (or anything written between subscribe-time and drain-time) reach the
/// FSM without waiting for a peer pong.
///
/// On exit from Leading* (demote / quorum loss / shutdown / coalesce / re-projection swap),
/// the held subscriptions MUST be released — verified here.
class HealthReconcilerObservationDrainTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final NodeId PEER_A = NodeId.nodeId("peer-a").unwrap();
    private static final NodeId PEER_B = NodeId.nodeId("peer-b").unwrap();

    private static final TimeSpan STALE_TTL = TimeSpan.timeSpan(30).seconds();

    private AtomicBoolean externalLeaderGate;
    private AtomicLong rabiaTerm;
    private AtomicLong now;
    private PeerObservationStore store;
    private FsmTestHarness<HealthReconcilerState, ClusterFsmEvent> harness;
    private HealthReconcilerContext ctx;

    @BeforeEach
    void setUp() {
        externalLeaderGate = new AtomicBoolean(true);
        rabiaTerm = new AtomicLong(1L);
        now = new AtomicLong(1_000_000_000L);
        store = PeerObservationStore.peerObservationStore();
        var hlcClock = HlcClock.hlcClock(SELF.id()).unwrap();
        ClusterNode<KVCommand<AetherKey>> cluster = new NoopClusterNode();
        var autoHeal = AutoHealConfig.autoHealConfig(TimeSpan.timeSpan(10).seconds(),
                                                      TimeSpan.timeSpan(15).seconds(),
                                                      STALE_TTL).unwrap();
        var ctxHolder = new AtomicReference<HealthReconcilerContext>();
        Function<Fsm<HealthReconcilerState, ClusterFsmEvent>, HealthReconcilerState> factory =
                fsm -> {
                    var c = new HealthReconcilerContext(fsm,
                                                        SELF,
                                                        cluster,
                                                        hlcClock,
                                                        rabiaTerm::get,
                                                        externalLeaderGate::get,
                                                        autoHeal,
                                                        GenerationChangedSink.noop(),
                                                        PeerObservationReducer.peerObservationReducer(),
                                                        store,
                                                        now::get);
                    ctxHolder.set(c);
                    return c.dormant();
                };
        harness = FsmTestHarness.harness("health-reconciler-drain-test-" + SELF.id(), factory);
        ctx = ctxHolder.get();
        harness.dispatch(new SnapshotSeeded(seedSnapshotWithCorePeers()));
        harness.dispatch(new QuorumEstablished());
    }

    private static ClusterGenerationSnapshot seedSnapshotWithCorePeers() {
        var snapshot = ClusterGenerationSnapshot.empty(1L).withDesiredCoreSize(2);
        var coreMembers = new LinkedHashMap<NodeId, CoreMember>();
        coreMembers.put(PEER_A, healthyMember(PEER_A, "host-a"));
        coreMembers.put(PEER_B, healthyMember(PEER_B, "host-b"));
        return snapshot.withCoreMembers(coreMembers);
    }

    private static CoreMember healthyMember(NodeId nodeId, String host) {
        return CoreMember.coreMember(nodeId,
                                     host,
                                     9001,
                                     NodeLifecycleState.ON_DUTY,
                                     HealthHint.HEALTHY,
                                     Epoch.epoch(1L, 0L),
                                     Epoch.epoch(1L, 0L));
    }

    @Nested
    class DrainOnEntry {
        @Test
        void leadingSteady_onEntry_drainsFreshHealthObservationsBufferedBeforePromotion() {
            // Buffer a fresh FAULTY observation for PEER_A while still Dormant/QuorumWaiting.
            store.pushHealth(new PeerHealthObservation(PEER_A,
                                                       HealthHintWire.FAULTY,
                                                       1L, 0L,
                                                       now.get() - 1_000L));
            harness.dispatch(new BecameLeader(Epoch.epoch(1L, 0L)));
            assertThat(currentSnapshot().coreMembers().get(PEER_A).healthHint()).isEqualTo(HealthHint.SUSPECTED);
        }

        @Test
        void leadingSteady_onEntry_drainsFreshConnectivityObservationsBufferedBeforePromotion() {
            // Buffer a DISCONNECTED observation for PEER_A. RemoteConnectivity routes through
            // handleQuicDisconnect, which records a ping miss on the store. Verify the miss
            // counter advanced (proves the drain delivered the observation to the FSM).
            store.pushConnectivity(new PeerConnectivityObservation(PEER_A,
                                                                    ConnectivityState.DISCONNECTED,
                                                                    1L, 0L,
                                                                    now.get() - 1_000L));
            assertThat(store.pingMissCount(PEER_A)).isEqualTo(0);
            harness.dispatch(new BecameLeader(Epoch.epoch(1L, 0L)));
            assertThat(store.pingMissCount(PEER_A)).isEqualTo(1);
        }
    }

    @Nested
    class StalenessFilterOnDrain {
        @Test
        void leadingSteady_onEntry_stalenessFilterDropsExpiredHealthObservation() {
            // Observation produced 60s ago, twice the 30s TTL — must be dropped on drain.
            store.pushHealth(new PeerHealthObservation(PEER_A,
                                                       HealthHintWire.FAULTY,
                                                       1L, 0L,
                                                       now.get() - 60_000L));
            harness.dispatch(new BecameLeader(Epoch.epoch(1L, 0L)));
            assertThat(currentSnapshot().coreMembers().get(PEER_A).healthHint()).isEqualTo(HealthHint.HEALTHY);
        }

        @Test
        void leadingSteady_onEntry_stalenessFilterDropsExpiredConnectivityObservation() {
            // Stale RemoteConnectivity is dropped before it can record a ping miss.
            store.pushConnectivity(new PeerConnectivityObservation(PEER_A,
                                                                    ConnectivityState.DISCONNECTED,
                                                                    1L, 0L,
                                                                    now.get() - 60_000L));
            harness.dispatch(new BecameLeader(Epoch.epoch(1L, 0L)));
            assertThat(store.pingMissCount(PEER_A)).isEqualTo(0);
        }
    }

    @Nested
    class LiveSubscription {
        @Test
        void leadingSteady_liveCallback_freshHealthObservationAfterEntryReachesFsm() {
            harness.dispatch(new BecameLeader(Epoch.epoch(1L, 0L)));
            // Buffer was empty on entry; now push live and verify it lands.
            assertThat(currentSnapshot().coreMembers().get(PEER_A).healthHint()).isEqualTo(HealthHint.HEALTHY);
            store.pushHealth(new PeerHealthObservation(PEER_A,
                                                       HealthHintWire.FAULTY,
                                                       1L, 0L,
                                                       now.get() - 500L));
            assertThat(currentSnapshot().coreMembers().get(PEER_A).healthHint()).isEqualTo(HealthHint.SUSPECTED);
        }

        @Test
        void leadingSteady_liveCallback_stalenessFilterAppliedToLiveArrivals() {
            harness.dispatch(new BecameLeader(Epoch.epoch(1L, 0L)));
            store.pushHealth(new PeerHealthObservation(PEER_A,
                                                       HealthHintWire.FAULTY,
                                                       1L, 0L,
                                                       now.get() - 60_000L));
            // Live arrival but already stale — filter must drop it.
            assertThat(currentSnapshot().coreMembers().get(PEER_A).healthHint()).isEqualTo(HealthHint.HEALTHY);
        }
    }

    @Nested
    class UnsubscribeOnExit {
        @Test
        void demote_releasesSubscriptionsViaClearLeaderData_noCallbackDispatchAfterExit() {
            harness.dispatch(new BecameLeader(Epoch.epoch(1L, 0L)));
            // Demote — Dormant.onEntry calls clearLeaderData which releases both subscriptions.
            harness.dispatch(new LeaderChange(Option.none(), false));
            assertThat(harness.state()).isInstanceOf(HealthReconcilerState.Dormant.class);
            var ignoredBefore = harness.ignored().size();
            // If the subscription is still active, the live push will trigger the callback,
            // which dispatches SignalReceived; Dormant ignores it (recorded in `ignored`).
            // If unsubscribed correctly, no dispatch happens at all → `ignored` unchanged.
            store.pushHealth(new PeerHealthObservation(PEER_A,
                                                       HealthHintWire.FAULTY,
                                                       1L, 0L,
                                                       now.get() - 500L));
            assertThat(harness.ignored().size()).isEqualTo(ignoredBefore);
        }

        @Test
        void quorumLost_releasesSubscriptionsViaClearLeaderData_noCallbackDispatchAfterExit() {
            harness.dispatch(new BecameLeader(Epoch.epoch(1L, 0L)));
            harness.dispatch(new QuorumDisappeared());
            assertThat(harness.state()).isInstanceOf(HealthReconcilerState.Dormant.class);
            var ignoredBefore = harness.ignored().size();
            store.pushConnectivity(new PeerConnectivityObservation(PEER_A,
                                                                    ConnectivityState.DISCONNECTED,
                                                                    1L, 0L,
                                                                    now.get() - 500L));
            assertThat(harness.ignored().size()).isEqualTo(ignoredBefore);
        }
    }

    private ClusterGenerationSnapshot currentSnapshot() {
        return switch (harness.state()){
            case HealthReconcilerState.LeadingSteady ls -> ls.snapshot();
            case HealthReconcilerState.LeadingReprojecting lr -> lr.snapshot();
            default -> ctx.ambientSnapshot();
        };
    }

    private static final class NoopClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        @Override public NodeId self() { return SELF; }

        @Override public TopologyManager topologyManager() {
            throw new UnsupportedOperationException("not used in tests");
        }

        @Override public Promise<Unit> start() { return Promise.success(Unit.unit()); }

        @Override public Promise<Unit> stop() { return Promise.success(Unit.unit()); }

        @SuppressWarnings({"unchecked", "rawtypes"})
        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            return (Promise) Promise.success(new ArrayList<>());
        }
    }
}
