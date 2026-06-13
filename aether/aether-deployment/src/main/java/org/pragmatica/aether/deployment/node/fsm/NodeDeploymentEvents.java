// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.fsm;

import org.pragmatica.aether.deployment.drain.DrainReason;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;


public interface NodeDeploymentEvents extends ClusterFsmEvent {
    record NodeArtifactPutReceived(ValuePut<NodeArtifactKey, NodeArtifactValue> valuePut) implements NodeDeploymentEvents {}

    record NodeArtifactRemoveReceived(ValueRemove<NodeArtifactKey, NodeArtifactValue> valueRemove) implements NodeDeploymentEvents {}

    record NodeRoutesPutReceived(ValuePut<NodeRoutesKey, NodeRoutesValue> valuePut) implements NodeDeploymentEvents {}

    record LeavingRequested(DrainReason reason) implements NodeDeploymentEvents {}
}
