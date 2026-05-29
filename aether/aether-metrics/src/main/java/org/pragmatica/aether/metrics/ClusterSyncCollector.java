// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.cluster.metrics.AggregatedReachabilitySnapshot;
import org.pragmatica.cluster.metrics.CommunityReport;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPong;
import org.pragmatica.cluster.metrics.ConnectivityState;
import org.pragmatica.cluster.metrics.NodePingCommand;
import org.pragmatica.cluster.metrics.PeerConnectivityObservation;
import org.pragmatica.cluster.metrics.PeerObservationBuffer;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.utility.RingBuffer;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public interface ClusterSyncCollector {
    String CPU_USAGE = "cpu.usage";

    String HEAP_USED = "heap.used";

    String HEAP_MAX = "heap.max";

    String HEAP_USAGE = "heap.usage";

    Map<String, Double> collectLocal();
    @Contract void recordCall(MethodName method, long durationMs);
    @Contract void recordCustom(String name, double value);
    @Contract void setInvocationMetricsProvider(InvocationMetricsCollector provider);
    Map<NodeId, Map<String, Double>> allMetrics();
    Map<String, Double> metricsFor(NodeId nodeId);
    Map<NodeId, List<MetricsSnapshot>> historicalMetrics();

    record MetricsSnapshot(long timestamp, Map<String, Double> metrics){}

    /// Test-only synthetic-history injection. Appends `snapshot` to the
    /// historical ring buffer for `nodeId` without going through the normal
    /// `collectLocal()` → `addToHistory(self, ...)` path. Used by the
    /// `/api/metrics/backfill` dev-mode endpoint (P-NEW-D, 2026-05-21) to
    /// seed deterministic historical samples for TC-11-H1-historical-metrics-range
    /// without waiting hours for the sliding window to populate organically.
    /// Default no-op so the dozens of test stubs that implement this
    /// interface don't all need an override.
    @Contract default void injectHistoricalSnapshot(NodeId nodeId, MetricsSnapshot snapshot) {}

    @Contract void removeNode(NodeId nodeId);
    @MessageReceiver@Contract void onMembershipDecision(MembershipDecision decision);
    @MessageReceiver@Contract void onClusterSyncPing(ClusterSyncPing ping);
    @MessageReceiver@Contract void onClusterSyncPong(ClusterSyncPong pong);
    long observedRabiaTerm();
    Epoch observedEpoch();
    String currentLifecycleState();
    List<CommunityReport> collectCommunityReports();
    @Contract void setLifecycleStateSupplier(Supplier<String> supplier);
    @Contract void setCommunityReportSupplier(Supplier<List<CommunityReport>> supplier);
    @Contract void addPongListener(Consumer<ClusterSyncPong> listener);

    long DEFAULT_slidingWindowMs = 2 * 60 * 60 * 1000L;

    @Contract void setPongSignalFan(ClusterSyncPongSignalFan fan);
    @Contract void setPeerObservationBuffer(PeerObservationBuffer buffer);
    /// RC1 (S01 fix) — wire the SWIM-backed predicate that answers "is this peer
    /// observed as ALIVE in our local SWIM membership view?". Used to verify owner-
    /// broadcast eviction hints (`ClusterSyncPing.evictionHints`) — when SWIM says
    /// ALIVE, the follower REFUSES the owner's hint, preserving the independent-
    /// observers property. Default no-op (test doubles inherit; production wires
    /// in `AetherNode` to consult `CoreSwimHealthDetector.healthOf`).
    @Contract default void setPeerLocallyAlive(java.util.function.Predicate<NodeId> predicate) {}

    /// RC1 (S01 fix, owner-side symmetry) — query the SWIM-backed predicate wired via
    /// [setPeerLocallyAlive]. Returns whether `peer` is observed ALIVE (SWIM HEALTHY) in
    /// the local membership view. Default `true` (no SWIM info → behave as before).
    /// `ClusterSyncContext.emitPingTimeoutIfExceeded` consults this BEFORE soft-evicting a
    /// peer on ping-timeout, so the owner refuses to evict a SWIM-HEALTHY peer — the same
    /// guard `processEvictionHints` already applies follower-side. Reuses the single
    /// predicate wired in `AetherNode` (no second wiring path).
    default boolean peerLocallyAlive(NodeId peer) {return true;}

    /// Emit one `PeerConnectivityObservation` per topology peer (excluding `self`)
    /// into the wired `PeerObservationBuffer`. Peers in `connected` get state
    /// `CONNECTED`; peers in `topology` but absent from `connected` get state
    /// `DISCONNECTED`. Invoked periodically by `ClusterSyncScheduler` to keep
    /// the leader-side aggregator continuously fed (eliminates the
    /// transition-only buffer-decay race). See
    /// `reachability-aggregator-spec.md` "Periodic Observation Mode".
    @Contract void emitPeriodicConnectivity(Set<NodeId> topology, Set<NodeId> connected, NodeId self, long nowMs);

    /// Most-recently received `AggregatedReachabilitySnapshot` from an incoming
    /// `ClusterSyncPing`. `Option.none()` until the first ping with a non-empty
    /// snapshot lands (cold-start window or pre-extension peers). Followers cache
    /// this for warm-takeover when they become leader; `/api/status` reads it to
    /// produce reader-invariant `coreCount`. See
    /// `aether/docs/specs/reachability-aggregator-spec.md`.
    Option<AggregatedReachabilitySnapshot> lastReachabilitySnapshot();

    /// Wires the local `ReachabilityAggregator`'s snapshot supplier. On the leader,
    /// `lastReachabilitySnapshot()` is forever `none` (the leader sends pings, never
    /// receives them) so `MembershipView` must read the leader's OWN aggregator
    /// output directly. The injected supplier is consulted by `bestSnapshot()` and
    /// takes precedence when present. Followers leave this unset; `bestSnapshot()`
    /// falls back to the cached received snapshot.
    @Contract void setLocalSnapshotSupplier(Supplier<Option<AggregatedReachabilitySnapshot>> supplier);

    /// Unified accessor: leader's local aggregator output if non-empty, else the
    /// cached snapshot received from the prior leader. This is the value that
    /// `MembershipView.strict` consumes via `AetherNode.membershipView()` to
    /// resolve KV-ON_DUTY peer status when local SWIM hasn't acked HEALTHY.
    Option<AggregatedReachabilitySnapshot> bestSnapshot();

    /// Membership v2 (§7.5.3) — wire the node-reported readiness state supplier so
    /// `buildPong()` stamps `current().name()` (SYNCING|READY|DRAINING) onto
    /// `ClusterSyncPong.lifecycleState` instead of the legacy `currentLifecycleState()`
    /// default `() -> "ON_DUTY"`. Default no-op leaves the legacy supplier in place
    /// (test doubles inherit; production wires in `AetherNode`).
    @Contract default void setNodeReportedStateSupplier(Supplier<NodeReportedState> supplier) {}

    /// Membership v2 (§7.5.3) — wire the per-incarnation discriminator stamped onto
    /// `ClusterSyncPong.incarnation`. Default no-op leaves the field at `0L`
    /// (pre-migration). Production wires in `AetherNode`, defaulting to
    /// `BootEpoch::bootEpoch`. See [BootEpoch].
    @Contract default void setIncarnationSupplier(java.util.function.LongSupplier supplier) {}

    /// Membership v2 (B5a) — wire the handler invoked whenever an inbound `ClusterSyncPing`
    /// carries `NodePingCommand.DRAIN` (the command targets THIS node). Invoked AFTER the
    /// existing fencing/metrics/eviction-hint handling. Production wires it in `AetherNode` to
    /// `DrainProcedure.initiate(DrainReason.COMMANDED)` + `holder.onDrainStarted()`. The handler
    /// MUST be idempotent — it is invoked on every DRAIN ping (DrainProcedure is CAS-guarded).
    /// Default no-op leaves pre-B5a behavior unchanged (test doubles inherit).
    @Contract default void setDrainCommandHandler(Runnable handler) {}

    static ClusterSyncCollector clusterSyncCollector(NodeId self, ClusterNetwork network) {
        return new ClusterSyncCollectorImpl(self, network, DEFAULT_slidingWindowMs);
    }

    static ClusterSyncCollector clusterSyncCollector(NodeId self, ClusterNetwork network, long slidingWindowMs) {
        return new ClusterSyncCollectorImpl(self, network, slidingWindowMs);
    }
}

