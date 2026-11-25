// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.fsm;

import java.util.List;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.OwnerLifetime;


/// The node deployment FSM's one door to consensus (#3754): a write submitted after the node's [OwnerLifetime] ended is refused, so a body
/// that passed the lifetime's wrapper read before `end()` and was still running does not put its effect on the cluster.
///
/// This is the NARROWING of the lifetime's contract ("a second check on the side-effect path"), not a fence: a write submitted before the
/// lifetime ended is not recalled, and the receiver does not refuse a stale node, because the leader writes the same `NodeArtifactKey`
/// (#3754, class F, reported to the CTO). Every consensus write of the FSM goes through [NodeDeploymentContext#cluster()], which is this
/// gate, so there is one check for all of them, not one per call site.
record LifetimeGatedCluster(NodeId self, ClusterNode<KVCommand<AetherKey>> delegate, OwnerLifetime lifetime) implements ClusterNode<KVCommand<AetherKey>> {
    /// The write was refused because the node deployment lifetime had ended; nothing was submitted.
    record WriteRefused(NodeId node) implements Cause {
        static WriteRefused writeRefused(NodeId node) {
            return new WriteRefused(node);
        }

        @Override
        public String message() {
            return "Node " + node.id() + " deployment lifetime ended: consensus write refused, nothing was submitted";
        }
    }

    static LifetimeGatedCluster lifetimeGatedCluster(NodeId self,
                                                     ClusterNode<KVCommand<AetherKey>> delegate,
                                                     OwnerLifetime lifetime) {
        return new LifetimeGatedCluster(self, delegate, lifetime);
    }

    @Override
    public TopologyManager topologyManager() {
        return delegate.topologyManager();
    }

    @Override
    public Promise<Unit> start() {
        return delegate.start();
    }

    @Override
    public Promise<Unit> stop() {
        return delegate.stop();
    }

    @Override
    public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
        return lifetime.isEnded()
               ? WriteRefused.writeRefused(self).promise()
               : delegate.apply(commands);
    }
}
