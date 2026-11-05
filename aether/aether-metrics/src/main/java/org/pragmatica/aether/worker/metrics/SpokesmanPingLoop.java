// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.worker.metrics;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import org.pragmatica.aether.metrics.NodeReportedState;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SpokesmanKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SpokesmanStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SpokesmanValue;
import org.pragmatica.cluster.metrics.CommunityReport;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing;
import org.pragmatica.cluster.metrics.MetricObservation;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPong;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.concurrent.CancellableTask;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.utility.react.ReactExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public interface SpokesmanPingLoop {
    @Contract
    void start();

    @Contract
    void stop();

    @MessageReceiver
    @Contract
    void onSpokesmanPut(ValuePut<SpokesmanKey, SpokesmanValue> notification);

    @MessageReceiver
    @Contract
    void onSpokesmanRemove(ValueRemove<SpokesmanKey, SpokesmanValue> notification);

    @MessageReceiver
    @Contract
    void onClusterSyncPong(ClusterSyncPong pong);

    boolean isActive();
    List<CommunityReport> currentReports();

    /// D1-react (KVStore dispatcher design §3.5.1 rows 72/73): the executor activation, deactivation and the status
    /// writes run on. A node installs its gated executor once, at assembly; until then (and in a host that installs none)
    /// they run inline, as before D1-react.
    default Unit installReactExecutor(ReactExecutor executor) {
        return Unit.unit();
    }

    static SpokesmanPingLoop spokesmanPingLoop(NodeId self,
                                               ClusterNetwork network,
                                               TimeSpan interval,
                                               Supplier<Long> rabiaTermSupplier,
                                               Supplier<Map<NodeId, MetricObservation>> allMetricsSupplier,
                                               Function<String, Option<NodeId>> governorLookup) {
        return spokesmanPingLoop(self,
                                 network,
                                 interval,
                                 rabiaTermSupplier,
                                 allMetricsSupplier,
                                 governorLookup,
                                 NoopSpokesmanStatusWriter.INSTANCE);
    }

    static SpokesmanPingLoop spokesmanPingLoop(NodeId self,
                                               ClusterNetwork network,
                                               TimeSpan interval,
                                               Supplier<Long> rabiaTermSupplier,
                                               Supplier<Map<NodeId, MetricObservation>> allMetricsSupplier,
                                               Function<String, Option<NodeId>> governorLookup,
                                               SpokesmanStatusWriter statusWriter) {
        return new SpokesmanPingLoopImpl(self,
                                         network,
                                         interval,
                                         rabiaTermSupplier,
                                         allMetricsSupplier,
                                         governorLookup,
                                         statusWriter);
    }

    interface SpokesmanStatusWriter {
        @Contract
        void writeActive(NodeId self, SpokesmanValue baseValue);

        @Contract
        void writeFailure(NodeId self, SpokesmanValue baseValue, String reason);

        static SpokesmanStatusWriter fromCluster(ClusterNode<KVCommand<AetherKey>> cluster) {
            return new ClusterSpokesmanStatusWriter(cluster);
        }
    }
}

enum NoopSpokesmanStatusWriter implements SpokesmanPingLoop.SpokesmanStatusWriter {
    INSTANCE;
    @Contract
    @Override
    public void writeActive(NodeId self, SpokesmanValue baseValue) {}
    @Contract
    @Override
    public void writeFailure(NodeId self, SpokesmanValue baseValue, String reason) {}
}

record ClusterSpokesmanStatusWriter(ClusterNode<KVCommand<AetherKey>> cluster) implements SpokesmanPingLoop.SpokesmanStatusWriter {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ClusterSpokesmanStatusWriter.class);

    @Contract
    @Override
    public void writeActive(NodeId self, SpokesmanValue baseValue) {
        var active = baseValue.withStatus(SpokesmanStatus.ACTIVE);
        var command = new KVCommand.Put<AetherKey, AetherValue>(SpokesmanKey.spokesmanKey(self), active);

        cluster.apply(java.util.List.<KVCommand<AetherKey>> of(command))
               .onFailure(cause -> LOG.warn("Failed to write SpokesmanValue ACTIVE for {}: {}",
                                            self,
                                            cause.message()));
    }

    @Contract
    @Override
    public void writeFailure(NodeId self, SpokesmanValue baseValue, String reason) {
        var failed = baseValue.withFailure(reason);
        var command = new KVCommand.Put<AetherKey, AetherValue>(SpokesmanKey.spokesmanKey(self), failed);

        cluster.apply(java.util.List.<KVCommand<AetherKey>> of(command))
               .onFailure(cause -> LOG.warn("Failed to write SpokesmanValue FAILED for {}: {}",
                                            self,
                                            cause.message()));
    }
}

final class SpokesmanPingLoopImpl implements SpokesmanPingLoop {
    private static final Logger log = LoggerFactory.getLogger(SpokesmanPingLoopImpl.class);