class ClusterSyncCollectorImpl implements ClusterSyncCollector {
    private static final Logger log = LoggerFactory.getLogger(ClusterSyncCollectorImpl.class);

    private final long slidingWindowMs;
    private final int ringBufferCapacity;
    private final NodeId self;
    private final ClusterNetwork network;
    private final OperatingSystemMXBean osMxBean;
    private final MemoryMXBean memoryMxBean;

    private final ConcurrentHashMap<MethodName, CallStats> callStats = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Double> customMetrics = new ConcurrentHashMap<>();

    private volatile InvocationMetricsCollector invocationMetricsProvider;

    private final ConcurrentHashMap<NodeId, Map<String, Double>> remoteMetrics = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<NodeId, RingBuffer<MetricsSnapshot>> historicalMetricsMap = new ConcurrentHashMap<>();

    private final AtomicLong observedRabiaTerm = new AtomicLong();

    private final AtomicReference<Epoch> observedEpoch = new AtomicReference<>(Epoch.ZERO);

    private final AtomicReference<Supplier<String>> lifecycleStateSupplier = new AtomicReference<>(() -> "ON_DUTY");

    private final AtomicReference<Option<AggregatedReachabilitySnapshot>> lastReachabilitySnapshot = new AtomicReference<>(Option.none());

