// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.pragmatica.aether.api.ClusterEventAggregator;
import org.pragmatica.aether.backup.BackupService;
import org.pragmatica.aether.controller.ControlLoop;
import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.cluster.BlueprintService;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.drain.InFlightRequestTracker;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.node.journal.TransitionJournal;
import org.pragmatica.aether.node.lifecycle.NodeLifecycle;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.aether.http.AppHttpServer;
import org.pragmatica.aether.http.HttpRouteRegistry;
import org.pragmatica.aether.metrics.ComprehensiveSnapshotCollector;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.metrics.artifact.ArtifactMetricsCollector;
import org.pragmatica.aether.metrics.deployment.DeploymentMetricsCollector;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.resource.artifact.ArtifactStore;
import org.pragmatica.aether.resource.artifact.MavenProtocolHandler;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.HealthSignalSink;
import org.pragmatica.aether.node.StorageFactory;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.StreamReadRouter;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator;
import org.pragmatica.aether.stream.consumer.ConsumerGroupRegistry;
import org.pragmatica.aether.ttm.TTMManager;
import org.pragmatica.aether.update.AbTestManager;
import org.pragmatica.aether.update.DeploymentManager;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.dht.DHTNode;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.Message;
import org.pragmatica.net.tcp.security.CertificateRenewalScheduler;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;


public interface ManageableNode {
    NodeId self();
    KVStore<AetherKey, AetherValue> kvStore();
    SliceStore sliceStore();
    ClusterSyncCollector metricsCollector();
    DeploymentMetricsCollector deploymentMetricsCollector();
    ControlLoop controlLoop();
    BlueprintService blueprintService();
    MavenProtocolHandler mavenProtocolHandler();
    ArtifactStore artifactStore();
    TopologyManager topologyManager();
    /// #114 W2.0 — exposes the per-node authoritative `MembershipFsm` so management HTTP
    /// routes can read live membership (members/health/quiescence) directly, replacing reads
    /// of the generation snapshot. Additive accessor; snapshot accessors remain until a later wave.
    MembershipFsm membershipFsm();

    /// Wave-1 Enrichment A (cluster-topology-overhaul spec) — exposes the per-node transition
    /// journal (every `MembershipFsm` + `PeerState` transition, bounded ring buffer per layer)
    /// so `GET /api/cluster/journal` can dump it. Diagnostic-only. Default inert journal keeps
    /// test proxies compiling; the production node record supplies the live instance.
    default TransitionJournal transitionJournal() {
        return TransitionJournal.inert();
    }

