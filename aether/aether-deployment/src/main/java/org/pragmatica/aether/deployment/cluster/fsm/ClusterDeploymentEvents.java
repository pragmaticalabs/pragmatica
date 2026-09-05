// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.VersionRoutingKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.VersionRoutingValue;
import org.pragmatica.aether.deployment.membership.fsm.WorkerJoinDecision;
import org.pragmatica.aether.deployment.membership.fsm.WorkerLeaveDecision;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.TransportObservation;


public interface ClusterDeploymentEvents extends ClusterFsmEvent {
    record Activate() implements ClusterDeploymentEvents {}

    record Deactivate() implements ClusterDeploymentEvents {}

    record AppBlueprintPutReceived(ValuePut<AppBlueprintKey, AppBlueprintValue> valuePut) implements ClusterDeploymentEvents {}

    record SliceTargetPutReceived(ValuePut<SliceTargetKey, SliceTargetValue> valuePut) implements ClusterDeploymentEvents {}

    record VersionRoutingPutReceived(ValuePut<VersionRoutingKey, VersionRoutingValue> valuePut) implements ClusterDeploymentEvents {}

    record AppBlueprintRemoveReceived(ValueRemove<AppBlueprintKey, AppBlueprintValue> valueRemove) implements ClusterDeploymentEvents {}

    record SliceTargetRemoveReceived(ValueRemove<SliceTargetKey, SliceTargetValue> valueRemove) implements ClusterDeploymentEvents {}

    record VersionRoutingRemoveReceived(ValueRemove<VersionRoutingKey, VersionRoutingValue> valueRemove) implements ClusterDeploymentEvents {}

    record MembershipDecisionReceived(MembershipDecision decision) implements ClusterDeploymentEvents {}

    /// The non-core join channel (#728) — a worker reached FSM Member and needs a role assignment.
    /// Separate from [`MembershipDecisionReceived`] because `MembershipDecision` is the core
    /// topology stream and a worker must never travel on it.
    record WorkerJoinReceived(WorkerJoinDecision decision) implements ClusterDeploymentEvents {}

    /// The non-core leave channel (#731), symmetric to [`WorkerJoinReceived`] — a departed worker's
    /// REMOVED edge never reaches `MembershipDecisionReceived` (workers never enter the core
    /// baseline), so without this arm `handleNodeRemoval` was unreachable for a dead worker and its
    /// allocation-pool slot and KV footprint lingered forever.
    record WorkerLeaveReceived(WorkerLeaveDecision decision) implements ClusterDeploymentEvents {}

    record SelfShutdownReceived(TransportObservation.SelfShutdown selfShutdown) implements ClusterDeploymentEvents {}

    record ActivationDirectivePutReceived(ValuePut<ActivationDirectiveKey, ActivationDirectiveValue> valuePut) implements ClusterDeploymentEvents {}

    record ActivationDirectiveRemoveReceived(ValueRemove<ActivationDirectiveKey, ActivationDirectiveValue> valueRemove) implements ClusterDeploymentEvents {}

    record NodeArtifactPutReceived(ValuePut<NodeArtifactKey, NodeArtifactValue> valuePut) implements ClusterDeploymentEvents {}

    record NodeArtifactRemoveReceived(ValueRemove<NodeArtifactKey, NodeArtifactValue> valueRemove) implements ClusterDeploymentEvents {}

    record SchemaVersionPutReceived(ValuePut<SchemaVersionKey, SchemaVersionValue> valuePut) implements ClusterDeploymentEvents {}

    /// #731 round 3: a committed reannouncement is exactly the signal `sweepDeadRestoredWorkers`
    /// reads (`observedCommunityMembers`), so reacting to it directly closes the gap between a
    /// governor's `tickReannounce` write landing and the next scheduled recheck — instead of
    /// waiting on the one-shot `deferredTopologyRecheck` timer alone.
    record GovernorAnnouncementPutReceived(ValuePut<GovernorAnnouncementKey, GovernorAnnouncementValue> valuePut) implements ClusterDeploymentEvents {}
}