    private final AtomicReference<Supplier<Option<AggregatedReachabilitySnapshot>>> localSnapshotSupplier = new AtomicReference<>(Option::none);

    private final AtomicReference<Supplier<List<CommunityReport>>> communityReportSupplier = new AtomicReference<>(List::of);

    private final CopyOnWriteArrayList<Consumer<ClusterSyncPong>> pongListeners = new CopyOnWriteArrayList<>();

    private final AtomicReference<ClusterSyncPongSignalFan> pongSignalFan = new AtomicReference<>(ClusterSyncPongSignalFan.NOOP);

    private final AtomicReference<PeerObservationBuffer> peerObservationBuffer = new AtomicReference<>(PeerObservationBuffer.NOOP);

    /// RC1 (S01 fix) — SWIM-backed predicate "is this peer observed ALIVE locally". Used
    /// to verify owner-broadcast eviction hints (`ClusterSyncPing.evictionHints`). Default
    /// is "always alive" (safe — refuses all hints) until the wiring layer plugs in a real
    /// SWIM accessor. Set by `AetherNode` to `nodeId -> swimProtocol.healthOf(nodeId) ==
    /// SwimHealth.HEALTHY`. When this predicate returns TRUE for a peer, the follower
    /// IGNORES the owner's eviction hint for that peer — independence preserved.
    private final AtomicReference<java.util.function.Predicate<NodeId>> peerLocallyAlive = new AtomicReference<>(_ -> true);

    /// Membership v2 (§7.5.3) — node-reported readiness state supplier. `buildPong()`
    /// stamps `current().name()` onto the pong's `lifecycleState` field, repurposing it
    /// to carry SYNCING|READY|DRAINING. Default `Option.none()` keeps the legacy
    /// `lifecycleStateSupplier` path until `setNodeReportedStateSupplier(...)` is wired.
    private final AtomicReference<Option<Supplier<NodeReportedState>>> nodeReportedStateSupplier =
        new AtomicReference<>(Option.none());

    /// Membership v2 (§7.5.3) — per-incarnation discriminator supplier. `buildPong()`
    /// stamps the value onto `ClusterSyncPong.incarnation`. Default `0L` (pre-migration)
    /// until `setIncarnationSupplier(...)` wires `BootEpoch::bootEpoch` or the SWIM
    /// incarnation in `AetherNode`.
    private final AtomicReference<java.util.function.LongSupplier> incarnationSupplier =
        new AtomicReference<>(() -> 0L);

