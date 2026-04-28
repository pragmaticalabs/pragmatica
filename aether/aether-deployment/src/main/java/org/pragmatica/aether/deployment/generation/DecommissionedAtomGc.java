// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.concurrent.CancellableTask;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public final class DecommissionedAtomGc {
    private static final Logger log = LoggerFactory.getLogger(DecommissionedAtomGc.class);

    private static final TimeSpan MAX_GC_PERIOD = TimeSpan.timeSpan(1).hours();

    private static final TimeSpan MIN_GC_PERIOD = TimeSpan.timeSpan(5).seconds();

    private final ClusterNode<KVCommand<AetherKey>> cluster;
    private final Supplier<Map<AetherKey, AetherValue>> kvSnapshotSupplier;
    private final BooleanSupplier isLeaderSupplier;
    private final AutoHealConfig autoHealConfig;
    private final LongSupplier clock;
    private final CancellableTask timer;

    private DecommissionedAtomGc(ClusterNode<KVCommand<AetherKey>> cluster,
                                 Supplier<Map<AetherKey, AetherValue>> kvSnapshotSupplier,
                                 BooleanSupplier isLeaderSupplier,
                                 AutoHealConfig autoHealConfig,
                                 LongSupplier clock) {
        this.cluster = cluster;
        this.kvSnapshotSupplier = kvSnapshotSupplier;
        this.isLeaderSupplier = isLeaderSupplier;
        this.autoHealConfig = autoHealConfig;
        this.clock = clock;
        this.timer = CancellableTask.cancellableTask();
    }

    public static DecommissionedAtomGc decommissionedAtomGc(ClusterNode<KVCommand<AetherKey>> cluster,
                                                            Supplier<Map<AetherKey, AetherValue>> kvSnapshotSupplier,
                                                            BooleanSupplier isLeaderSupplier,
                                                            AutoHealConfig autoHealConfig) {
        return decommissionedAtomGc(cluster,
                                    kvSnapshotSupplier,
                                    isLeaderSupplier,
                                    autoHealConfig,
                                    System::currentTimeMillis);
    }

    public static DecommissionedAtomGc decommissionedAtomGc(ClusterNode<KVCommand<AetherKey>> cluster,
                                                            Supplier<Map<AetherKey, AetherValue>> kvSnapshotSupplier,
                                                            BooleanSupplier isLeaderSupplier,
                                                            AutoHealConfig autoHealConfig,
                                                            LongSupplier clock) {
        return new DecommissionedAtomGc(cluster, kvSnapshotSupplier, isLeaderSupplier, autoHealConfig, clock);
    }

    private TimeSpan computePeriod() {
        var halfRetentionMs = autoHealConfig.decommissionedRetention().millis() / 2L;
        var clamped = Math.max(MIN_GC_PERIOD.millis(),
                               Math.min(MAX_GC_PERIOD.millis(), halfRetentionMs));
        return TimeSpan.timeSpan(clamped).millis();
    }

    @Contract public void start() {
        var period = computePeriod();
        log.info("DecommissionedAtomGc starting: retention={}, period={}",
                 autoHealConfig.decommissionedRetention(),
                 period);
        timer.set(SharedScheduler.scheduleAtFixedRate(this::tick, period, period));
    }

    @Contract public void stop() {
        timer.cancel();
    }

    public Promise<Long> tick() {
        if (!isLeaderSupplier.getAsBoolean()) {return Promise.success(0L);}
        var commands = collectExpiredAtoms();
        if (commands.isEmpty()) {return Promise.success(0L);}
        log.info("DecommissionedAtomGc: removing {} stale DECOMMISSIONED atom(s) past {} retention",
                 commands.size(),
                 autoHealConfig.decommissionedRetention());
        return cluster.apply(commands).onFailure(cause -> log.warn("DecommissionedAtomGc: consensus apply failed for {} command(s): {}",
                                                                   commands.size(),
                                                                   cause.message()))
                            .map(_ -> (long) commands.size());
    }

    private List<KVCommand<AetherKey>> collectExpiredAtoms() {
        var nowMs = clock.getAsLong();
        var cutoffMs = nowMs - autoHealConfig.decommissionedRetention().millis();
        var commands = new ArrayList<KVCommand<AetherKey>>();
        Map<?, ?> raw = kvSnapshotSupplier.get();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {appendIfExpired(entry.getKey(),
                                                                      entry.getValue(),
                                                                      cutoffMs,
                                                                      commands);}
        return List.copyOf(commands);
    }

    private static void appendIfExpired(Object key, Object value, long cutoffMs, List<KVCommand<AetherKey>> commands) {
        if (! (key instanceof NodeLifecycleKey lifecycleKey)) {return;}
        if (! (value instanceof NodeLifecycleValue lifecycle)) {return;}
        if (lifecycle.state() != NodeLifecycleState.DECOMMISSIONED) {return;}
        if (lifecycle.updatedAt() >= cutoffMs) {return;}
        commands.add(new KVCommand.Remove<AetherKey>(lifecycleKey));
    }
}
