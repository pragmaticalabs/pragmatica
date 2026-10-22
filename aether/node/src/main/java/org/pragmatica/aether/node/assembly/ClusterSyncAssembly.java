// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.metrics.ClusterSyncPongSignalFan;
import org.pragmatica.aether.metrics.ClusterSyncScheduler;
import org.pragmatica.aether.metrics.DrainCommandRegistry;
import org.pragmatica.aether.metrics.NodeReportedState;
import org.pragmatica.aether.metrics.NodeReportedStateHolder;
import org.pragmatica.aether.metrics.PeriodicObservationConfig;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.metrics.observation.PeerObservationStore;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.AetherNode.EpochSources;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.aether.node.LeaderTerm;
import org.pragmatica.aether.slice.*;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.*;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.SharedScheduler;

import static org.pragmatica.aether.node.AetherNode.configuredWorker;
import static org.pragmatica.aether.node.AetherNode.nodesReporting;
import static org.pragmatica.aether.node.AetherNode.runtimeReady;


/// The cluster sync unit of the node assembly: the metrics collector with its pong fan, the node-reported state and drain
/// command registry, the sync scheduler, the leader-driven generation bump and the readiness sweep, and the readers and
/// appliers of the committed cluster config. Moved out of `AetherNode` without change; the root calls the phases in the
/// order it always ran them.
public sealed interface ClusterSyncAssembly {
    /// What `clusterSync` reads from the assembly built so far.
    record ClusterSyncInputs(AetherNodeConfig config,
                             KVStore<AetherKey, AetherValue> kvStore,
                             RabiaNode<KVCommand<AetherKey>> clusterNode,
                             LeaderTerm leaderTerm,
                             AetherNode.EpochSources epochSources,
                             GenerationSnapshotSource snapshotSource,
                             AtomicReference<ClusterSyncCollector> metricsCollectorRef,
                             InvocationMetricsCollector invocationMetrics,
                             AtomicReference<Supplier<Set<NodeId>>> cdmReadyNodesRef,
                             AtomicReference<Supplier<Set<NodeId>>> cdmDrainingNodesRef,
                             Consumer<Supplier<ScheduledFuture<?>>> defer) {}

    /// What `clusterSync` hands back for the rest of the assembly.
    record ClusterSyncOutputs(ClusterSyncCollector metricsCollector,
                              PeerObservationStore peerObservationStore,
                              BooleanSupplier isLeaderSupplier,
                              AtomicReference<BooleanSupplier> workerProjectionFreshRef,
                              NodeReportedStateHolder nodeReportedStateHolder,
                              DrainCommandRegistry drainCommandRegistry,
                              ClusterSyncPongSignalFan pongSignalFan,
                              Supplier<Long> rabiaTermSupplier,
                              Supplier<Epoch> leaderEpochSupplier,
                              ClusterSyncScheduler metricsScheduler,
                              Supplier<Epoch> currentGenerationEpochSupplier,
                              Supplier<Option<AetherValue.ClusterConfigValue>> clusterConfigReader,
                              Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> clusterCommandApplier,
                              GenerationSnapshotSource leaderAwareSnapshotSource) {}

