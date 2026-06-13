// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.slice.generation.HealthSignalSink;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.concurrent.CancellableTask;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.Fsm;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;


public final class ClusterDeploymentContext {
    private final Fsm<ClusterDeploymentState, ClusterFsmEvent> fsm;
    private final NodeId self;
    private final ClusterNode<KVCommand<AetherKey>> cluster;
    private final KVStore<AetherKey, AetherValue> kvStore;
    private final MessageRouter router;
    private final TopologyManager topologyManager;
    private final SchemaOrchestratorService schemaOrchestrator;
    private final HealthSignalSink healthSignalSink;
    private final Supplier<Set<NodeId>> coreCountedMembersSupplier;
    private final Supplier<Set<NodeId>> readyNodesSupplier;
    private final Supplier<Set<NodeId>> drainingNodesSupplier;
    private final Set<NodeId> seedNodes;
    private final DeploymentAtomicity atomicity;
    private final int coreMax;
    private final TimeSpan reconcileInterval;
    private final LongSupplier clock;
    private final ClusterDeploymentState dormant;
    private final ClusterDeploymentState stopped;

    public ClusterDeploymentContext(Fsm<ClusterDeploymentState, ClusterFsmEvent> fsm,
                                    NodeId self,
                                    ClusterNode<KVCommand<AetherKey>> cluster,
                                    KVStore<AetherKey, AetherValue> kvStore,
                                    MessageRouter router,
                                    TopologyManager topologyManager,
                                    SchemaOrchestratorService schemaOrchestrator,
                                    HealthSignalSink healthSignalSink,
                                    Supplier<Set<NodeId>> coreCountedMembersSupplier,
                                    Supplier<Set<NodeId>> readyNodesSupplier,
                                    Supplier<Set<NodeId>> drainingNodesSupplier,
                                    Set<NodeId> seedNodes,
                                    DeploymentAtomicity atomicity,
                                    int coreMax,
                                    TimeSpan reconcileInterval) {
        this(fsm,
             self,
             cluster,
             kvStore,
             router,
             topologyManager,
             schemaOrchestrator,
             healthSignalSink,
             coreCountedMembersSupplier,
             readyNodesSupplier,
             drainingNodesSupplier,
             seedNodes,
             atomicity,
             coreMax,
             reconcileInterval,
             System::currentTimeMillis);
    }

    public ClusterDeploymentContext(Fsm<ClusterDeploymentState, ClusterFsmEvent> fsm,
                                    NodeId self,
                                    ClusterNode<KVCommand<AetherKey>> cluster,
                                    KVStore<AetherKey, AetherValue> kvStore,
                                    MessageRouter router,
                                    TopologyManager topologyManager,
                                    SchemaOrchestratorService schemaOrchestrator,
                                    HealthSignalSink healthSignalSink,
                                    Supplier<Set<NodeId>> coreCountedMembersSupplier,
                                    Supplier<Set<NodeId>> readyNodesSupplier,
                                    Supplier<Set<NodeId>> drainingNodesSupplier,
                                    Set<NodeId> seedNodes,
                                    DeploymentAtomicity atomicity,
                                    int coreMax,
                                    TimeSpan reconcileInterval,
                                    LongSupplier clock) {
        this.fsm = fsm;
        this.self = self;
        this.cluster = cluster;
        this.kvStore = kvStore;
        this.router = router;
        this.topologyManager = topologyManager;
        this.schemaOrchestrator = schemaOrchestrator;
        this.healthSignalSink = healthSignalSink;
        this.coreCountedMembersSupplier = coreCountedMembersSupplier;
        this.readyNodesSupplier = readyNodesSupplier;
        this.drainingNodesSupplier = drainingNodesSupplier;
        this.seedNodes = seedNodes;
        this.atomicity = atomicity;
        this.coreMax = coreMax;
        this.reconcileInterval = reconcileInterval;
        this.clock = clock;
        this.dormant = new ClusterDeploymentState.Dormant(this);
        this.stopped = new ClusterDeploymentState.Stopped(this);
    }

    public long nowMs() {
        return clock.getAsLong();
    }

    public Fsm<ClusterDeploymentState, ClusterFsmEvent> fsm() {
        return fsm;
    }

    @Contract
    public void dispatch(ClusterFsmEvent event) {
        fsm.dispatch(event);
    }

    public ClusterDeploymentState dormant() {
        return dormant;
    }

    public ClusterDeploymentState stopped() {
        return stopped;
    }

    public ClusterDeploymentState.Active newActive() {
        return new ClusterDeploymentState.Active(this,
                                                 new ConcurrentHashMap<>(),
                                                 new ConcurrentHashMap<>(),
                                                 new ConcurrentHashMap<>(),
                                                 ConcurrentHashMap.newKeySet(),
                                                 new ConcurrentHashMap<>(),
                                                 new ConcurrentHashMap<>(),
                                                 ConcurrentHashMap.newKeySet(),
                                                 ConcurrentHashMap.newKeySet(),
                                                 ConcurrentHashMap.newKeySet(),
                                                 new ConcurrentHashMap<>(),
                                                 new ConcurrentHashMap<>(),
                                                 new AtomicInteger(0),
                                                 new AtomicBoolean(false),
                                                 CancellableTask.cancellableTask());
    }

    public NodeId self() {
        return self;
    }

    public ClusterNode<KVCommand<AetherKey>> cluster() {
        return cluster;
    }

    public KVStore<AetherKey, AetherValue> kvStore() {
        return kvStore;
    }

    public MessageRouter router() {
        return router;
    }

    public TopologyManager topologyManager() {
        return topologyManager;
    }

    public SchemaOrchestratorService schemaOrchestrator() {
        return schemaOrchestrator;
    }

    public HealthSignalSink healthSignalSink() {
        return healthSignalSink;
    }

    public Supplier<Set<NodeId>> coreCountedMembersSupplier() {
        return coreCountedMembersSupplier;
    }

    public Supplier<Set<NodeId>> readyNodesSupplier() {
        return readyNodesSupplier;
    }

    /// Membership-v2: nodes currently reporting `NodeReportedState.DRAINING` via the metrics
    /// pong — the real, node-authoritative draining set. Replaces the synthetic
    /// `CoreMember.lifecycle() == DRAINING` projection. Wired in `AetherNode` to the pong
    /// readiness snapshot.
    public Supplier<Set<NodeId>> drainingNodesSupplier() {
        return drainingNodesSupplier;
    }

    public Set<NodeId> seedNodes() {
        return seedNodes;
    }

    public DeploymentAtomicity atomicity() {
        return atomicity;
    }

    public int coreMax() {
        return coreMax;
    }

    public TimeSpan reconcileInterval() {
        return reconcileInterval;
    }

    public boolean isActive() {
        return fsm.current() instanceof ClusterDeploymentState.Active;
    }
}