    /// Membership v2 (B5a) — handler invoked when an inbound DRAIN ping is received. Default
    /// no-op until `setDrainCommandHandler(...)` wires the local `DrainProcedure`. Invoked on
    /// every DRAIN ping; the handler must be idempotent (DrainProcedure is CAS-guarded).
    private final AtomicReference<Runnable> drainCommandHandler =
        new AtomicReference<>(() -> {});

    ClusterSyncCollectorImpl(NodeId self, ClusterNetwork network, long slidingWindowMs) {
        this.self = self;
        this.network = network;
        this.slidingWindowMs = slidingWindowMs;
        this.ringBufferCapacity = (int)(slidingWindowMs / 1000);
        this.osMxBean = ManagementFactory.getOperatingSystemMXBean();
        this.memoryMxBean = ManagementFactory.getMemoryMXBean();
    }

    @Override public Map<String, Double> collectLocal() {
        var metrics = new HashMap<String, Double>();
        collectCpuMetrics(metrics);
        collectHeapMetrics(metrics);
        collectCallStatsMetrics(metrics);
        metrics.putAll(customMetrics);
        collectInvocationMetrics(metrics);
        return metrics;
    }

    @Override@Contract public void recordCall(MethodName method, long durationMs) {
        callStats.computeIfAbsent(method, _ -> CallStats.callStats()).record(durationMs);
    }

    @Override@Contract public void recordCustom(String name, double value) {
        customMetrics.put(name, value);
    }

    @Override@Contract public void setInvocationMetricsProvider(InvocationMetricsCollector provider) {
        this.invocationMetricsProvider = provider;
    }

    @Override public Map<NodeId, Map<String, Double>> allMetrics() {
        var local = collectLocal();
        addToHistory(self, local);
        var result = new ConcurrentHashMap<>(remoteMetrics);
        result.put(self, local);
        return result;
    }

    @Override public Map<String, Double> metricsFor(NodeId nodeId) {
        if (nodeId.equals(self)) {return collectLocal();}
        return remoteMetrics.getOrDefault(nodeId, Map.of());
    }

    @Override public Map<NodeId, List<MetricsSnapshot>> historicalMetrics() {
        var cutoff = System.currentTimeMillis() - slidingWindowMs;
        var result = new ConcurrentHashMap<NodeId, List<MetricsSnapshot>>();
        historicalMetricsMap.forEach((nodeId, ringBuffer) -> addFilteredHistory(result, nodeId, ringBuffer, cutoff));
        return result;
    }

    @Override@Contract public void removeNode(NodeId nodeId) {
        remoteMetrics.remove(nodeId);
        historicalMetricsMap.remove(nodeId);
    }

    @Override
    @Contract
    public void injectHistoricalSnapshot(NodeId nodeId, MetricsSnapshot snapshot) {
        var ringBuffer = historicalMetricsMap.computeIfAbsent(nodeId, _ -> RingBuffer.ringBuffer(ringBufferCapacity));
        ringBuffer.add(snapshot);
    }

    @Override@Contract public void onMembershipDecision(MembershipDecision decision) {
        switch (decision){
            case MembershipDecision.NodeRemoved(var removedNode, _, _, _) -> removeNode(removedNode);
            case MembershipDecision.NodeDecommissioned(var decommissioned, _, _, _) -> removeNode(decommissioned);
            default -> {}
        }
    }