    /// The metrics collector, pong fan, node-reported state, drain command registry, the sync scheduler, the deferred readiness and generation ticks and the cluster config reader and applier.
    static ClusterSyncOutputs clusterSync(ClusterSyncInputs in) {
        var config = in.config();
        var kvStore = in.kvStore();
        var clusterNode = in.clusterNode();
        var leaderTerm = in.leaderTerm();
        var epochSources = in.epochSources();
        var snapshotSource = in.snapshotSource();
        var metricsCollectorRef = in.metricsCollectorRef();
        var invocationMetrics = in.invocationMetrics();
        var cdmReadyNodesRef = in.cdmReadyNodesRef();
        var cdmDrainingNodesRef = in.cdmDrainingNodesRef();
        var defer = in.defer();
        var metricsCollector = ClusterSyncCollector.clusterSyncCollector(config.self(),
                                                                         clusterNode.network(),
                                                                         config.timeouts()
                                                                               .observability()
                                                                               .metricsSlidingWindow()
                                                                               .millis());
        // Publish the collector into the deferred holder captured by the leader-election FSM's
        // leader-acting lease predicate (built in createNode, before this collector existed). From
        // now on the FSM's Led-tenure lease reads real leader-ping freshness via
        // `metricsCollector.hasAuthoritativeReadiness()` instead of the lease-neutral default.
        metricsCollectorRef.set(metricsCollector);
        metricsCollector.setInvocationMetricsProvider(invocationMetrics);
        metricsCollector.recordCustom("mgmt.port", config.managementPort());
        var peerObservationStore = org.pragmatica.aether.metrics.observation.PeerObservationStore.peerObservationStore();
        // Step 1: periodic-observation config (5s emission cadence, 15s aggregator
        // TTL, cap floor 64). The store's cap supplier is wired by ClusterSyncContext
        // and computes `max(capFloor, peers × 4)` per push — sized to handle one
        // full emission cycle (N-1 observations per node per period) plus per-peer
        // transition burst.
        var periodicConfig = PeriodicObservationConfig.defaultConfig();
        BooleanSupplier isLeaderSupplier = clusterNode.leaderManager()::isLeader;
        // Membership is presence-derived (SWIM/QUIC via NTT); node readiness/drain is
        // heartbeat-reported and leader-cached — never stored in or committed to the KV-Store.
        // (The former leader-side reachability aggregator that folded a separate peer set is
        // deleted; see the P3 note immediately below.)
        // P3 (membership unification): the leader-side ReachabilityAggregator is deleted —
        // SWIM (fed by QUIC hints) is the single liveness signal, so the pong-derived
        // reachability fold (and the KV-tracked-peers quorum-N it used) is redundant. The
        // metricsCollector local-snapshot supplier is left unset (defaults to none()).
        // Membership v2 — the leader-side `ClusterSyncPongSignalFan` no longer drives a
        // readyCandidate→ForceOnDuty path (the FSM is gone); readiness is node-reported on the
        // control-heartbeat pong and consumed by the CDM allocatable gate (B4).
        ClusterSyncPongSignalFan.ReadyCandidateSink readyCandidateSink = (sender, candidate) -> {};
        // B1 (membership v2 §7.5): node-reported readiness reads a LIVE consensus-active LEVEL on
        // every pong via `clusterNode::isActive` — the SAME `RabiaEngine::isActive` accessor leader
        // election wires as its `consensusReadySupplier` (RabiaNode.isActive -> consensus().isActive()).
        // Sampling the level each pong self-heals: the former edge-cached flag could stick SYNCING
        // when a PASSIVE edge was not followed by a fresh ACTIVE edge.
        var workerProjectionFreshRef = new AtomicReference<java.util.function.BooleanSupplier>(() -> !configuredWorker(config));
        var nodeReportedStateHolder = NodeReportedStateHolder.nodeReportedStateHolder(() -> runtimeReady(clusterNode) && workerProjectionFreshRef.get()
                                                                                                                                                 .getAsBoolean());
        var drainCommandRegistry = DrainCommandRegistry.drainCommandRegistry();

        metricsCollector.setNodeReportedStateSupplier(nodeReportedStateHolder::current);
        // Incarnation supplier is wired to the SWIM self-incarnation below, once
        // `swimHealthDetector` is constructed (single `(NodeId, incarnation)` authority).
        var pongSignalFan = ClusterSyncPongSignalFan.clusterSyncPongSignalFan(clusterNode.leaderManager(),
                                                                              readyCandidateSink);

        metricsCollector.setPongSignalFan(pongSignalFan);
        // Readiness-broadcast (failover-readability): wire the leader manager + clock + ping interval
        // so the collector serves the authoritative fan view on the leader and the cached leader view
        // on followers (and so `/api/nodes/lifecycle` returns 503 + leader hint on a cold follower
        // instead of a misleading `200 []`).
        metricsCollector.setReadinessViewContext(clusterNode.leaderManager(),
                                                 System::nanoTime,
                                                 config.timeouts().cluster().pingInterval());
        // B3 (membership v2 §7.5.5): the leader readiness view self-cleans via a periodic stale-sweep
        // (catches the QUIC-open-but-silent black-hole case, where no disconnect event fires). Clean
        // departures are evicted faster by the routed TransportObservation.PeerDisconnected route below.
        // The map is leader-only (fanIfLeader-gated), so the sweep is a no-op on followers.
        var readinessSweepMaxAgeNanos = config.timeouts().cluster().pingInterval().nanos() * 3;

        defer.accept(() -> SharedScheduler.scheduleAtFixedRate(() -> pongSignalFan.sweepStale(readinessSweepMaxAgeNanos),
                                                               config.timeouts().cluster().pingInterval(),
                                                               config.timeouts().cluster().pingInterval()));
        Supplier<Long> rabiaTermSupplier = leaderTerm::current;
        // #114 W1: the cluster-sync ping carries a monotonic generation counter. Each node bumps its
        // OWN counter only while it is leader — ONCE PER pingInterval (1s default), so the counter is
        // a LEADERSHIP-TENURE TICK, not an event count: "1:4254" means rabiaTerm 1 with ~71 minutes of
        // uninterrupted leadership, which is ordinary (#634 follow-up — this exact value was once
        // investigated as an anomaly because the per-interval semantics were written down nowhere).
        // The supplier merely READS it (it is consumed in four contexts; incrementing inside would
        // over-count). No reset is needed: Epoch ordering is term-dominant, and the term is the
        // committed LeaderValue.viewSequence of this node's election (S28, see LeaderTerm) — strictly
        // above every prior committed leadership cluster-wide — so a new leader's lower local counter
        // still orders strictly after the prior leader's epoch via the term.
        var generationCounter = epochSources.generationCounter();
        Supplier<Epoch> leaderEpochSupplier = epochSources.leaderEpoch();

        defer.accept(() -> SharedScheduler.scheduleAtFixedRate(() -> bumpGenerationIfLeader(isLeaderSupplier,
                                                                                            generationCounter),
                                                               config.timeouts().cluster().pingInterval(),
                                                               config.timeouts().cluster().pingInterval()));
        // B4 (membership v2 §7.5): bind the CDM readiness supplier now that the pong fan + self-state
        // holder exist (READY peers from the leader view + self when locally READY).
        cdmReadyNodesRef.set(() -> nodesReporting(pongSignalFan,
                                                  nodeReportedStateHolder,
                                                  config.self(),
                                                  NodeReportedState.READY));
        cdmDrainingNodesRef.set(() -> nodesReporting(pongSignalFan,
                                                     nodeReportedStateHolder,
                                                     config.self(),
                                                     NodeReportedState.DRAINING));
        var metricsScheduler = ClusterSyncScheduler.clusterSyncScheduler(config.self(),
                                                                         clusterNode.network(),
                                                                         metricsCollector,
                                                                         config.timeouts().cluster().pingInterval(),
                                                                         rabiaTermSupplier,
                                                                         ClusterSyncScheduler.DEFAULT_PING_TIMEOUT_THRESHOLD,
                                                                         leaderEpochSupplier,
                                                                         peerObservationStore,
                                                                         periodicConfig,
                                                                         isLeaderSupplier);
        // Step 1 (Periodic Observation Mode): cap supplier honored externally so
        // the buffer absorbs one full emission cycle (N-1 observations per 5s tick)
        // plus per-peer transition burst. Floor of 64 from periodicConfig accommodates
        // small clusters; topology × 4 scales with peer count. Set AFTER the scheduler
        // is constructed because ClusterSyncContext's own constructor pre-wires the
        // store's cap supplier to its internal `bufferCap()` formula — this call wins
        // and gives AetherNode the canonical sizing surface.
        peerObservationStore.setCapSupplier(() -> Math.max(periodicConfig.capFloor().getAsInt(),
                                                           clusterNode.topologyManager().topology().size() * 4));
        metricsCollector.addPongListener(pong -> metricsScheduler.onPongReceived(pong.sender()));
        metricsScheduler.setDrainTargets(drainCommandRegistry::drainTargets);
        // #114 — the leader never pings itself, so metricsCollector.observedEpoch() (follower-style,
        // updated only by RECEIVED pings) stays at 0:0 on the leader forever. The CURRENT generation
        // epoch is therefore the leader-minted epoch on the leader and the observed ping epoch elsewhere.
        // gen-route + await-quiesced + NDM read this supplier so deploy barriers see current+1.
        Supplier<Epoch> currentGenerationEpochSupplier = () -> isLeaderSupplier.getAsBoolean()
                                                               ? leaderEpochSupplier.get()
                                                               : metricsCollector.observedEpoch();
        // E2 Phase 2a (2026-05-28): leader-side φ-accrual (#231) is removed. SWIM owns the
        // liveness decision directly; the v2 NTT path is the replacement debounce.
        // P3 (membership unification): the leader/spokesman-gated ReachabilityAggregator
        // pong ingest is removed with the aggregator. SWIM owns liveness; pongs no longer
        // feed a reachability fold.
        metricsCollector.setPeerObservationBuffer(peerObservationStore);
        Supplier<Option<AetherValue.ClusterConfigValue>> clusterConfigReader = () -> kvStore.get(AetherKey.ClusterConfigKey.CURRENT)
                                                                                            .filter(v -> v instanceof AetherValue.ClusterConfigValue)
                                                                                            .map(v -> (AetherValue.ClusterConfigValue) v);
        java.util.function.Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> clusterCommandApplier = commands -> clusterNode.apply(commands);
        var leaderAwareSnapshotSource = snapshotSource;

        return new ClusterSyncOutputs(metricsCollector,
                                      peerObservationStore,
                                      isLeaderSupplier,
                                      workerProjectionFreshRef,
                                      nodeReportedStateHolder,
                                      drainCommandRegistry,
                                      pongSignalFan,
                                      rabiaTermSupplier,
                                      leaderEpochSupplier,
                                      metricsScheduler,
                                      currentGenerationEpochSupplier,
                                      clusterConfigReader,
                                      clusterCommandApplier,
                                      leaderAwareSnapshotSource);
    }

    /// One LEADERSHIP-TENURE TICK: fired once per `pingInterval` (1s default), so the counter's value
    /// is elapsed leadership time in ping intervals — never an event count. Deliberately unlogged
    /// (1 Hz per leader forever, for a value readable on demand from `/api/cluster/generation`).
    @Contract
    private static void bumpGenerationIfLeader(BooleanSupplier isLeader, AtomicLong generationCounter) {
        if (isLeader.getAsBoolean()) {
            generationCounter.incrementAndGet();
        }
    }

    record unused() implements ClusterSyncAssembly {}
}
