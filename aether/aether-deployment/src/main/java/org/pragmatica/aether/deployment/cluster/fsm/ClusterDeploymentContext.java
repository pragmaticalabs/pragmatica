// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.pragmatica.aether.config.CommunitySizing;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
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
import org.pragmatica.lang.Option;
import org.pragmatica.statemachine.Fsm;


public final class ClusterDeploymentContext {
    private final Fsm<ClusterDeploymentState, ClusterFsmEvent> fsm;
    private final NodeId self;
    private final ClusterNode<KVCommand<AetherKey>> cluster;
    private final KVStore<AetherKey, AetherValue> kvStore;
    private final MessageRouter router;
    private final TopologyManager topologyManager;
    private final SchemaOrchestratorService schemaOrchestrator;
    private final Supplier<Set<NodeId>> coreCountedMembersSupplier;
    private final Supplier<Set<NodeId>> readyNodesSupplier;
    private final Supplier<Set<NodeId>> drainingNodesSupplier;
    private final Set<NodeId> seedNodes;
    private final DeploymentAtomicity atomicity;
    private final int coreMax;
    private final TimeSpan reconcileInterval;
    private final LongSupplier clock;
    /// Per-node membership `source` read-seam (worker-membership-spec §4.1 / D2): resolves the
    /// joining node's source label (from the membership FSM's [`MemberDescriptor#source`]) so the
    /// leader can mint/reuse a per-source community at role-assignment time. `none()` (or a blank
    /// source) means "unknown" and the caller falls back to the `"default"` source. Wired in
    /// `AetherNode` to `membershipFsmRef`; defaults to `node -> none()` in the legacy constructors
    /// so existing call sites (and tests) keep the community-less behaviour.
    private final Function<NodeId, Option<String>> memberSourceSupplier;
    /// Per-community sizing policy (worker-membership-spec §3.3 / §4.1): the `targetSize` stamped on a
    /// minted FORMING community and the `viabilityFloor` that gates its FORMING/DEGRADED → ACTIVE
    /// transition. Threaded from the node/deployment config so a small test/dev topology can run
    /// communities under the default size; legacy constructors default it to
    /// [CommunitySizing#DEFAULT] (target 100, floor 3) so existing call sites are unchanged.
    private final CommunitySizing communitySizing;
    /// #590 core-absence read-seam. Late-wired rather than constructor-injected, matching the
    /// `setDrainTargets` / `setColdBootSupplier` idiom: the collector that backs it is built well after
    /// the deployment context, and every existing call site (production and test) keeps the
    /// pre-#590 behaviour until `AetherNode` supplies the real view.
    private volatile CommunityLivenessView communityLiveness = CommunityLivenessView.unwired();
    /// #731 round 3: the leader's own local SWIM-derived alive view, read by `sweepDeadRestoredWorkers`
    /// as a second, lower-latency signal alongside the committed `GovernorAnnouncementValue` roster —
    /// a fresh worker is present here the instant SWIM observes it, closing the up-to-one-reannounce-
    /// interval gap during which a live worker can be absent from every committed announcement.
    /// Late-wired (same idiom as `communityLiveness`); defaults to an empty set so pre-#731-round-3
    /// call sites and tests keep the old committed-announcement-only sweep behaviour until
    /// `AetherNode` supplies the real `MembershipFsm`-backed view.
    private volatile Supplier<Set<NodeId>> localAliveMembersSupplier = Set::of;
    private final ClusterDeploymentState dormant;
    private final ClusterDeploymentState stopped;

    public ClusterDeploymentContext(Fsm<ClusterDeploymentState, ClusterFsmEvent> fsm,
                                    NodeId self,
                                    ClusterNode<KVCommand<AetherKey>> cluster,
                                    KVStore<AetherKey, AetherValue> kvStore,
                                    MessageRouter router,
                                    TopologyManager topologyManager,
                                    SchemaOrchestratorService schemaOrchestrator,
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
                                    Supplier<Set<NodeId>> coreCountedMembersSupplier,
                                    Supplier<Set<NodeId>> readyNodesSupplier,
                                    Supplier<Set<NodeId>> drainingNodesSupplier,
                                    Set<NodeId> seedNodes,
                                    DeploymentAtomicity atomicity,
                                    int coreMax,
                                    TimeSpan reconcileInterval,
                                    LongSupplier clock) {
        this(fsm,
             self,
             cluster,
             kvStore,
             router,
             topologyManager,
             schemaOrchestrator,
             coreCountedMembersSupplier,
             readyNodesSupplier,
             drainingNodesSupplier,
             seedNodes,
             atomicity,
             coreMax,
             reconcileInterval,
             clock,
             node -> Option.none());
    }