    private final NodeId self;
    private final ClusterNetwork network;
    private final TimeSpan interval;
    private final Supplier<Long> rabiaTermSupplier;
    private final Supplier<Map<NodeId, MetricObservation>> allMetricsSupplier;
    private final Function<String, Option<NodeId>> governorLookup;
    private final SpokesmanStatusWriter statusWriter;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicReference<List<String>> assignedCommunities = new AtomicReference<>(List.of());
    private final AtomicReference<Map<String, CommunityReport>> reports = new AtomicReference<>(Map.of());

    private final AtomicReference<Map<NodeId, String>> governorToCommunity = new AtomicReference<>(Map.of());

    private final CancellableTask task = CancellableTask.cancellableTask();

    /// D1-react (§3.5.1 rows 72/73). Activation and the status writes are side-effect reactions behind the engine-alive
    /// gate; every deactivation (row 73's remove, an emptied or FAILED assignment, `stop()`) is an ungated undo on the same
    /// executor (U1), so FIFO keeps it after the activation it undoes and an engine stop cannot leak the ping timer.
    /// The writes are under U2: absorb of rows 72/73 bumps [#assignmentGeneration], and a reaction writes only if it has
    /// not moved, so a removed assignment is never resurrected as `Put(ACTIVE)`.
    private final AtomicReference<ReactExecutor> react = new AtomicReference<>(ReactExecutor.inline("SpokesmanPingLoop"));

    private final AtomicLong assignmentGeneration = new AtomicLong();
    private final Object writeGuard = new Object();

    SpokesmanPingLoopImpl(NodeId self,
                          ClusterNetwork network,
                          TimeSpan interval,
                          Supplier<Long> rabiaTermSupplier,
                          Supplier<Map<NodeId, MetricObservation>> allMetricsSupplier,
                          Function<String, Option<NodeId>> governorLookup,
                          SpokesmanStatusWriter statusWriter) {
        this.self = self;
        this.network = network;
        this.interval = interval;
        this.rabiaTermSupplier = rabiaTermSupplier;
        this.allMetricsSupplier = allMetricsSupplier;
        this.governorLookup = governorLookup;
        this.statusWriter = statusWriter;
    }

    @Override
    @Contract
    public void start() {
        started.set(true);
    }

    @Override
    @Contract
    public void stop() {
        started.set(false);
        react.get().undo("Spokesman stop", this::deactivate);
    }

    @Override
    public Unit installReactExecutor(ReactExecutor executor) {
        react.set(executor);

        return Unit.unit();
    }

    @Override
    @Contract
    public void onSpokesmanPut(ValuePut<SpokesmanKey, SpokesmanValue> notification) {
        if (!started.get()) {
            return;
        }

        if (!notification.cause().key().coreNodeId().equals(self)) {
            return;
        }

        var value = notification.cause().value();
        var generation = bumpGeneration();

        if (value.communities().isEmpty() || value.status() == SpokesmanStatus.FAILED) {
            react.get().undo("Spokesman deactivate", this::deactivate);

            return;
        }

        if (value.status() == SpokesmanStatus.ACTIVE) {
            react.get().react("Spokesman activate", () -> activate(value.communities()));

            return;
        }

        react.get().react("Spokesman assigned", () -> handleAssigned(value, generation));
    }

    @Contract
    private void handleAssigned(SpokesmanValue baseValue, long generation) {
        try {
            activate(baseValue.communities());
            writeIfCurrent(generation, () -> statusWriter.writeActive(self, baseValue));
        } catch (RuntimeException ex) {
            log.warn("Activation failed on {} for communities {}: {}", self, baseValue.communities(), ex.getMessage());
            deactivate();
            writeIfCurrent(generation, () -> statusWriter.writeFailure(self, baseValue, failureReason(ex)));
        }
    }

    private static String failureReason(RuntimeException ex) {
        return ex.getMessage() == null
               ? ex.getClass()
                   .getSimpleName()
               : ex.getMessage();
    }

    /// Absorb half of U2: under the guard, so a reaction's check-and-submit never interleaves with the bump.
    private long bumpGeneration() {
        synchronized (writeGuard) {
            return assignmentGeneration.incrementAndGet();
        }
    }

    /// Reaction half of U2: the write is submitted only if no later put or remove of the assignment was absorbed.
    @Contract
    private void writeIfCurrent(long generation, Runnable write) {
        synchronized (writeGuard) {
            if (assignmentGeneration.get() == generation) {
                write.run();
            } else {
                log.debug("Spokesman status write on {} skipped: the assignment changed after generation {}",
                          self,
                          generation);
            }
        }
    }

    @Override
    @Contract
    public void onSpokesmanRemove(ValueRemove<SpokesmanKey, SpokesmanValue> notification) {
        if (!notification.cause().key().coreNodeId().equals(self)) {
            return;
        }

        bumpGeneration();
        react.get().undo("Spokesman remove", this::deactivate);
    }

