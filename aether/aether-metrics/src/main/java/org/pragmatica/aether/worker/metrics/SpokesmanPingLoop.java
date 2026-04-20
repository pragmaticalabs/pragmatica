// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.metrics;

import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SpokesmanKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SpokesmanStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SpokesmanValue;
import org.pragmatica.cluster.metrics.CommunityReport;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPong;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.SnapshotPayload;
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
import org.pragmatica.messaging.MessageReceiver;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Tier 2 sharded ping loop — runs on every core node that holds a Spokesman
/// duty (`SpokesmanKey(self).status == ACTIVE`) and fans out the leader's
/// relayed `ClusterGenerationSnapshot` to each assigned community's governor
/// every 500ms (configurable).
///
/// Responses (Tier 2 `ClusterSyncPong` from governors) are aggregated into a
/// `CommunityReport` per community. The resulting list is published via the
/// `ClusterSyncCollector` `communityReportSupplier` so that the core node's own
/// Tier 1 pong (leader-bound) can piggyback it.
///
/// Lifecycle:
///   - Dormant by default (Spokesman never ACTIVE).
///   - Activated on `ValuePut(SpokesmanKey(self), status=ACTIVE, communities≠[])`.
///   - Deactivated on `ValueRemove(SpokesmanKey(self))`, empty `communities`,
///     or status flip to `ASSIGNED | FAILED`.
///
/// See `aether/docs/specs/cluster-generation-spec.md` §7.3.
public interface SpokesmanPingLoop {
    @Contract void start();
    @Contract void stop();
    @MessageReceiver@Contract void onSpokesmanPut(ValuePut<SpokesmanKey, SpokesmanValue> notification);
    @MessageReceiver@Contract void onSpokesmanRemove(ValueRemove<SpokesmanKey, SpokesmanValue> notification);
    @MessageReceiver@Contract void onClusterSyncPong(ClusterSyncPong pong);
    boolean isActive();
    List<CommunityReport> currentReports();

    static SpokesmanPingLoop spokesmanPingLoop(NodeId self,
                                               ClusterNetwork network,
                                               TimeSpan interval,
                                               Supplier<Long> rabiaTermSupplier,
                                               Supplier<Option<ClusterGenerationSnapshot>> snapshotSupplier,
                                               Supplier<Map<NodeId, Map<String, Double>>> allMetricsSupplier,
                                               Function<String, Option<NodeId>> governorLookup,
                                               Function<ClusterGenerationSnapshot, byte[]> snapshotEncoder) {
        return spokesmanPingLoop(self,
                                 network,
                                 interval,
                                 rabiaTermSupplier,
                                 snapshotSupplier,
                                 allMetricsSupplier,
                                 governorLookup,
                                 snapshotEncoder,
                                 NoopSpokesmanStatusWriter.INSTANCE);
    }

    static SpokesmanPingLoop spokesmanPingLoop(NodeId self,
                                               ClusterNetwork network,
                                               TimeSpan interval,
                                               Supplier<Long> rabiaTermSupplier,
                                               Supplier<Option<ClusterGenerationSnapshot>> snapshotSupplier,
                                               Supplier<Map<NodeId, Map<String, Double>>> allMetricsSupplier,
                                               Function<String, Option<NodeId>> governorLookup,
                                               Function<ClusterGenerationSnapshot, byte[]> snapshotEncoder,
                                               SpokesmanStatusWriter statusWriter) {
        return new SpokesmanPingLoopImpl(self,
                                         network,
                                         interval,
                                         rabiaTermSupplier,
                                         snapshotSupplier,
                                         allMetricsSupplier,
                                         governorLookup,
                                         snapshotEncoder,
                                         statusWriter);
    }

    interface SpokesmanStatusWriter {
        @Contract void writeActive(NodeId self, SpokesmanValue baseValue);
        @Contract void writeFailure(NodeId self, SpokesmanValue baseValue, String reason);

