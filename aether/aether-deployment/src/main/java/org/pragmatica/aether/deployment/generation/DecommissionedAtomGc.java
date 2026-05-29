// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GenerationSnapshotKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GenerationSnapshotValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.concurrent.CancellableTask;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final CancellableTask timer;

    /// RC1 membership-v2 step 1: `clock` is accepted for factory-signature compatibility
    /// (production + test callers still pass it) but no longer read — GC eligibility is now
    /// membership-presence, not a retention cutoff on `NodeLifecycleValue.updatedAt`. The
    /// `decommissionedRetention` config still governs the sweep period (see `computePeriod`).
    private DecommissionedAtomGc(ClusterNode<KVCommand<AetherKey>> cluster,
                                 Supplier<Map<AetherKey, AetherValue>> kvSnapshotSupplier,
                                 BooleanSupplier isLeaderSupplier,
                                 AutoHealConfig autoHealConfig,
                                 @SuppressWarnings("unused") LongSupplier clock) {
        this.cluster = cluster;
        this.kvSnapshotSupplier = kvSnapshotSupplier;
        this.isLeaderSupplier = isLeaderSupplier;
        this.autoHealConfig = autoHealConfig;
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

    @Contract
    public void start() {
        var period = computePeriod();
        log.info("DecommissionedAtomGc starting: retention={}, period={}",
                 autoHealConfig.decommissionedRetention(),
                 period);
        timer.set(SharedScheduler.scheduleAtFixedRate(this::tick, period, period));
    }

    @Contract
    public void stop() {
        timer.cancel();
    }

    public Promise<Long> tick() {
        if (!isLeaderSupplier.getAsBoolean()) {return Promise.success(0L);}

        var commands = collectExpiredAtoms();

        if (commands.isEmpty()) {return Promise.success(0L);}

        log.info("DecommissionedAtomGc: removing {} lifecycle atom(s) for node(s) absent from current membership",
                 commands.size());

        return cluster.apply(commands)
                      .onFailure(cause -> log.warn("DecommissionedAtomGc: consensus apply failed for {} command(s): {}",
                                                   commands.size(),
                                                   cause.message()))
                      .map(_ -> (long) commands.size());
    }

    /// RC1 membership-v2 step 1: re-sourced off the FSM-written `NodeLifecycleValue.state`.
    /// A node is "gone" when it is no longer present in the NTT-derived membership — i.e. its
    /// `nodeId` is absent from the generation snapshot's `coreMembers`. Any leftover
    /// `NodeLifecycleKey` atom for such a node is GC'd. The snapshot is read from the same KV
    /// map already supplied (stored under `GenerationSnapshotKey.SINGLETON`), so no extra
    /// dependency is wired. When the snapshot is not yet present (cold boot), nothing is
    /// collected — GC must never run against an unknown membership.
    private List<KVCommand<AetherKey>> collectExpiredAtoms() {
        Map<AetherKey, AetherValue> raw = kvSnapshotSupplier.get();
        var membership = currentMembership(raw);

        if (membership.isEmpty()) {return List.of();}

        var members = membership.unwrap();
        var commands = new ArrayList<KVCommand<AetherKey>>();

        for (Map.Entry<AetherKey, AetherValue> entry : raw.entrySet()) {
            appendIfAbsentFromMembership(entry.getKey(), members, commands);
        }

        return List.copyOf(commands);
    }

    private static Option<Set<NodeId>> currentMembership(Map<AetherKey, AetherValue> raw) {
        return Option.option(raw.get(GenerationSnapshotKey.SINGLETON))
                     .filter(value -> value instanceof GenerationSnapshotValue)
                     .map(value -> ((GenerationSnapshotValue) value).snapshot())
                     .map(ClusterGenerationSnapshot::coreMembers)
                     .map(Map::keySet);
    }

    private static void appendIfAbsentFromMembership(AetherKey key,
                                                     Set<NodeId> members,
                                                     List<KVCommand<AetherKey>> commands) {
        if (! (key instanceof NodeLifecycleKey lifecycleKey)) {return;}
        if (members.contains(lifecycleKey.nodeId())) {return;}

        commands.add(new KVCommand.Remove<AetherKey>(lifecycleKey));
    }
}