    @Override
    @Contract
    public void onClusterSyncPong(ClusterSyncPong pong) {
        if (!active.get()) {
            return;
        }

        Option.option(governorToCommunity.get().get(pong.sender())).onPresent(communityId -> aggregatePong(communityId,
                                                                                                           pong));
    }

    @Override
    public boolean isActive() {
        return active.get();
    }

    @Override
    public List<CommunityReport> currentReports() {
        return List.copyOf(reports.get().values());
    }

    @Contract
    private void activate(List<String> communities) {
        var frozen = List.copyOf(communities);
        var newIndex = buildGovernorIndex(frozen);

        if (!active.compareAndSet(false, true)) {
            assignedCommunities.set(frozen);
            governorToCommunity.set(newIndex);

            return;
        }

        assignedCommunities.set(frozen);
        governorToCommunity.set(newIndex);
        task.set(SharedScheduler.scheduleAtFixedRate(this::tick, interval));
        log.info("SpokesmanPingLoop activated on {} with communities {}", self, frozen);
    }

    @Contract
    private void deactivate() {
        if (active.compareAndSet(true, false)) {
            task.cancel();
            reports.set(Map.of());
            governorToCommunity.set(Map.of());
            assignedCommunities.set(List.of());
            log.info("SpokesmanPingLoop deactivated on {}", self);
        }
    }

    private Map<NodeId, String> buildGovernorIndex(List<String> communities) {
        var fresh = new LinkedHashMap<NodeId, String>();

        communities.forEach(communityId -> governorLookup.apply(communityId)
                                                         .onPresent(governor -> fresh.put(governor, communityId)));

        return Map.copyOf(fresh);
    }

    private void tick() {
        try {
            var communities = assignedCommunities.get();

            if (communities.isEmpty()) {
                return;
            }

            var rabiaTerm = rabiaTermSupplier.get();

            communities.forEach(communityId -> pingOneGovernor(communityId, rabiaTerm));
        } catch (Exception e) {
            log.warn("SpokesmanPingLoop tick failed: {}", e.getMessage());
        }
    }

    private void pingOneGovernor(String communityId, long rabiaTerm) {
        governorLookup.apply(communityId).onPresent(governor -> sendPing(governor, rabiaTerm));
    }

    private void sendPing(NodeId governor, long rabiaTerm) {
        var epoch = Epoch.ZERO;
        var local = allMetricsSupplier.get().get(self);
        var observations = local == null
                           ? Map.<NodeId, MetricObservation> of()
                           : Map.of(self, local);
        var ping = new ClusterSyncPing(self,
                                       observations,
                                       rabiaTerm,
                                       epoch.incarnation(),
                                       epoch.rabiaTerm(),
                                       epoch.localCounter(),
                                       Set.of(),
                                       Set.of(),
                                       Map.of(),
                                       Set.of(),
                                       false,
                                       false);

        network.send(governor, ping);
    }

    private void aggregatePong(String communityId, ClusterSyncPong pong) {
        reports.updateAndGet(current -> mergeReport(current, communityId, pong));
    }

    private static Map<String, CommunityReport> mergeReport(Map<String, CommunityReport> current,
                                                            String communityId,
                                                            ClusterSyncPong pong) {
        var partitionsHeld = Option.option(current.get(communityId)).map(CommunityReport::partitionsHeld).or(Set.of());
        var members = lifecycleCount(pong);
        var report = new CommunityReport(communityId,
                                         0L,
                                         pong.observedEpochIncarnation(),
                                         pong.observedEpochTerm(),
                                         pong.observedEpochCounter(),
                                         pong.sender(),
                                         members.total(),
                                         members.healthy(),
                                         members.suspected(),
                                         members.faulty(),
                                         partitionsHeld,
                                         System.currentTimeMillis());
        var fresh = new HashMap<>(current);

        fresh.put(communityId, report);

        return Map.copyOf(fresh);
    }

    private static LifecycleCounts lifecycleCount(ClusterSyncPong pong) {
        var state = pong.lifecycleState();
        // Membership-v2 finale: lifecycleState carries the real NodeReportedState
        // (SYNCING|READY|DRAINING). READY/SYNCING are operational (healthy); DRAINING is winding
        // down (suspected). The legacy synthetic lifecycle states are gone.
        var healthy = NodeReportedState.READY.name().equals(state) || NodeReportedState.SYNCING.name().equals(state)
                      ? 1
                      : 0;
        var suspected = NodeReportedState.DRAINING.name().equals(state)
                        ? 1
                        : 0;

        return new LifecycleCounts(healthy + suspected, healthy, suspected, 0);
    }

    private record LifecycleCounts(int total, int healthy, int suspected, int faulty) {}
}
