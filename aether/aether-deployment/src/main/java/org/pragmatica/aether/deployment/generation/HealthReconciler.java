// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.pragmatica.aether.deployment.generation.fsm.HealthReconcilerContext;
import org.pragmatica.aether.metrics.observation.PeerObservationStore;
import org.pragmatica.aether.deployment.generation.fsm.HealthReconcilerEvents.MembershipReseeded;
import org.pragmatica.aether.deployment.generation.fsm.HealthReconcilerEvents.ReprojectionRequested;
import org.pragmatica.aether.deployment.generation.fsm.HealthReconcilerEvents.SignalReceived;
import org.pragmatica.aether.deployment.generation.fsm.HealthReconcilerEvents.SnapshotSeeded;
import org.pragmatica.aether.deployment.generation.fsm.HealthReconcilerState;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.GenerationChangedSink;
import org.pragmatica.aether.slice.generation.HealthSignal;
import org.pragmatica.aether.slice.generation.HealthSignalSink;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.statemachine.Fsm;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Single-writer reconciler for cluster membership atoms. Leader-only — gated by an internal FSM
/// whose `Leading*` states own the authoritative snapshot and `startEpoch`.
///
/// Lifecycle is owned by an explicit FSM: `Dormant → QuorumWaiting → LeadingSteady ⇄
/// LeadingReprojecting`, with transitions back to `Dormant` on leader loss and to a terminal
/// `Stopped` on shutdown. See [`HealthReconcilerState`] for the full transition diagram.
///
/// Public surface: `start()` / `stop(StopReason)` translate to FSM dispatches;
/// `onSignal(HealthSignal)` enqueues a `SignalReceived` event; `requestReprojection(...)` triggers
/// a coalescing `LeadingReprojecting` swap. The leader epoch read at `start()` time comes from
/// [`HealthReconcilerContext#defaultLeaderEpoch`] — the same source surfaced by
/// [`org.pragmatica.consensus.leader.LeaderManager#currentLeaderEpoch`].
///
/// Thread-confinement contract:
///   - `onSignal` / `seedSnapshot` / `start` / `stop` / `reseedMembership` are serialized via the
///     FSM's CAS-guarded dispatch path.
///   - `requestReprojection(...)` is safe to call from any thread; the FSM transitions to
///     `LeadingReprojecting`, whose `onEntry` submits the supplier to a dedicated single-thread
///     executor owned by the Context. The executor dispatches `ReprojectionCompleted` /
///     `ReprojectionFailed` back into the FSM.
///
/// See `aether/docs/specs/cluster-generation-spec.md` §8.
public interface HealthReconciler extends HealthSignalSink {
    int DEFAULT_SUSPECT_INTERVAL_THRESHOLD = 3;

    int DEFAULT_REMOVE_INTERVAL_THRESHOLD = 10;

    int LATE_SIGNAL_WINDOW = 2;

    @Contract void start();
    @Contract void stop(StopReason reason);
    @Contract boolean isActive();
    @Contract void onSignal(HealthSignal signal);
    ClusterGenerationSnapshot currentSnapshot();
    Epoch currentEpoch();
    NodeId self();
    @Contract void seedSnapshot(ClusterGenerationSnapshot snapshot);
    @Contract void reseedMembership(ClusterGenerationSnapshot freshProjection);
    @Contract void requestReprojection(Supplier<ClusterGenerationSnapshot> reprojectionSupplier, String reason);
    @Contract void requestReprojection(String reason);
    long consensusApplyFailedCount();

    @Contract@Override default void emit(HealthSignal signal) {
        onSignal(signal);
    }

    static HealthReconciler healthReconciler(NodeId self,
                                             ClusterNode<KVCommand<AetherKey>> cluster,
                                             ClusterGenerationProjector projector,
                                             HlcClock hlcClock,
                                             Supplier<Long> rabiaTermSupplier,
                                             BooleanSupplier isLeaderSupplier,
                                             AutoHealConfig autoHealConfig) {
        return healthReconciler(self,
                                cluster,
                                projector,
                                hlcClock,
                                rabiaTermSupplier,
                                isLeaderSupplier,
                                autoHealConfig,
                                GenerationChangedSink.noop(),
                                PeerObservationStore.peerObservationStore());
    }

    static HealthReconciler healthReconciler(NodeId self,
                                             ClusterNode<KVCommand<AetherKey>> cluster,
                                             ClusterGenerationProjector projector,
                                             HlcClock hlcClock,
                                             Supplier<Long> rabiaTermSupplier,
                                             BooleanSupplier isLeaderSupplier,
                                             AutoHealConfig autoHealConfig,
                                             GenerationChangedSink generationChangedSink) {
        return healthReconciler(self,
                                cluster,
                                projector,
                                hlcClock,
                                rabiaTermSupplier,
                                isLeaderSupplier,
                                autoHealConfig,
                                generationChangedSink,
                                PeerObservationStore.peerObservationStore());
    }

