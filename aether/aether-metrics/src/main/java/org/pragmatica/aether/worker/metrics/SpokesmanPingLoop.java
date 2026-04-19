// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.metrics;

import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey.SpokesmanKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.SpokesmanStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SpokesmanValue;
import org.pragmatica.cluster.metrics.CommunityReport;
import org.pragmatica.cluster.metrics.MetricsMessage.MetricsPing;
import org.pragmatica.cluster.metrics.MetricsMessage.MetricsPong;
import org.pragmatica.cluster.metrics.MetricsMessage.SnapshotPayload;
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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
/// Responses (Tier 2 `MetricsPong` from governors) are aggregated into a
/// `CommunityReport` per community. The resulting list is published via the
/// `MetricsCollector` `communityReportSupplier` so that the core node's own
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
    @MessageReceiver@Contract void onMetricsPong(MetricsPong pong);
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
        return new SpokesmanPingLoopImpl(self,
                                         network,
                                         interval,
                                         rabiaTermSupplier,
                                         snapshotSupplier,
                                         allMetricsSupplier,
                                         governorLookup,
                                         snapshotEncoder);
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

    private final AtomicBoolean started = new AtomicBoolean(false);

    private final AtomicBoolean active = new AtomicBoolean(false);

    private final AtomicReference<List<String>> assignedCommunities = new AtomicReference<>(List.of());

    private final Map<String, CommunityReport> reports = new ConcurrentHashMap<>();

    private final Map<NodeId, String> governorToCommunity = new ConcurrentHashMap<>();

    private final CancellableTask task = CancellableTask.cancellableTask();

    SpokesmanPingLoopImpl(NodeId self,
                          ClusterNetwork network,
                          TimeSpan interval,
                          Supplier<Long> rabiaTermSupplier,
                          Supplier<Option<ClusterGenerationSnapshot>> snapshotSupplier,
                          Supplier<Map<NodeId, Map<String, Double>>> allMetricsSupplier,
                          Function<String, Option<NodeId>> governorLookup,
                          Function<ClusterGenerationSnapshot, byte[]> snapshotEncoder) {
        this.self = self;
        this.network = network;
        this.interval = interval;
        this.rabiaTermSupplier = rabiaTermSupplier;
        this.snapshotSupplier = snapshotSupplier;
        this.allMetricsSupplier = allMetricsSupplier;
        this.governorLookup = governorLookup;
        this.snapshotEncoder = snapshotEncoder;
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
        if (value.status() == SpokesmanStatus.ACTIVE && !value.communities().isEmpty()) {activate(value.communities());} else {deactivate();}
    }

    @Override@Contract public void onSpokesmanRemove(ValueRemove<SpokesmanKey, SpokesmanValue> notification) {
        if (!notification.cause().key()
                               .coreNodeId()
                               .equals(self)) {return;}
        deactivate();
    }

    @Override@Contract public void onMetricsPong(MetricsPong pong) {
        if (!active.get()) {return;}
        var communityId = governorToCommunity.get(pong.sender());
        if (communityId == null) {return;}
        aggregatePong(communityId, pong);
    }

    @Override public boolean isActive() {
        return active.get();
    }

    @Override public List<CommunityReport> currentReports() {
        return List.copyOf(reports.values());
    }

    @Contract private void activate(List<String> communities) {
        assignedCommunities.set(List.copyOf(communities));
        rebuildGovernorIndex(communities);
        if (active.compareAndSet(false, true)) {
            task.set(SharedScheduler.scheduleAtFixedRate(this::tick, interval));
            log.info("SpokesmanPingLoop activated on {} with communities {}", self, communities);
        }
    }

    @Contract private void deactivate() {
        if (active.compareAndSet(true, false)) {
            task.cancel();
            reports.clear();
            governorToCommunity.clear();
            assignedCommunities.set(List.of());
            log.info("SpokesmanPingLoop deactivated on {}", self);
        }
    }

    private void rebuildGovernorIndex(List<String> communities) {
        governorToCommunity.clear();
        communities.forEach(communityId -> governorLookup.apply(communityId)
                                                               .onPresent(governor -> governorToCommunity.put(governor,
                                                                                                              communityId)));
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
        var ping = new MetricsPing(self,
                                   allMetricsSupplier.get(),
                                   rabiaTerm,
                                   epoch.rabiaTerm(),
                                   epoch.localCounter(),
                                   payload);
        network.send(governor, ping);
    }

    private void aggregatePong(String communityId, MetricsPong pong) {
        var existing = reports.get(communityId);
        var governorId = pong.sender();
        var partitionsHeld = existing == null
                            ? Set.<String>of()
                            : existing.partitionsHeld();
        var members = lifecycleCount(pong);
        var report = CommunityReport.communityReport(communityId,
                                                     0L,
                                                     pong.observedEpochTerm(),
                                                     pong.observedEpochCounter(),
                                                     governorId,
                                                     members.total(),
                                                     members.healthy(),
                                                     members.suspected(),
                                                     members.faulty(),
                                                     partitionsHeld,
                                                     System.currentTimeMillis());
        reports.put(communityId, report);
    }

    private static LifecycleCounts lifecycleCount(MetricsPong pong) {
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
