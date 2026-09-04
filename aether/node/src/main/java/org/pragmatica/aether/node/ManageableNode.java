// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.pragmatica.aether.worker.isolation.CoreAbsenceSnapshot;
import org.pragmatica.aether.api.ClusterEventAggregator;
import org.pragmatica.aether.backup.BackupService;
import org.pragmatica.aether.controller.ControlLoop;
import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.cluster.BlueprintService;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.drain.InFlightRequestTracker;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.deployment.membership.ntt.QuorumLossSnapshot;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.node.journal.TransitionJournal;
import org.pragmatica.aether.node.lifecycle.NodeLifecycle;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
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
import org.pragmatica.aether.node.StorageFactory;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.node.stream.StreamConsumerManager;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.StreamReadRouter;
import org.pragmatica.aether.stream.StreamWriteRouter;
import org.pragmatica.aether.stream.segment.SegmentIndex;
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

    /// The node's sealed-segment index (#634-3/4): per-partition [SegmentIndex.SegmentRef] ranges, the
    /// third local retention-floor source beside the ring tail and the WAL watermark. The default is an
    /// EMPTY index — for HAND-WRITTEN test implementations only ("no segments known" is the honest
    /// answer for a fake with no stream storage); JDK dynamic proxies never reach it — they route
    /// default methods to their handler like any other. The production
    /// record supplies the live index as a component, so forgetting to wire it is a compile error, not
    /// a silent empty.
    default SegmentIndex streamSegmentIndex() {
        return new SegmentIndex();
    }

    StreamReadRouter streamReadRouter();

    /// Owner-routed publish path — the write-side mirror of [#streamReadRouter]. Since #265 made
    /// non-owner nodes metadata-only, a management publish landing on an arbitrary node must reach the
    /// partition owner rather than fail `PARTITION_NOT_LOCAL` on a local append. The production node
    /// record supplies the fully-wired router (forward client + HRW owner resolver); the default keeps
    /// `ManageableNode` test proxies compiling with a local-only writer.
    default StreamWriteRouter streamWriteRouter() {
        return StreamWriteRouter.localOnly(streamPartitionManager());
    }

    ConsumerGroupCoordinator consumerGroupCoordinator();
    ConsumerGroupRegistry consumerGroupRegistry();

    /// Declarative `[streams.X]` consumer manager (#488). The production node record supplies the
    /// wired manager; the default keeps `ManageableNode` test proxies compiling with an inert one
    /// that truthfully reports no declared consumers rather than fabricating any.
    default StreamConsumerManager streamConsumerManager() {
        return StreamConsumerManager.inactive();
    }

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

    /// SWIM-under-concurrent-loss observability — this node's LOCAL quorum-loss drain-readiness
    /// view (strict member count, simple-majority threshold, below-threshold flag, armed latch),
    /// or empty before the per-node `QuorumLossDetector` is wired. PER-NODE local state, never
    /// leader-forwarded: querying a specific survivor returns THAT node's own detector view, so
    /// `GET /api/cluster/membership` can answer "is this survivor's self-drain window armed and
    /// below quorum?" without log-scraping. Default `Option.none()` keeps `ManageableNode` test
    /// proxies compiling; the production node record supplies the live view.
    default Option<QuorumLossSnapshot> quorumLossSnapshot() {
        return Option.none();
    }

    /// #590 — this node's LOCAL core-absence view: has it ever heard the core, how long since the last
    /// accepted `ClusterSyncPing`, and how long until it dissolves itself. The community-tier twin of
    /// [#quorumLossSnapshot], and PER-NODE for the same reason — plus a sharper one. A node nearing its
    /// core-absence fence is by definition one the core is losing contact with, so a leader-forwarded
    /// answer is unobtainable during exactly the incident it describes; an operator polls the suspect
    /// node directly. Default `Option.none()` keeps `ManageableNode` test proxies compiling.
    default Option<CoreAbsenceSnapshot> coreAbsenceSnapshot() {
        return Option.none();
    }

    /// #345 item 1f — the node's live per-ownership-domain epoch high-water table (the DATA-plane
    /// mirror of the committed ownership records). `GET /api/ownership/{domain}` reads its
    /// [OwnershipEpochHighWater#snapshot] to surface each entry's LOCAL `highWater` epoch and the
    /// `fenced` deposed-owner-window flag (local high-water strictly after the committed owner
    /// epoch). PER-NODE local state, never leader/owner-forwarded — each node answers from its own
    /// table. Default `Option.none()` keeps `ManageableNode` test proxies compiling; the production
    /// node record supplies the live table.
    default Option<OwnershipEpochHighWater> ownershipEpochHighWater() {
        return Option.none();
    }

    Option<CertificateRenewalScheduler> certRenewalScheduler();  /// Runtime TLS posture. `true` when the node's app-HTTP server is bound with TLS
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
    /// #543: `SchemaRoutes` needs the orchestrator itself — not just KV read/write — for undo and
    /// baseline to run through the same artifact-resolution, single-flight fence, and
    /// `AetherSchemaManager` call path forward migrate already uses, instead of writing a status
    /// record no reconciler tick ever acts on.
    SchemaOrchestratorService schemaOrchestrator();
    int managementPort();
    int appHttpPort();
    long uptimeSeconds();
    List<NodeId> initialTopology();
    TopologyConfig topologyConfig();
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