    public ClusterDeploymentContext(Fsm<ClusterDeploymentState, ClusterFsmEvent> fsm,
                                    NodeId self,
                                    ClusterNode<KVCommand<AetherKey>> cluster,
                                    KVStore<AetherKey, AetherValue> kvStore,
                                    MessageRouter router,
                                    TopologyManager topologyManager,
                                    SchemaOrchestratorService schemaOrchestrator,
                                    Supplier<Set<NodeId>> coreCountedMembersSupplier,
                                    Supplier<Set<NodeId>> readyNodesSupplier,
                                    Supplier<Set<NodeId>> drainingNodesSupplier,
                                    Set<NodeId> seedNodes,
                                    DeploymentAtomicity atomicity,
                                    int coreMax,
                                    TimeSpan reconcileInterval,
                                    LongSupplier clock,
                                    Function<NodeId, Option<String>> memberSourceSupplier) {
        this(fsm,
             self,
             cluster,
             kvStore,
             router,
             topologyManager,
             schemaOrchestrator,
             coreCountedMembersSupplier,
             readyNodesSupplier,
             drainingNodesSupplier,
             seedNodes,
             atomicity,
             coreMax,
             reconcileInterval,
             clock,
             memberSourceSupplier,
             CommunitySizing.DEFAULT);
    }

    public ClusterDeploymentContext(Fsm<ClusterDeploymentState, ClusterFsmEvent> fsm,
                                    NodeId self,
                                    ClusterNode<KVCommand<AetherKey>> cluster,
                                    KVStore<AetherKey, AetherValue> kvStore,
                                    MessageRouter router,
                                    TopologyManager topologyManager,
                                    SchemaOrchestratorService schemaOrchestrator,
                                    Supplier<Set<NodeId>> coreCountedMembersSupplier,
                                    Supplier<Set<NodeId>> readyNodesSupplier,
                                    Supplier<Set<NodeId>> drainingNodesSupplier,
                                    Set<NodeId> seedNodes,
                                    DeploymentAtomicity atomicity,
                                    int coreMax,
                                    TimeSpan reconcileInterval,
                                    LongSupplier clock,
                                    Function<NodeId, Option<String>> memberSourceSupplier,
                                    CommunitySizing communitySizing) {
        this.fsm = fsm;
        this.self = self;
        this.cluster = cluster;
        this.kvStore = kvStore;
        this.router = router;
        this.topologyManager = topologyManager;
        this.schemaOrchestrator = schemaOrchestrator;
        this.coreCountedMembersSupplier = coreCountedMembersSupplier;
        this.readyNodesSupplier = readyNodesSupplier;
        this.drainingNodesSupplier = drainingNodesSupplier;
        this.seedNodes = seedNodes;
        this.atomicity = atomicity;
        this.coreMax = coreMax;
        this.reconcileInterval = reconcileInterval;
        this.clock = clock;
        this.memberSourceSupplier = memberSourceSupplier;
        this.communitySizing = communitySizing;
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

    /// The joining node's membership `source` label (worker-membership-spec §4.1 / D2), used by the
    /// leader to mint/reuse a per-source community at role-assignment time. `none()` when the source
    /// is unknown (untracked member or no descriptor yet); the caller defaults such a node to the
    /// `"default"` source. A blank source is surfaced as-is and normalized by the caller.
    public Option<String> memberSource(NodeId nodeId) {
        return memberSourceSupplier.apply(nodeId);
    }

    /// Per-community sizing policy (worker-membership-spec §3.3 / §4.1): the `targetSize` stamped on a
    /// minted FORMING community and the `viabilityFloor` gating its promotion to ACTIVE. Read by the
    /// `Active` state in place of the former hardcoded constants; defaults to
    /// [CommunitySizing#DEFAULT] (target 100, floor 3).
    public CommunitySizing communitySizing() {
        return communitySizing;
    }

    /// The leader's observed community-liveness view (#590). Defaults to
    /// [CommunityLivenessView#unwired], which reports nothing absent.
    public CommunityLivenessView communityLiveness() {
        return communityLiveness;
    }

    /// Inject the observed community-liveness view. The pre-wiring default is
    /// [CommunityLivenessView#unwired]; pass that explicitly to restore it rather than clearing.
    @Contract
    public void setCommunityLiveness(CommunityLivenessView view) {
        communityLiveness = view;
    }

    /// The leader's local SWIM-derived alive-member view (#731 round 3). Defaults to an empty set
    /// (`Set::of`) until wired, matching the pre-round-3 sweep behaviour.
    public Supplier<Set<NodeId>> localAliveMembersSupplier() {
        return localAliveMembersSupplier;
    }

    /// Inject the local alive-member supplier. The pre-wiring default is `Set::of`; pass that
    /// explicitly to restore it rather than clearing.
    @Contract
    public void setLocalAliveMembersSupplier(Supplier<Set<NodeId>> supplier) {
        localAliveMembersSupplier = supplier;
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