    static HealthReconciler healthReconciler(NodeId self,
                                             ClusterNode<KVCommand<AetherKey>> cluster,
                                             ClusterGenerationProjector projector,
                                             HlcClock hlcClock,
                                             Supplier<Long> rabiaTermSupplier,
                                             BooleanSupplier isLeaderSupplier,
                                             AutoHealConfig autoHealConfig,
                                             GenerationChangedSink generationChangedSink,
                                             PeerObservationStore peerObservationStore) {
        var ctxHolder = new AtomicReference<HealthReconcilerContext>();
        Function<Fsm<HealthReconcilerState, ClusterFsmEvent>, HealthReconcilerState> initialStateFactory = fsm -> buildContextAndDormant(fsm,
                                                                                                                                         ctxHolder,
                                                                                                                                         self,
                                                                                                                                         cluster,
                                                                                                                                         hlcClock,
                                                                                                                                         rabiaTermSupplier,
                                                                                                                                         isLeaderSupplier,
                                                                                                                                         autoHealConfig,
                                                                                                                                         generationChangedSink,
                                                                                                                                         peerObservationStore);
        // Fsm constructor publishes itself into ctxHolder via initialStateFactory —
        // we only need the context here; the FSM reference lives on ctx.fsm().
        var _fsm = Fsm.fsm("health-reconciler", self.id(), initialStateFactory);
        return new HealthReconcilerRecord(ctxHolder.get());
    }

    private static HealthReconcilerState buildContextAndDormant(Fsm<HealthReconcilerState, ClusterFsmEvent> fsm,
                                                                AtomicReference<HealthReconcilerContext> ctxHolder,
                                                                NodeId self,
                                                                ClusterNode<KVCommand<AetherKey>> cluster,
                                                                HlcClock hlcClock,
                                                                Supplier<Long> rabiaTermSupplier,
                                                                BooleanSupplier isLeaderSupplier,
                                                                AutoHealConfig autoHealConfig,
                                                                GenerationChangedSink generationChangedSink,
                                                                PeerObservationStore peerObservationStore) {
        var ctx = new HealthReconcilerContext(fsm,
                                              self,
                                              cluster,
                                              hlcClock,
                                              rabiaTermSupplier,
                                              isLeaderSupplier,
                                              autoHealConfig,
                                              generationChangedSink,
                                              PeerObservationReducer.peerObservationReducer(),
                                              peerObservationStore);
        ctxHolder.set(ctx);
        return ctx.dormant();
    }
}

/// Thin adapter: translates the public [`HealthReconciler`] surface into FSM dispatches and
/// context queries. All lifecycle state (Dormant / QuorumWaiting / Following / LeadingSteady /
/// LeadingReprojecting / Stopped), the authoritative cluster-generation snapshot, the
/// `startEpoch`, and all decision-table computation live inside the FSM / Context.
record HealthReconcilerRecord(HealthReconcilerContext ctx) implements HealthReconciler {
    private static final Logger log = LoggerFactory.getLogger(HealthReconcilerRecord.class);

    @Contract@Override public void start() {
        // Drive the FSM into a Leading* state via the canonical events. The first dispatch is
        // a no-op when already past Dormant; the LeaderChange transitions Dormant /
        // QuorumWaiting / Following → LeadingSteady, with the epoch sourced inside the state
        // handler from `ctx.defaultLeaderEpoch()` (single source of truth — same supplier
        // surfaced by `LeaderManager.currentLeaderEpoch()`).
        ctx.dispatch(new ClusterFsmEvent.QuorumEstablished());
        ctx.dispatch(new ClusterFsmEvent.LeaderChange(Option.some(ctx.self()), true));
        log.debug("HealthReconciler started at epoch {}", ctx.defaultLeaderEpoch());
    }

    @Contract@Override public void stop(StopReason reason) {
        switch (reason){
            case LEADER_LOST -> ctx.dispatch(new ClusterFsmEvent.QuorumDisappeared());
            case SHUTDOWN -> ctx.dispatch(new ClusterFsmEvent.Shutdown());
        }
        log.debug("HealthReconciler stopped (reason={})", reason);
    }

    @Contract@Override public boolean isActive() {
        return isLeading();
    }

    private boolean isLeading() {
        var state = ctx.fsm().current();
        return state instanceof HealthReconcilerState.LeadingSteady || state instanceof HealthReconcilerState.LeadingReprojecting;
    }

    @Override public ClusterGenerationSnapshot currentSnapshot() {
        return switch (ctx.fsm().current()){
            case HealthReconcilerState.LeadingSteady ls -> ls.snapshot();
            case HealthReconcilerState.LeadingReprojecting lr -> lr.snapshot();
            default -> ctx.ambientSnapshot();
        };
    }

    @Override public Epoch currentEpoch() {
        return currentSnapshot().epoch();
    }

    @Override public NodeId self() {
        return ctx.self();
    }

    @Contract@Override public void seedSnapshot(ClusterGenerationSnapshot snapshot) {
        ctx.dispatch(new SnapshotSeeded(snapshot));
    }

    @Contract@Override public void reseedMembership(ClusterGenerationSnapshot freshProjection) {
        ctx.dispatch(new MembershipReseeded(freshProjection));
    }

    @Contract@Override public void onSignal(HealthSignal signal) {
        ctx.dispatch(new SignalReceived(signal));
    }

    @Contract@Override public void requestReprojection(Supplier<ClusterGenerationSnapshot> reprojectionSupplier,
                                                       String reason) {
        if (reprojectionSupplier == null) {return;}
        ctx.dispatch(new ReprojectionRequested(reprojectionSupplier, reason));
    }

    @Contract@Override public void requestReprojection(String reason) {
        ctx.lastSupplier().onPresent(fn -> ctx.dispatch(new ReprojectionRequested(fn, reason)));
    }

    @Override public long consensusApplyFailedCount() {
        return ctx.consensusApplyFailedCount();
    }
}