    @Override@Contract public void onClusterSyncPing(ClusterSyncPing ping) {
        log.debug("ClusterSync: received PING from {} (rabiaTerm={}, epoch={}:{})",
                  ping.sender(),
                  ping.rabiaTerm(),
                  ping.epochTerm(),
                  ping.epochCounter());
        if (!acceptPingFencing(ping)) {
            log.warn("ClusterSync: PING from {} rejected by fencing (rabiaTerm={} < observed={})",
                     ping.sender(),
                     ping.rabiaTerm(),
                     observedRabiaTerm.get());
            return;
        }
        ping.allMetrics().forEach(this::storeRemoteMetrics);
        var incomingEpoch = Epoch.epoch(ping.epochTerm(), ping.epochCounter());
        advanceObservedEpoch(incomingEpoch);
        // Cache the leader-broadcast reachability snapshot. `/api/status` reads
        // it to eliminate per-reader QUIC-view variance; on leader-gained, the
        // new leader seeds its aggregator from this cache to shorten warmup.
        // Pre-extension peers send Option.none() — leave cache unchanged so
        // older data isn't lost during a rolling upgrade window.
        ping.aggregatedReachability().onPresent(snapshot -> lastReachabilitySnapshot.set(Option.some(snapshot)));
        // RC1 (S01 fix): owner's eviction hints are SUGGESTIONS — verify against local
        // liveness evidence before acting. If we've received traffic from the peer
        // recently (within `EVICTION_HINT_VERIFY_NANOS`), the owner's view is likely
        // stale or wrong (asymmetric routing / NIC issue on owner side) — ignore.
        // If silent locally too, agree with owner and disconnect the peer ourselves,
        // which strips our REACHABLE vote from the next pong and lets the aggregator
        // converge faster. Preserves the "independent observers" property by adding
        // a LOCAL veto on the owner's suggestion.
        processEvictionHints(ping);
        var pong = buildPong();
        log.debug("ClusterSync: sending PONG to {} (epoch={}:{})",
                  ping.sender(),
                  pong.observedEpochTerm(),
                  pong.observedEpochCounter());
        network.send(ping.sender(), pong);
        // Membership v2 (B5a) — leader→node DRAIN command piggybacked on the ping. Acted on
        // AFTER fencing/metrics/eviction handling and after the pong is sent so the leader
        // still observes this incarnation's response. Idempotent: the wired handler guards
        // against repeated DRAIN pings (DrainProcedure is CAS-guarded). NONE is a no-op.
        handleDrainCommand(ping);
    }

    /// Membership v2 (B5a) — invoke the wired drain handler when the inbound ping commands DRAIN.
    private void handleDrainCommand(ClusterSyncPing ping) {
        if (ping.command() != NodePingCommand.DRAIN) {return;}
        log.info("ClusterSync: received DRAIN command from {} — invoking local drain handler", ping.sender());
        drainCommandHandler.get().run();
    }

    /// RC1 (S01 fix) — when SWIM says the suggested-evicted peer is observed locally as
    /// ALIVE, the follower REFUSES the owner's eviction hint (preserves independent-
    /// observers property). When SWIM has no direct evidence (SUSPECT/FAULTY/UNKNOWN/
    /// never-seen), the follower agrees with owner and disconnects locally. SWIM's
    /// ALIVE state requires actual probe-ack within the last `suspectTimeout` window
    /// — strong enough evidence that the peer is genuinely reachable.
    private void processEvictionHints(ClusterSyncPing ping) {
        var hints = ping.evictionHints();
        if (hints.isEmpty()) {return;}
        var aliveCheck = peerLocallyAlive.get();
        for (var peer : hints) {
            if (peer.equals(self)) {continue;}
            if (aliveCheck.test(peer)) {
                log.debug("ClusterSync: ignored eviction hint for {} from {} — SWIM says ALIVE",
                          peer, ping.sender());
                continue;
            }
            log.info("ClusterSync: acting on eviction hint for {} from {} (SWIM not ALIVE)",
                     peer, ping.sender());
            network.disconnect(new NetworkServiceMessage.DisconnectNode(peer));
        }
    }

    @Override@Contract public void onClusterSyncPong(ClusterSyncPong pong) {
        log.debug("ClusterSync: received PONG from {} (epoch={}:{})",
                  pong.sender(),
                  pong.observedEpochTerm(),
                  pong.observedEpochCounter());
        if (!pong.sender().equals(self)) {
            remoteMetrics.put(pong.sender(), pong.metrics());
            addToHistory(pong.sender(), pong.metrics());
        }
        pongSignalFan.get().fan(pong);
        pongListeners.forEach(listener -> listener.accept(pong));
    }

    @Override@Contract public void setPongSignalFan(ClusterSyncPongSignalFan fan) {
        pongSignalFan.set(fan);
    }

    @Override@Contract public void setPeerObservationBuffer(PeerObservationBuffer buffer) {
        peerObservationBuffer.set(buffer == null
                                  ? PeerObservationBuffer.NOOP
                                  : buffer);
    }

    @Override@Contract public void setPeerLocallyAlive(java.util.function.Predicate<NodeId> predicate) {
        peerLocallyAlive.set(predicate == null
                             ? _ -> true
                             : predicate);
    }

