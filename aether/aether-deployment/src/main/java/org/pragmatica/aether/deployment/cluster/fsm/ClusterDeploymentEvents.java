// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.VersionRoutingKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.VersionRoutingValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.topology.TopologyChangeNotification;

/// Domain event vocabulary for the [`org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager`]
/// FSM. Layered on [`ClusterFsmEvent`] so the shared cluster-lifecycle events
/// ([`ClusterFsmEvent.Shutdown`]) and the cluster-deployment-specific events below all flow through
/// the same `Fsm.dispatch` path.
///
/// Unlike the node-deployment FSM, cluster-deployment activation is driven by the
/// [`org.pragmatica.aether.slice.delegation.DelegatedComponent#activate`] /
/// [`org.pragmatica.aether.slice.delegation.DelegatedComponent#deactivate`] lifecycle calls
/// coming from the leader-election machinery — not by quorum notifications directly. The
/// [`Activate`] and [`Deactivate`] records below encode those two transitions.
///
/// Mapping from legacy [`org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager`]
/// `@MessageReceiver` entry points:
/// - `activate()` → [`Activate`].
/// - `deactivate()` → [`Deactivate`].
/// - `onAppBlueprintPut(valuePut)` → [`AppBlueprintPutReceived`].
/// - `onSliceTargetPut(valuePut)` → [`SliceTargetPutReceived`].
/// - `onVersionRoutingPut(valuePut)` → [`VersionRoutingPutReceived`].
/// - `onAppBlueprintRemove(valueRemove)` → [`AppBlueprintRemoveReceived`].
/// - `onSliceTargetRemove(valueRemove)` → [`SliceTargetRemoveReceived`].
/// - `onVersionRoutingRemove(valueRemove)` → [`VersionRoutingRemoveReceived`].
/// - `onTopologyChange(topologyChange)` → [`TopologyChangeReceived`].
/// - `onNodeLifecyclePut(valuePut)` → [`NodeLifecyclePutReceived`].
/// - `onActivationDirectivePut(valuePut)` → [`ActivationDirectivePutReceived`].
/// - `onActivationDirectiveRemove(valueRemove)` → [`ActivationDirectiveRemoveReceived`].
/// - `onNodeArtifactPut(valuePut)` → [`NodeArtifactPutReceived`].
/// - `onNodeArtifactRemove(valueRemove)` → [`NodeArtifactRemoveReceived`].
/// - `onSchemaVersionPut(valuePut)` → [`SchemaVersionPutReceived`].
public interface ClusterDeploymentEvents extends ClusterFsmEvent {

    /// Drives Dormant → Active. Emitted by the DelegatedComponent activation path when the local
    /// node becomes leader.
    record Activate() implements ClusterDeploymentEvents {}

    /// Drives Active → Dormant. Emitted by the DelegatedComponent deactivation path when the local
    /// node loses leadership.
    record Deactivate() implements ClusterDeploymentEvents {}

    record AppBlueprintPutReceived(ValuePut<AppBlueprintKey, AppBlueprintValue> valuePut) implements ClusterDeploymentEvents {}

    record SliceTargetPutReceived(ValuePut<SliceTargetKey, SliceTargetValue> valuePut) implements ClusterDeploymentEvents {}

    record VersionRoutingPutReceived(ValuePut<VersionRoutingKey, VersionRoutingValue> valuePut) implements ClusterDeploymentEvents {}

    record AppBlueprintRemoveReceived(ValueRemove<AppBlueprintKey, AppBlueprintValue> valueRemove) implements ClusterDeploymentEvents {}

    record SliceTargetRemoveReceived(ValueRemove<SliceTargetKey, SliceTargetValue> valueRemove) implements ClusterDeploymentEvents {}

    record VersionRoutingRemoveReceived(ValueRemove<VersionRoutingKey, VersionRoutingValue> valueRemove) implements ClusterDeploymentEvents {}

    record TopologyChangeReceived(TopologyChangeNotification topologyChange) implements ClusterDeploymentEvents {}

    record NodeLifecyclePutReceived(ValuePut<NodeLifecycleKey, NodeLifecycleValue> valuePut) implements ClusterDeploymentEvents {}

    record ActivationDirectivePutReceived(ValuePut<ActivationDirectiveKey, ActivationDirectiveValue> valuePut) implements ClusterDeploymentEvents {}

    record ActivationDirectiveRemoveReceived(ValueRemove<ActivationDirectiveKey, ActivationDirectiveValue> valueRemove) implements ClusterDeploymentEvents {}

    record NodeArtifactPutReceived(ValuePut<NodeArtifactKey, NodeArtifactValue> valuePut) implements ClusterDeploymentEvents {}

    record NodeArtifactRemoveReceived(ValueRemove<NodeArtifactKey, NodeArtifactValue> valueRemove) implements ClusterDeploymentEvents {}

    record SchemaVersionPutReceived(ValuePut<SchemaVersionKey, SchemaVersionValue> valuePut) implements ClusterDeploymentEvents {}
}