    /// #114 — the node's CURRENT generation epoch. On the leader this is the locally-minted
    /// epoch (`leaderTerm`:`generationCounter`); on followers it is the observed ping epoch.
    /// The leader never receives its own pings, so its `metricsCollector().observedEpoch()`
    /// stays at `0:0` forever — gen-route, await-quiesced and NDM MUST read this instead.
    Epoch currentGenerationEpoch();
    InvocationMetricsCollector invocationMetrics();
    DeploymentManager deploymentManager();
    AbTestManager abTestManager();
    AppHttpServer appHttpServer();
    HttpRouteRegistry httpRouteRegistry();
    TTMManager ttmManager();
    ComprehensiveSnapshotCollector snapshotCollector();
    ArtifactMetricsCollector artifactMetricsCollector();
    DeploymentMap deploymentMap();
    ClusterEventAggregator eventAggregator();
    BackupService backupService();
    StreamPartitionManager streamPartitionManager();
    StreamReadRouter streamReadRouter();
    ConsumerGroupCoordinator consumerGroupCoordinator();
    ConsumerGroupRegistry consumerGroupRegistry();
    org.pragmatica.aether.slice.stream.StreamNamespacesService streamNamespacesService();
    Fn1<Result<NodeId>, TaskGroup> taskGroupOwnerResolver();
    Map<String, StorageFactory.StorageSetup> storageSetups();
    Option<ClusterTopologyManager> clusterTopologyManager();
    /// Diagnostic/test observable — the leader `PresenceSampler` peak (monotonic high-water mark
    /// of the debounced stable member-set size ever observed). The NTT `LeaderReconciler` latches
    /// its `reachedFullMembership` cold-start guard off THIS value (`peak >= configuredCoreCount`),
    /// NOT off the faster-latching `MembershipFsm.coreCountedMembers()` count — a probe that must
    /// wait until provisioning is armed should gate on this reaching the cluster size before
    /// inducing a deficit. 1 (self) until peers are admitted (K_UP consecutive healthy samples).
    int observedPeakMembership();
    /// #336 observability — assembled provisioning diagnostics (the leader reconcile decision
    /// snapshot + the provisioning circuit-breaker state + the last provisioning failure), or
    /// empty when this node is not the leader or owns no `ClusterTopologyManager`. Lets the
    /// management API answer "why is this deficit not being filled?" without log-scraping.
    /// Default `Option.none()` keeps `ManageableNode` test proxies compiling; the production node
    /// record supplies the live view.
    default Option<ProvisioningDiagnostics> provisioningDiagnostics() {
        return Option.none();
    }
    Option<CertificateRenewalScheduler> certRenewalScheduler();
    /// Runtime TLS posture. `true` when the node's app-HTTP server is bound with TLS
    /// (equivalent to `AetherNodeConfig.tls().isPresent()` — i.e. `AetherConfig.tlsEnabled()`
    /// was true at startup and a `CertificateProvider` resolved). Surfaced through
    /// `GET /api/certificates` so integration tooling can assert active TLS without
    /// inferring it from the `renewalStatus` placeholder. See
    /// `aether/docs/internal/audits/integration-test-audit-2026-05-21.md` §2.2.
    boolean tlsEnabled();
    int connectedNodeCount();
    Map<String, Number> transportMetrics();
    Set<NodeId> connectedPeerIds();
    boolean isLeader();
    boolean isReady();
    Option<NodeId> leader();
    <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands);
    int managementPort();
    int appHttpPort();
    long uptimeSeconds();
    List<NodeId> initialTopology();
    TopologyConfig topologyConfig();
    HealthSignalSink healthSignalSink();
    InFlightRequestTracker inFlightRequestTracker();
    NodeLifecycle nodeLifecycle();
    /// RC1 Step 4 — exposes the node's canonical Hybrid Logical Clock so request-handling
    /// routes (e.g., `NodeLifecycleRoutes` constructing operator events) can stamp events
    /// with the same clock, preserving causal ordering across the admission path.
    HlcClock hlcClock();
    /// P-NEW-B / P-NEW-F (RC1, 2026-05-21) — exposes the node's local DHT client so management
    /// routes (`DhtRoutes`) can issue versioned puts with explicit HLC for deterministic
    /// version-conflict tests, and surface the active replication map for operator inspection.
    /// `Option.none()` only in tests that wire a `ManageableNode` proxy without DHT.
    Option<DHTClient> dhtClient();
    /// P-NEW-B / P-NEW-F (RC1, 2026-05-21) — exposes the local `DHTNode` for storage iteration
    /// (`storage().keys()` powers the replication-map inspector) and direct versioned local
    /// puts (`putLocalVersioned`, used by the dev-mode `/api/dht/inject` test hook to write a
    /// value with an explicit HLC, bypassing the live-clock advancement the regular `put` path
    /// performs). `Option.none()` only in tests that wire a `ManageableNode` proxy without DHT.
    Option<DHTNode> dhtNode();
    /// H.1 (spec §H): derived cluster-membership view. Computed from the local SWIM
    /// `HealthSnapshot` — SWIM is authoritative for "alive". Reader-side replacement for
    /// the retired membership FSM snapshot. Cheap to call repeatedly — recomputes on each query.
    org.pragmatica.aether.deployment.membership.view.MembershipView membershipView();
    /// Post-E.8 (spec §7.2): unified `ClusterPhase` accessor that returns the value
    /// derived by `ClusterPhaseView.compute()`. Status routes and any dashboard consumer
    /// should call this rather than reading the KV atom directly.
    Supplier<AetherValue.ClusterPhase> clusterPhaseSupplier();

    @SuppressWarnings("JBCT-RET-01")
    void route(Message message);
}