    @Override public boolean peerLocallyAlive(NodeId peer) {
        return peerLocallyAlive.get().test(peer);
    }

    @Override@Contract public void setNodeReportedStateSupplier(Supplier<NodeReportedState> supplier) {
        nodeReportedStateSupplier.set(Option.option(supplier));
    }

    @Override@Contract public void setIncarnationSupplier(java.util.function.LongSupplier supplier) {
        incarnationSupplier.set(supplier == null
                                ? () -> 0L
                                : supplier);
    }

    @Override@Contract public void setDrainCommandHandler(Runnable handler) {
        drainCommandHandler.set(handler == null
                                ? () -> {}
                                : handler);
    }

    @Override@Contract public void emitPeriodicConnectivity(Set<NodeId> topology, Set<NodeId> connected, NodeId self, long nowMs) {
        var buffer = peerObservationBuffer.get();
        var epoch = observedEpoch.get();
        var epochTerm = epoch.rabiaTerm();
        var epochCounter = epoch.localCounter();
        topology.stream()
                .filter(peer -> !peer.equals(self))
                .map(peer -> new PeerConnectivityObservation(peer,
                                                             connected.contains(peer)
                                                                 ? ConnectivityState.CONNECTED
                                                                 : ConnectivityState.DISCONNECTED,
                                                             epochTerm,
                                                             epochCounter,
                                                             nowMs))
                .forEach(buffer::pushConnectivity);
    }

    @Override public long observedRabiaTerm() {
        return observedRabiaTerm.get();
    }

    @Override public Epoch observedEpoch() {
        return observedEpoch.get();
    }

    @Override public String currentLifecycleState() {
        return lifecycleStateSupplier.get().get();
    }

    @Override public List<CommunityReport> collectCommunityReports() {
        return communityReportSupplier.get().get();
    }

    @Override@Contract public void setLifecycleStateSupplier(Supplier<String> supplier) {
        lifecycleStateSupplier.set(supplier);
    }

    @Override@Contract public void setCommunityReportSupplier(Supplier<List<CommunityReport>> supplier) {
        communityReportSupplier.set(supplier);
    }

    @Override@Contract public void addPongListener(Consumer<ClusterSyncPong> listener) {
        pongListeners.add(listener);
    }

    @Override public Option<AggregatedReachabilitySnapshot> lastReachabilitySnapshot() {
        return lastReachabilitySnapshot.get();
    }

    @Override@Contract public void setLocalSnapshotSupplier(Supplier<Option<AggregatedReachabilitySnapshot>> supplier) {
        localSnapshotSupplier.set(supplier == null ? Option::none : supplier);
    }

    @Override public Option<AggregatedReachabilitySnapshot> bestSnapshot() {
        var local = localSnapshotSupplier.get().get();
        return local.isPresent() ? local : lastReachabilitySnapshot.get();
    }

    private boolean acceptPingFencing(ClusterSyncPing ping) {
        var currentTerm = observedRabiaTerm.get();
        if (ping.rabiaTerm() < currentTerm) {return false;}
        if (ping.rabiaTerm() > currentTerm) {observedRabiaTerm.set(ping.rabiaTerm());}
        return true;
    }

    private void advanceObservedEpoch(Epoch incomingEpoch) {
        observedEpoch.updateAndGet(prev -> incomingEpoch.isStrictlyAfter(prev)
                                          ? incomingEpoch
                                          : prev);
    }

    private ClusterSyncPong buildPong() {
        var epoch = observedEpoch.get();
        var buffer = peerObservationBuffer.get();
        return new ClusterSyncPong(self,
                                   collectLocal(),
                                   observedRabiaTerm.get(),
                                   epoch.rabiaTerm(),
                                   epoch.localCounter(),
                                   reportedLifecycleState(),
                                   collectCommunityReports(),
                                   buffer.drainHealth(),
                                   buffer.drainConnectivity(),
                                   Option.none(),
                                   incarnationSupplier.get().getAsLong());
    }

