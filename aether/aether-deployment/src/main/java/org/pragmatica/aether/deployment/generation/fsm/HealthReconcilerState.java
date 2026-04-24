// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation.fsm;

import org.pragmatica.aether.deployment.generation.fsm.HealthReconcilerEvents.BecameLeader;
import org.pragmatica.aether.deployment.generation.fsm.HealthReconcilerEvents.CommandsApplied;
import org.pragmatica.aether.deployment.generation.fsm.HealthReconcilerEvents.CommandsApplyFailed;
import org.pragmatica.aether.deployment.generation.fsm.HealthReconcilerEvents.MembershipReseeded;
import org.pragmatica.aether.deployment.generation.fsm.HealthReconcilerEvents.ReprojectionCompleted;
import org.pragmatica.aether.deployment.generation.fsm.HealthReconcilerEvents.ReprojectionFailed;
import org.pragmatica.aether.deployment.generation.fsm.HealthReconcilerEvents.ReprojectionRequested;
import org.pragmatica.aether.deployment.generation.fsm.HealthReconcilerEvents.SignalReceived;
import org.pragmatica.aether.deployment.generation.fsm.HealthReconcilerEvents.SnapshotSeeded;
import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.lang.Contract;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.LeaderChange;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.QuorumDisappeared;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.QuorumEstablished;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.Shutdown;
import org.pragmatica.statemachine.FsmState;
import org.pragmatica.statemachine.TransitionRequest;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Sealed state hierarchy for the HealthReconciler FSM.
///
/// ```text
/// Dormant
///   ──QuorumEstablished──► QuorumWaiting
/// QuorumWaiting
///   ──LeaderChange(localIsLeader=true) | BecameLeader(epoch)──► LeadingSteady(startEpoch, snapshot)
/// QuorumWaiting
///   ──LeaderChange(localIsLeader=false)──► Following
/// Following
///   ──LeaderChange(localIsLeader=true) | BecameLeader(epoch)──► LeadingSteady(startEpoch, snapshot)
/// LeadingSteady
///   ──ReprojectionRequested(supplier)──► LeadingReprojecting(startEpoch, snapshot, supplier)
/// LeadingReprojecting
///   ──ReprojectionCompleted(startEpoch, new)──► LeadingSteady(startEpoch, new)   (accept)
/// LeadingReprojecting
///   ──ReprojectionFailed(startEpoch)──► LeadingSteady(startEpoch, snapshot)       (rollback)
/// LeadingReprojecting
///   ──ReprojectionRequested(newSupplier)──► LeadingReprojecting(startEpoch, snapshot, newSupplier)  (coalesce)
/// Leading*  ──LeaderChange(localIsLeader=false) | QuorumDisappeared──► Dormant   (clears leader data)
/// Any (non-terminal) ──Shutdown──► Stopped
/// ```
///
/// - `Dormant`, `QuorumWaiting`, `Following`, `Stopped` are per-context singletons (data-free).
/// - `LeadingSteady(startEpoch, snapshot)` and `LeadingReprojecting(startEpoch, snapshot, supplier)`
///   are fresh records per entry. `dirty` is implicit in state identity: being in
///   `LeadingReprojecting` *is* "a reprojection is pending"; the explicit boolean from the legacy
///   implementation is redundant.
public sealed interface HealthReconcilerState extends FsmState<HealthReconcilerState, ClusterFsmEvent>
        permits HealthReconcilerState.Dormant,
                HealthReconcilerState.QuorumWaiting,
                HealthReconcilerState.Following,
                HealthReconcilerState.LeadingSteady,
                HealthReconcilerState.LeadingReprojecting,
                HealthReconcilerState.Stopped {
    Logger LOG = LoggerFactory.getLogger(HealthReconcilerState.class);

    HealthReconcilerContext ctx();

    record Dormant(HealthReconcilerContext ctx) implements HealthReconcilerState {
        @Override public void onEntry() {
            ctx.clearLeaderData();
        }

        @Override public void handle(ClusterFsmEvent event,
                                     TransitionRequest<HealthReconcilerState, ClusterFsmEvent> tx) {
            switch (event){
                case QuorumEstablished _ -> tx.transitionTo(ctx.quorumWaiting());
                case BecameLeader be -> tx.transitionTo(ctx.newLeadingSteady(be.startEpoch(), ctx.ambientSnapshot()));
                case LeaderChange lc when lc.localIsLeader() -> tx.transitionTo(ctx.quorumWaiting());
                case SnapshotSeeded seeded -> handleSnapshotSeededAmbient(seeded, tx);
                case Shutdown _ -> tx.transitionTo(ctx.stopped());
                default -> tx.ignore();
            }
        }

        private void handleSnapshotSeededAmbient(SnapshotSeeded event,
                                                 TransitionRequest<HealthReconcilerState, ClusterFsmEvent> tx) {
            ctx.setAmbientSnapshot(event.snapshot());
            tx.ignore();
        }
    }

    record QuorumWaiting(HealthReconcilerContext ctx) implements HealthReconcilerState {
        @Override public void handle(ClusterFsmEvent event,
                                     TransitionRequest<HealthReconcilerState, ClusterFsmEvent> tx) {
            switch (event){
                case BecameLeader be -> tx.transitionTo(ctx.newLeadingSteady(be.startEpoch(), ctx.ambientSnapshot()));
                case LeaderChange lc when lc.localIsLeader() -> tx.transitionTo(ctx.newLeadingSteady(ctx.defaultLeaderEpoch(),
                                                                                                     ctx.ambientSnapshot()));
                case LeaderChange lc when !lc.localIsLeader() -> tx.transitionTo(ctx.following());
                case QuorumDisappeared _ -> tx.transitionTo(ctx.dormant());
                case SnapshotSeeded seeded -> handleSnapshotSeededAmbient(seeded, tx);
                case Shutdown _ -> tx.transitionTo(ctx.stopped());
                default -> tx.ignore();
            }
        }

        private void handleSnapshotSeededAmbient(SnapshotSeeded event,
                                                 TransitionRequest<HealthReconcilerState, ClusterFsmEvent> tx) {
            ctx.setAmbientSnapshot(event.snapshot());
            tx.ignore();
        }
    }

    record Following(HealthReconcilerContext ctx) implements HealthReconcilerState {
        @Override public void onEntry() {
            ctx.clearLeaderData();
        }

        @Override public void handle(ClusterFsmEvent event,
                                     TransitionRequest<HealthReconcilerState, ClusterFsmEvent> tx) {
            switch (event){
                case BecameLeader be -> tx.transitionTo(ctx.newLeadingSteady(be.startEpoch(), ctx.ambientSnapshot()));
                case LeaderChange lc when lc.localIsLeader() -> tx.transitionTo(ctx.newLeadingSteady(ctx.defaultLeaderEpoch(),
                                                                                                     ctx.ambientSnapshot()));
                case QuorumDisappeared _ -> tx.transitionTo(ctx.dormant());
                case SnapshotSeeded seeded -> handleSnapshotSeededAmbient(seeded, tx);
                case Shutdown _ -> tx.transitionTo(ctx.stopped());
                default -> tx.ignore();
            }
        }

        private void handleSnapshotSeededAmbient(SnapshotSeeded event,
                                                 TransitionRequest<HealthReconcilerState, ClusterFsmEvent> tx) {
            ctx.setAmbientSnapshot(event.snapshot());
            tx.ignore();
        }
    }

    record LeadingSteady(HealthReconcilerContext ctx, Epoch startEpoch, ClusterGenerationSnapshot snapshot) implements HealthReconcilerState {
        @Override public void onEntry() {
            ctx.ensureReprojectionExecutor();
            ctx.publishLeadingSnapshot(snapshot);
        }

        @Override public void handle(ClusterFsmEvent event,
                                     TransitionRequest<HealthReconcilerState, ClusterFsmEvent> tx) {
            switch (event){
                case SignalReceived sr -> ctx.handleSignalFromLeadingSteady(this, sr, tx);
                case ReprojectionRequested rr -> handleReprojectionRequested(rr, tx);
                case MembershipReseeded mr -> ctx.handleMembershipReseedFromLeadingSteady(this, mr, tx);
                case SnapshotSeeded seeded -> tx.transitionToOrDrop(ctx.newLeadingSteady(startEpoch,
                                                                                         eventSnapshot(seeded)));
                case CommandsApplied ca -> ctx.handleCommandsAppliedFromLeadingSteady(this, ca, tx);
                case CommandsApplyFailed cf -> ctx.handleCommandsApplyFailedFromLeading(this, cf, tx);
                case QuorumDisappeared _ -> tx.transitionTo(ctx.dormant());
                case LeaderChange lc when !lc.localIsLeader() -> tx.transitionTo(ctx.dormant());
                case Shutdown _ -> tx.transitionTo(ctx.stopped());
                default -> tx.ignore();
            }
        }

        private static ClusterGenerationSnapshot eventSnapshot(SnapshotSeeded seeded) {
            return seeded.snapshot();
        }

        private void handleReprojectionRequested(ReprojectionRequested event,
                                                 TransitionRequest<HealthReconcilerState, ClusterFsmEvent> tx) {
            if (!ctx.gateAllowsLeaderWork()) {
                tx.ignore();
                return;
            }
            ctx.rememberSupplier(event.supplier());
            tx.transitionToOrDrop(ctx.newLeadingReprojecting(startEpoch, snapshot, event.supplier(), event.reason()));
        }
    }

    record LeadingReprojecting(HealthReconcilerContext ctx,
                               Epoch startEpoch,
                               ClusterGenerationSnapshot snapshot,
                               Supplier<ClusterGenerationSnapshot> supplier) implements HealthReconcilerState {
        @Override public void onEntry() {
            ctx.publishLeadingSnapshot(snapshot);
            ctx.submitReprojection(startEpoch, supplier);
        }

        @Override public void handle(ClusterFsmEvent event,
                                     TransitionRequest<HealthReconcilerState, ClusterFsmEvent> tx) {
            switch (event){
                case ReprojectionCompleted rc -> handleReprojectionCompleted(rc, tx);
                case ReprojectionFailed rf -> handleReprojectionFailed(rf, tx);
                case ReprojectionRequested rr -> handleCoalesce(rr, tx);
                case SignalReceived sr -> ctx.handleSignalFromLeadingReprojecting(this, sr, tx);
                case MembershipReseeded mr -> ctx.handleMembershipReseedFromLeadingReprojecting(this, mr, tx);
                case SnapshotSeeded seeded -> tx.transitionToOrDrop(ctx.newLeadingReprojecting(startEpoch,
                                                                                               seeded.snapshot(),
                                                                                               supplier,
                                                                                               "snapshot-seeded"));
                case CommandsApplied ca -> ctx.handleCommandsAppliedFromLeadingReprojecting(this, ca, tx);
                case CommandsApplyFailed cf -> ctx.handleCommandsApplyFailedFromLeading(this, cf, tx);
                case QuorumDisappeared _ -> tx.transitionTo(ctx.dormant());
                case LeaderChange lc when !lc.localIsLeader() -> tx.transitionTo(ctx.dormant());
                case Shutdown _ -> tx.transitionTo(ctx.stopped());
                default -> tx.ignore();
            }
        }

        private void handleReprojectionCompleted(ReprojectionCompleted event,
                                                 TransitionRequest<HealthReconcilerState, ClusterFsmEvent> tx) {
            if (!event.startEpoch().equals(startEpoch)) {
                LOG.trace("Dropping stale ReprojectionCompleted: event epoch {} != current {}",
                          event.startEpoch(),
                          startEpoch);
                tx.ignore();
                return;
            }
            ctx.handleReprojectionCompletedPayload(this, event.newSnapshot(), tx);
        }

        private void handleReprojectionFailed(ReprojectionFailed event,
                                              TransitionRequest<HealthReconcilerState, ClusterFsmEvent> tx) {
            if (!event.startEpoch().equals(startEpoch)) {
                tx.ignore();
                return;
            }
            tx.transitionToOrDrop(ctx.newLeadingSteady(startEpoch, snapshot));
        }

        private void handleCoalesce(ReprojectionRequested event,
                                    TransitionRequest<HealthReconcilerState, ClusterFsmEvent> tx) {
            if (!ctx.gateAllowsLeaderWork()) {
                tx.ignore();
                return;
            }
            ctx.rememberSupplier(event.supplier());
            tx.transitionToOrDrop(ctx.newLeadingReprojecting(startEpoch, snapshot, event.supplier(), event.reason()));
        }
    }

    record Stopped(HealthReconcilerContext ctx) implements HealthReconcilerState {
        @Contract @Override public void onEntry() {
            LOG.debug("HealthReconciler stopped");
            ctx.clearLeaderData();
            ctx.shutdownReprojectionExecutor();
        }

        @Contract @Override public void handle(ClusterFsmEvent event,
                                                TransitionRequest<HealthReconcilerState, ClusterFsmEvent> tx) {
            tx.ignore();
        }
    }
}
