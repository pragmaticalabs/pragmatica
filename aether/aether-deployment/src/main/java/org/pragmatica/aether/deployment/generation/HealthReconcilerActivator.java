// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.HealthSignal;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SpokesmanKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SpokesmanStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SpokesmanValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.lang.Contract;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Wires the leader-only `HealthReconciler` into the message bus for Commit 3 activation.
///
/// Subscribes to:
///   - `LeaderChange` — toggles the `isLeader` gate and starts/stops the reconciler
///   - `GovernorAnnouncementKey` PUT — emits `GovernorAnnounced` or
///     `CommunityDissolved` depending on the dissolved flag
///   - `GovernorAnnouncementKey` REMOVE — emits `CommunityDissolved`
///   - `SpokesmanKey` PUT with `status == FAILED` — emits `SpokesmanAssignmentFailed`
///   - `NodeLifecycleKey` PUT with terminal state (DECOMMISSIONED) — emits an
///     internal re-project signal if the node was core (falls through
///     `HealthReconciler.handlePingTimeout`/partition transfer paths via
///     existing decision table)
///
/// In Commit 3 the activator writes only NEW atoms (`SpokesmanKey`,
/// `DhtPartitionOwnershipKey`) indirectly via the reconciler's decision table.
/// `NodeLifecycleKey = LEFT` writes for health-detected failures are NOT
/// issued here — deferred to Commit 4/5.
///
/// See `aether/docs/specs/cluster-generation-spec.md` §8.
public interface HealthReconcilerActivator {
    @Contract void onLeaderChange(LeaderChange change);
    @Contract void onGovernorAnnouncementPut(ValuePut<GovernorAnnouncementKey, GovernorAnnouncementValue> notification);
    @Contract void onGovernorAnnouncementRemove(ValueRemove<GovernorAnnouncementKey, GovernorAnnouncementValue> notification);
    @Contract void onSpokesmanPut(ValuePut<SpokesmanKey, SpokesmanValue> notification);
    @Contract void onNodeLifecyclePut(ValuePut<NodeLifecycleKey, NodeLifecycleValue> notification);

    static HealthReconcilerActivator healthReconcilerActivator(HealthReconciler reconciler,
                                                               AtomicBoolean isLeaderGate) {
        return new HealthReconcilerActivatorRecord(reconciler, isLeaderGate);
    }
}

record HealthReconcilerActivatorRecord(HealthReconciler reconciler, AtomicBoolean isLeaderGate) implements HealthReconcilerActivator {
    private static final Logger log = LoggerFactory.getLogger(HealthReconcilerActivatorRecord.class);

    @Contract@Override public void onLeaderChange(LeaderChange change) {
        isLeaderGate.set(change.localNodeIsLeader());
        if (change.localNodeIsLeader()) {
            log.info("HealthReconciler becoming leader — starting reconciler");
            reconciler.start();
        } else {
            log.info("HealthReconciler stepping down — stopping reconciler");
            reconciler.stop();
            reconciler.seedSnapshot(ClusterGenerationSnapshot.empty(reconciler.currentEpoch().rabiaTerm()));
        }
    }

    @Contract@Override public void onGovernorAnnouncementPut(ValuePut<GovernorAnnouncementKey, GovernorAnnouncementValue> notification) {
        if (!isLeaderGate.get()) {return;}
        var communityId = notification.cause().key()
                                            .communityId();
        var value = notification.cause().value();
        if (value.dissolved()) {
            reconciler.onSignal(new HealthSignal.CommunityDissolved(communityId));
            return;
        }
        reconciler.onSignal(new HealthSignal.GovernorAnnounced(communityId, value.governorId(), value.communityTerm()));
    }

    @Contract@Override public void onGovernorAnnouncementRemove(ValueRemove<GovernorAnnouncementKey, GovernorAnnouncementValue> notification) {
        if (!isLeaderGate.get()) {return;}
        reconciler.onSignal(new HealthSignal.CommunityDissolved(notification.cause().key()
                                                                                  .communityId()));
    }

    @Contract@Override public void onSpokesmanPut(ValuePut<SpokesmanKey, SpokesmanValue> notification) {
        if (!isLeaderGate.get()) {return;}
        var value = notification.cause().value();
        if (value.status() != SpokesmanStatus.FAILED) {return;}
        var coreNodeId = notification.cause().key()
                                           .coreNodeId();
        reconciler.onSignal(new HealthSignal.SpokesmanAssignmentFailed(coreNodeId,
                                                                       value.communities(),
                                                                       value.failureReason()));
    }

    @Contract@Override public void onNodeLifecyclePut(ValuePut<NodeLifecycleKey, NodeLifecycleValue> notification) {
        if (!isLeaderGate.get()) {return;}
        var state = notification.cause().value()
                                      .state();
        if (state != NodeLifecycleState.DECOMMISSIONED) {return;}
        var nodeId = notification.cause().key()
                                       .nodeId();
        var snapshot = reconciler.currentSnapshot();
        var member = snapshot.coreMembers().get(nodeId);
        if (member == null) {return;}
        log.info("Core node {} transitioned to DECOMMISSIONED — reconciler will rebalance spokesmen on next signal",
                 nodeId);
    }
}
