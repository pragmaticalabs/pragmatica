// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.fsm;

import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;

/// Domain event vocabulary for the [`org.pragmatica.aether.deployment.node.NodeDeploymentManager`]
/// FSM. Layered on [`ClusterFsmEvent`] so the shared cluster-lifecycle events
/// ([`ClusterFsmEvent.QuorumEstablished`], [`ClusterFsmEvent.QuorumDisappeared`],
/// [`ClusterFsmEvent.Shutdown`]) and the node-deployment-specific events below all flow through
/// the same `Fsm.dispatch` path.
///
/// Mapping from legacy [`NodeDeploymentManager`] `@MessageReceiver` entry points:
/// - `onQuorumStateChange(ESTABLISHED)` → [`ClusterFsmEvent.QuorumEstablished`].
/// - `onQuorumStateChange(DISAPPEARED)` → [`ClusterFsmEvent.QuorumDisappeared`].
/// - `onNodeArtifactPut(valuePut)` → [`NodeArtifactPutReceived(valuePut)`].
/// - `onNodeArtifactRemove(valueRemove)` → [`NodeArtifactRemoveReceived(valueRemove)`].
/// - `onNodeRoutesPut(valuePut)` → [`NodeRoutesPutReceived(valuePut)`].
///
/// The two `onNodeLifecycle*` receivers deliberately stay outside the FSM event channel: they
/// drive non-state-transitioning side effects (shutdown callback, lifecycle re-registration) that
/// read — but do not mutate — the FSM state.
public interface NodeDeploymentEvents extends ClusterFsmEvent {

    /// A NodeArtifactKey value was put into the KV-Store. The FSM's `Active` state consumes this to
    /// drive slice state transitions; `Dormant` and `Stopped` ignore it.
    record NodeArtifactPutReceived(ValuePut<NodeArtifactKey, NodeArtifactValue> valuePut) implements NodeDeploymentEvents {}

    /// A NodeArtifactKey value was removed from the KV-Store. The FSM's `Active` state consumes
    /// this to force-cleanup the corresponding slice when it was ACTIVE; other states ignore it.
    record NodeArtifactRemoveReceived(ValueRemove<NodeArtifactKey, NodeArtifactValue> valueRemove) implements NodeDeploymentEvents {}

    /// A NodeRoutesKey value was put into the KV-Store. The FSM's `Active` state uses this to
    /// observe cross-node routing-epoch acks and fast-transition ROUTING → ACTIVE; other states
    /// ignore it.
    record NodeRoutesPutReceived(ValuePut<NodeRoutesKey, NodeRoutesValue> valuePut) implements NodeDeploymentEvents {}
}