        static SpokesmanStatusWriter fromCluster(ClusterNode<KVCommand<AetherKey>> cluster) {
            return new ClusterSpokesmanStatusWriter(cluster);
        }
    }
}

enum NoopSpokesmanStatusWriter implements SpokesmanPingLoop.SpokesmanStatusWriter {
    INSTANCE;
    @Contract@Override public void writeActive(NodeId self, SpokesmanValue baseValue) {}
    @Contract@Override public void writeFailure(NodeId self, SpokesmanValue baseValue, String reason) {}
}

record ClusterSpokesmanStatusWriter(ClusterNode<KVCommand<AetherKey>> cluster) implements SpokesmanPingLoop.SpokesmanStatusWriter {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ClusterSpokesmanStatusWriter.class);

    @Contract@Override public void writeActive(NodeId self, SpokesmanValue baseValue) {
        var active = baseValue.withStatus(SpokesmanStatus.ACTIVE);
        var command = new KVCommand.Put<AetherKey, AetherValue>(SpokesmanKey.spokesmanKey(self), active);
        cluster.apply(java.util.List.<KVCommand<AetherKey>>of(command))
                     .onFailure(cause -> LOG.warn("Failed to write SpokesmanValue ACTIVE for {}: {}",
                                                  self,
                                                  cause.message()));
    }

    @Contract@Override public void writeFailure(NodeId self, SpokesmanValue baseValue, String reason) {
        var failed = baseValue.withFailure(reason);
        var command = new KVCommand.Put<AetherKey, AetherValue>(SpokesmanKey.spokesmanKey(self), failed);
        cluster.apply(java.util.List.<KVCommand<AetherKey>>of(command))
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
    private final Supplier<Option<ClusterGenerationSnapshot>> snapshotSupplier;
    private final Supplier<Map<NodeId, Map<String, Double>>> allMetricsSupplier;
    private final Function<String, Option<NodeId>> governorLookup;
    private final Function<ClusterGenerationSnapshot, byte[]> snapshotEncoder;
    private final SpokesmanStatusWriter statusWriter;

    private final AtomicBoolean started = new AtomicBoolean(false);

    private final AtomicBoolean active = new AtomicBoolean(false);

    private final AtomicReference<List<String>> assignedCommunities = new AtomicReference<>(List.of());

    private final AtomicReference<Map<String, CommunityReport>> reports = new AtomicReference<>(Map.of());

    private final AtomicReference<Map<NodeId, String>> governorToCommunity = new AtomicReference<>(Map.of());

    private final CancellableTask task = CancellableTask.cancellableTask();

    SpokesmanPingLoopImpl(NodeId self,
                          ClusterNetwork network,
                          TimeSpan interval,
                          Supplier<Long> rabiaTermSupplier,
                          Supplier<Option<ClusterGenerationSnapshot>> snapshotSupplier,
                          Supplier<Map<NodeId, Map<String, Double>>> allMetricsSupplier,
                          Function<String, Option<NodeId>> governorLookup,
                          Function<ClusterGenerationSnapshot, byte[]> snapshotEncoder,
                          SpokesmanStatusWriter statusWriter) {
        this.self = self;
        this.network = network;
        this.interval = interval;
        this.rabiaTermSupplier = rabiaTermSupplier;
        this.snapshotSupplier = snapshotSupplier;
        this.allMetricsSupplier = allMetricsSupplier;
        this.governorLookup = governorLookup;
        this.snapshotEncoder = snapshotEncoder;
        this.statusWriter = statusWriter;
    }

    @Override@Contract public void start() {
        started.set(true);
    }

    @Override@Contract public void stop() {
        started.set(false);
        deactivate();
    }

    @Override@Contract public void onSpokesmanPut(ValuePut<SpokesmanKey, SpokesmanValue> notification) {
        if (!started.get()) {return;}
        if (!notification.cause().key()
                               .coreNodeId()
                               .equals(self)) {return;}
        var value = notification.cause().value();
        if (value.communities().isEmpty() || value.status() == SpokesmanStatus.FAILED) {
            deactivate();
            return;
        }
        if (value.status() == SpokesmanStatus.ACTIVE) {
            activate(value.communities());
            return;
        }
        handleAssigned(value);
    }

    @Contract private void handleAssigned(SpokesmanValue baseValue) {
        try {
            activate(baseValue.communities());
            statusWriter.writeActive(self, baseValue);
        } catch (RuntimeException ex) {
            log.warn("Activation failed on {} for communities {}: {}", self, baseValue.communities(), ex.getMessage());
            deactivate();
            statusWriter.writeFailure(self,
                                      baseValue,
                                      ex.getMessage() == null
                                      ? ex.getClass().getSimpleName()
                                      : ex.getMessage());
        }
    }

    @Override@Contract public void onSpokesmanRemove(ValueRemove<SpokesmanKey, SpokesmanValue> notification) {
        if (!notification.cause().key()
                               .coreNodeId()
                               .equals(self)) {return;}
        deactivate();
    }

    @Override@Contract public void onClusterSyncPong(ClusterSyncPong pong) {
        if (!active.get()) {return;}
        Option.option(governorToCommunity.get().get(pong.sender()))
                     .onPresent(communityId -> aggregatePong(communityId, pong));
    }

    @Override public boolean isActive() {
        return active.get();
    }

    @Override public List<CommunityReport> currentReports() {
        return List.copyOf(reports.get().values());
    }

    @Contract private void activate(List<String> communities) {
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

    @Contract private void deactivate() {
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
            if (communities.isEmpty()) {return;}
            var maybeSnapshot = snapshotSupplier.get();
            var rabiaTerm = rabiaTermSupplier.get();
            var epoch = maybeSnapshot.map(ClusterGenerationSnapshot::epoch).or(Epoch.ZERO);
            communities.forEach(communityId -> pingOneGovernor(communityId, rabiaTerm, epoch, maybeSnapshot));
        } catch (Exception e) {
            log.warn("SpokesmanPingLoop tick failed: {}", e.getMessage());
        }
    }

    private void pingOneGovernor(String communityId,
                                 long rabiaTerm,
                                 Epoch epoch,
                                 Option<ClusterGenerationSnapshot> maybeSnapshot) {
        governorLookup.apply(communityId).onPresent(governor -> sendPing(governor, rabiaTerm, epoch, maybeSnapshot));
    }

    private void sendPing(NodeId governor,
                          long rabiaTerm,
                          Epoch epoch,
                          Option<ClusterGenerationSnapshot> maybeSnapshot) {
        var payload = maybeSnapshot.map(snapshotEncoder::apply).map(SnapshotPayload::snapshotPayload);
        var ping = new ClusterSyncPing(self,
                                       allMetricsSupplier.get(),
                                       rabiaTerm,
                                       epoch.rabiaTerm(),
                                       epoch.localCounter(),
                                       payload);
        network.send(governor, ping);
    }

    private void aggregatePong(String communityId, ClusterSyncPong pong) {
        reports.updateAndGet(current -> mergeReport(current, communityId, pong));
    }

    private static Map<String, CommunityReport> mergeReport(Map<String, CommunityReport> current,
                                                            String communityId,
                                                            ClusterSyncPong pong) {
        var partitionsHeld = Option.option(current.get(communityId)).map(CommunityReport::partitionsHeld)
                                          .or(Set.of());
        var members = lifecycleCount(pong);
        var report = CommunityReport.communityReport(communityId,
                                                     0L,
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
        var healthy = "ON_DUTY".equals(state) || "JOINING".equals(state)
                     ? 1
                     : 0;
        var suspected = "DRAINING".equals(state)
                       ? 1
                       : 0;
        var faulty = "DECOMMISSIONED".equals(state) || "SHUTTING_DOWN".equals(state)
                    ? 1
                    : 0;
        return new LifecycleCounts(healthy + suspected + faulty, healthy, suspected, faulty);
    }

    private record LifecycleCounts(int total, int healthy, int suspected, int faulty){}
}
