// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterEventLogKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
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
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// RC1 Step 1 — leader-only, quorum-gated sweeper for the cluster-scoped event log.
///
/// Each tick:
/// 1. Bail if not leader (only one node drives sweep, prevents N-fold delete fan-out).
/// 2. **Bail if not in quorum** — see `inQuorum` field doc. A minority-side leader without
///    this gate would issue `Remove` commands that the majority's surviving leader has not
///    issued, racing on rejoin and risking data loss.
/// 3. Collect all `ClusterEventLogKey` atoms with `epoch < currentEpoch - retainedEpochs`.
/// 4. Submit `KVCommand.Remove` batch via consensus.
///
/// Pattern mirrors `DecommissionedAtomGc` deliberately — same lifecycle, same applier shape.
public final class ClusterEventLogSweeper {
    private static final Logger log = LoggerFactory.getLogger(ClusterEventLogSweeper.class);

    /// Default retention: 4 epochs back. With `currentEpoch` tracking Rabia leader-term, this
    /// gives several minutes of history at typical churn — long enough to debug a single-incident
    /// cluster event sequence on `/api/events`, short enough that the KV-Store snapshot does not
    /// grow unboundedly.
    public static final long DEFAULT_RETAINED_EPOCHS = 4L;

    /// Default sweep period: 30 seconds. Faster than DecommissionedAtomGc because events arrive
    /// orders of magnitude more frequently than lifecycle transitions.
    public static final TimeSpan DEFAULT_SWEEP_PERIOD = TimeSpan.timeSpan(30).seconds();

    private final Supplier<Map<AetherKey, AetherValue>> kvSnapshotSupplier;

    private final BooleanSupplier isLeaderSupplier;

    /// **Quorum gate** — supplier returns `true` iff this node is on the majority side of any
    /// active partition. Without this gate, a minority-side leader would sweep events the
    /// majority still retains — see `TopologyObserver.inQuorum()` and spec §3.6 risk #5.
    private final BooleanSupplier inQuorum;
    private final LongSupplier currentEpochSupplier;
    private final long retainedEpochs;
    private final Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier;
    private final TimeSpan period;
    private final CancellableTask timer;

    private ClusterEventLogSweeper(Supplier<Map<AetherKey, AetherValue>> kvSnapshotSupplier,
                                   BooleanSupplier isLeaderSupplier,
                                   BooleanSupplier inQuorum,
                                   LongSupplier currentEpochSupplier,
                                   long retainedEpochs,
                                   Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                   TimeSpan period) {
        this.kvSnapshotSupplier = kvSnapshotSupplier;
        this.isLeaderSupplier = isLeaderSupplier;
        this.inQuorum = inQuorum;
        this.currentEpochSupplier = currentEpochSupplier;
        this.retainedEpochs = retainedEpochs;
        this.applier = applier;
        this.period = period;
        this.timer = CancellableTask.cancellableTask();
    }

    public static ClusterEventLogSweeper clusterEventLogSweeper(Supplier<Map<AetherKey, AetherValue>> kvSnapshotSupplier,
                                                                BooleanSupplier isLeaderSupplier,
                                                                BooleanSupplier inQuorum,
                                                                LongSupplier currentEpochSupplier,
                                                                Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier) {
        return new ClusterEventLogSweeper(kvSnapshotSupplier,
                                          isLeaderSupplier,
                                          inQuorum,
                                          currentEpochSupplier,
                                          DEFAULT_RETAINED_EPOCHS,
                                          applier,
                                          DEFAULT_SWEEP_PERIOD);
    }

    public static ClusterEventLogSweeper clusterEventLogSweeper(Supplier<Map<AetherKey, AetherValue>> kvSnapshotSupplier,
                                                                BooleanSupplier isLeaderSupplier,
                                                                BooleanSupplier inQuorum,
                                                                LongSupplier currentEpochSupplier,
                                                                long retainedEpochs,
                                                                Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                                                TimeSpan period) {
        return new ClusterEventLogSweeper(kvSnapshotSupplier,
                                          isLeaderSupplier,
                                          inQuorum,
                                          currentEpochSupplier,
                                          retainedEpochs,
                                          applier,
                                          period);
    }

    @Contract
    public void start() {
        log.info("ClusterEventLogSweeper starting: retainedEpochs={}, period={}", retainedEpochs, period);
        timer.set(SharedScheduler.scheduleAtFixedRate(this::tick, period, period));
    }

    @Contract
    public void stop() {
        timer.cancel();
    }

    public Promise<Long> tick() {
        if (!isLeaderSupplier.getAsBoolean()) {return Promise.success(0L);}
        if (!inQuorum.getAsBoolean()) {
            log.debug("ClusterEventLogSweeper: leader but minority-side (inQuorum=false), skipping sweep");

            return Promise.success(0L);
        }

        var currentEpoch = currentEpochSupplier.getAsLong();
        var cutoffEpoch = currentEpoch - retainedEpochs;

        if (cutoffEpoch <= 0L) {return Promise.success(0L);}

        var commands = collectExpired(cutoffEpoch);

        if (commands.isEmpty()) {return Promise.success(0L);}

        log.info("ClusterEventLogSweeper: removing {} event(s) with epoch < {} (currentEpoch={}, retainedEpochs={})",
                 commands.size(),
                 cutoffEpoch,
                 currentEpoch,
                 retainedEpochs);

        return applier.apply(commands)
                      .onFailure(cause -> log.warn("ClusterEventLogSweeper: consensus apply failed for {} command(s): {}",
                                                   commands.size(),
                                                   cause.message()))
                      .map(_ -> (long) commands.size());
    }

    private List<KVCommand<AetherKey>> collectExpired(long cutoffEpoch) {
        var commands = new ArrayList<KVCommand<AetherKey>>();
        Map<?, ?> raw = kvSnapshotSupplier.get();

        for (Map.Entry<?, ?> entry : raw.entrySet()) {appendIfExpired(entry.getKey(), cutoffEpoch, commands);}

        return List.copyOf(commands);
    }

    private static void appendIfExpired(Object key, long cutoffEpoch, List<KVCommand<AetherKey>> commands) {
        if (! (key instanceof ClusterEventLogKey eventLogKey)) {return;}
        if (eventLogKey.epoch() >= cutoffEpoch) {return;}

        commands.add(new KVCommand.Remove<AetherKey>(eventLogKey));
    }
}
