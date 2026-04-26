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


/// Theme K #4 — periodic garbage collection of `NodeLifecycleValue(state == DECOMMISSIONED)`
/// atoms. Without this sweep, terminated nodes accumulate as tombstones over the cluster's
/// lifetime, eventually bloating the KV-Store and the leader-side projection scans.
///
/// Cadence and gating:
///   - Runs only on the leader (`isLeaderSupplier`); a CAS check at task entry skips the
///     work on non-leaders so a slow leadership transition does not race the sweep.
///   - Period = `decommissionedRetention / 2` capped to `MAX_GC_PERIOD` so a 24h retention
///     produces a 12h sweep cadence; the start delay is `period` so multiple leaders staggered
///     in time do not synchronize their first sweeps.
///   - Each sweep scans the snapshot once, accumulates `KVCommand.Remove` for atoms whose
///     `updatedAt` is older than `now - decommissionedRetention`, and submits them as a single
///     consensus batch.
///
/// The `DECOMMISSIONED` state is terminal: an atom with a recent `updatedAt` is a node freshly
/// terminated and we keep it for the retention window so operators can audit recent
/// terminations. After the retention window the atom serves no further purpose — the
/// projection no longer references the node, and any consensus replays since the
/// `DECOMMISSIONED` write are now part of the committed log.
public final class DecommissionedAtomGc {
    private static final Logger log = LoggerFactory.getLogger(DecommissionedAtomGc.class);

    /// Upper bound on the GC period — even with multi-day retention the sweep runs at
    /// least once per hour so a config change reducing the retention is observed promptly.
    private static final TimeSpan MAX_GC_PERIOD = TimeSpan.timeSpan(1).hours();

    /// Lower bound on the GC period — protects the consensus apply path from being hammered
    /// when retention is set to a short value (e.g., minutes for tests).
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
        return decommissionedAtomGc(cluster, kvSnapshotSupplier, isLeaderSupplier, autoHealConfig, System::currentTimeMillis);
    }

    /// Full-arity factory with injectable clock — used by tests for deterministic time.
    public static DecommissionedAtomGc decommissionedAtomGc(ClusterNode<KVCommand<AetherKey>> cluster,
                                                             Supplier<Map<AetherKey, AetherValue>> kvSnapshotSupplier,
                                                             BooleanSupplier isLeaderSupplier,
                                                             AutoHealConfig autoHealConfig,
                                                             LongSupplier clock) {
        return new DecommissionedAtomGc(cluster, kvSnapshotSupplier, isLeaderSupplier, autoHealConfig, clock);
    }

    /// Computes the GC tick period from the configured retention. Half of retention,
    /// clamped to `[MIN_GC_PERIOD, MAX_GC_PERIOD]` so neither very short nor very long
    /// retentions destabilize the sweep cadence.
    private TimeSpan computePeriod() {
        var halfRetentionMs = autoHealConfig.decommissionedRetention().millis() / 2L;
        var clamped = Math.max(MIN_GC_PERIOD.millis(), Math.min(MAX_GC_PERIOD.millis(), halfRetentionMs));
        return TimeSpan.timeSpan(clamped).millis();
    }

    /// Starts the periodic sweep. Idempotent: subsequent calls are no-ops while a sweep
    /// timer is already armed.
    public void start() {
        var period = computePeriod();
        log.info("DecommissionedAtomGc starting: retention={}, period={}",
                 autoHealConfig.decommissionedRetention(),
                 period);
        timer.set(SharedScheduler.scheduleAtFixedRate(this::tick, period, period));
    }

    public void stop() {
        timer.cancel();
    }

    /// Single sweep pass — public so tests can drive it deterministically without waiting
    /// on the scheduler.
    public Promise<Long> tick() {
        if (!isLeaderSupplier.getAsBoolean()) {return Promise.success(0L);}
        var commands = collectExpiredAtoms();
        if (commands.isEmpty()) {return Promise.success(0L);}
        log.info("DecommissionedAtomGc: removing {} stale DECOMMISSIONED atom(s) past {} retention",
                 commands.size(),
                 autoHealConfig.decommissionedRetention());
        return cluster.apply(commands)
                       .onFailure(cause -> log.warn("DecommissionedAtomGc: consensus apply failed for {} command(s): {}",
                                                     commands.size(),
                                                     cause.message()))
                       .map(_ -> (long) commands.size());
    }

    private List<KVCommand<AetherKey>> collectExpiredAtoms() {
        var nowMs = clock.getAsLong();
        var cutoffMs = nowMs - autoHealConfig.decommissionedRetention().millis();
        var commands = new ArrayList<KVCommand<AetherKey>>();
        // Widen to raw Map<?, ?> so javac does not insert a checkcast AetherKey on
        // each entry — the merged KV may contain LeaderKey/LeaderValue (consensus
        // layer, sibling sealed hierarchy). instanceof handles cross-hierarchy keys.
        Map<?, ?> raw = kvSnapshotSupplier.get();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            appendIfExpired(entry.getKey(), entry.getValue(), cutoffMs, commands);
        }
        return List.copyOf(commands);
    }

    private static void appendIfExpired(Object key,
                                         Object value,
                                         long cutoffMs,
                                         List<KVCommand<AetherKey>> commands) {
        if (! (key instanceof NodeLifecycleKey lifecycleKey)) {return;}
        if (! (value instanceof NodeLifecycleValue lifecycle)) {return;}
        if (lifecycle.state() != NodeLifecycleState.DECOMMISSIONED) {return;}
        if (lifecycle.updatedAt() >= cutoffMs) {return;}
        commands.add(new KVCommand.Remove<AetherKey>(lifecycleKey));
    }
}
