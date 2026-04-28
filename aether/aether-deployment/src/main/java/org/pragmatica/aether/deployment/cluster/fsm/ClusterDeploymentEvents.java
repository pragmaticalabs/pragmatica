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


public interface ClusterDeploymentEvents extends ClusterFsmEvent {
    record Activate() implements ClusterDeploymentEvents{}

    record Deactivate() implements ClusterDeploymentEvents{}

    record AppBlueprintPutReceived(ValuePut<AppBlueprintKey, AppBlueprintValue> valuePut) implements ClusterDeploymentEvents{}

    record SliceTargetPutReceived(ValuePut<SliceTargetKey, SliceTargetValue> valuePut) implements ClusterDeploymentEvents{}

    record VersionRoutingPutReceived(ValuePut<VersionRoutingKey, VersionRoutingValue> valuePut) implements ClusterDeploymentEvents{}

    record AppBlueprintRemoveReceived(ValueRemove<AppBlueprintKey, AppBlueprintValue> valueRemove) implements ClusterDeploymentEvents{}

    record SliceTargetRemoveReceived(ValueRemove<SliceTargetKey, SliceTargetValue> valueRemove) implements ClusterDeploymentEvents{}

    record VersionRoutingRemoveReceived(ValueRemove<VersionRoutingKey, VersionRoutingValue> valueRemove) implements ClusterDeploymentEvents{}

    record TopologyChangeReceived(TopologyChangeNotification topologyChange) implements ClusterDeploymentEvents{}

    record NodeLifecyclePutReceived(ValuePut<NodeLifecycleKey, NodeLifecycleValue> valuePut) implements ClusterDeploymentEvents{}

    record ActivationDirectivePutReceived(ValuePut<ActivationDirectiveKey, ActivationDirectiveValue> valuePut) implements ClusterDeploymentEvents{}

    record ActivationDirectiveRemoveReceived(ValueRemove<ActivationDirectiveKey, ActivationDirectiveValue> valueRemove) implements ClusterDeploymentEvents{}

    record NodeArtifactPutReceived(ValuePut<NodeArtifactKey, NodeArtifactValue> valuePut) implements ClusterDeploymentEvents{}

    record NodeArtifactRemoveReceived(ValueRemove<NodeArtifactKey, NodeArtifactValue> valueRemove) implements ClusterDeploymentEvents{}

    record SchemaVersionPutReceived(ValuePut<SchemaVersionKey, SchemaVersionValue> valuePut) implements ClusterDeploymentEvents{}
}