    /// Membership v2 (§7.5.3) — the string stamped onto the pong's `lifecycleState`
    /// field. When a node-reported-state supplier is wired, the field carries the
    /// `NodeReportedState` name (SYNCING|READY|DRAINING); otherwise it falls back to the
    /// legacy `currentLifecycleState()` value, preserving pre-migration behaviour.
    private String reportedLifecycleState() {
        return nodeReportedStateSupplier.get()
                                        .map(Supplier::get)
                                        .map(NodeReportedState::name)
                                        .or(this::currentLifecycleState);
    }

    private void collectCpuMetrics(Map<String, Double> metrics) {
        double systemLoad = osMxBean.getSystemLoadAverage();
        if (systemLoad >= 0) {
            int processors = osMxBean.getAvailableProcessors();
            metrics.put(CPU_USAGE, Math.min(1.0, systemLoad / processors));
        }
    }

    private void collectHeapMetrics(Map<String, Double> metrics) {
        var heapUsage = memoryMxBean.getHeapMemoryUsage();
        metrics.put(HEAP_USED, (double) heapUsage.getUsed());
        metrics.put(HEAP_MAX, (double) heapUsage.getMax());
        if (heapUsage.getMax() > 0) {metrics.put(HEAP_USAGE,
                                                 (double) heapUsage.getUsed() / heapUsage.getMax());}
    }

    private void collectCallStatsMetrics(Map<String, Double> metrics) {
        callStats.forEach((method, stats) -> addMethodStats(metrics, method, stats));
    }

    private void addMethodStats(Map<String, Double> metrics, MethodName method, CallStats stats) {
        var prefix = "method." + method.name() + ".";
        metrics.put(prefix + "calls", (double) stats.count.sum());
        metrics.put(prefix + "duration.total", stats.totalDuration.sum());
        if (stats.count.sum() > 0) {metrics.put(prefix + "duration.avg",
                                                stats.totalDuration.sum() / stats.count.sum());}
    }

    private void collectInvocationMetrics(Map<String, Double> metrics) {
        var invMetrics = invocationMetricsProvider;
        if (invMetrics == null) {return;}
        invMetrics.snapshot().forEach(snapshot -> addInvocationSnapshot(metrics, snapshot));
    }

    private void addInvocationSnapshot(Map<String, Double> metrics,
                                       InvocationMetricsCollector.MethodSnapshot snapshot) {
        var prefix = "inv|" + snapshot.artifact().asString() + "|" + snapshot.methodName().name() + "|";
        var m = snapshot.metrics();
        metrics.put(prefix + "count", (double) m.count());
        metrics.put(prefix + "success", (double) m.successCount());
        metrics.put(prefix + "failure", (double) m.failureCount());
        metrics.put(prefix + "totalNs", (double) m.totalDurationNs());
        metrics.put(prefix + "p50ns", (double) m.estimatePercentileNs(50));
        metrics.put(prefix + "p95ns", (double) m.estimatePercentileNs(95));
    }

    private void storeRemoteMetrics(NodeId nodeId, Map<String, Double> metrics) {
        if (!nodeId.equals(self)) {
            remoteMetrics.put(nodeId, metrics);
            addToHistory(nodeId, metrics);
        }
    }

    private void addFilteredHistory(Map<NodeId, List<MetricsSnapshot>> result,
                                    NodeId nodeId,
                                    RingBuffer<MetricsSnapshot> ringBuffer,
                                    long cutoff) {
        var filtered = ringBuffer.filter(s -> s.timestamp() >= cutoff);
        if (!filtered.isEmpty()) {result.put(nodeId, filtered);}
    }

    private void addToHistory(NodeId nodeId, Map<String, Double> metrics) {
        var ringBuffer = historicalMetricsMap.computeIfAbsent(nodeId, _ -> RingBuffer.ringBuffer(ringBufferCapacity));
        ringBuffer.add(new MetricsSnapshot(System.currentTimeMillis(), metrics));
    }

    private record CallStats(LongAdder count, DoubleAdder totalDuration) {
        static CallStats callStats() {
            return new CallStats(new LongAdder(), new DoubleAdder());
        }

        @Contract void record(long durationMs) {
            count.increment();
            totalDuration.add(durationMs);
        }
    }
}
