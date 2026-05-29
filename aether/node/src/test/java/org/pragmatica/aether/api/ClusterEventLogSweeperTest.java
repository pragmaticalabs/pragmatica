// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterEventLogKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterEventValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConfigValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;


/// RC1 Step 1 — sweeper unit tests.
///
/// Covered:
/// - leader-only: non-leader nodes never issue Remove commands
/// - **inQuorum gate**: minority-side leader does NOT delete (spec §3.6 risk #5)
/// - retention boundary: events with `epoch >= currentEpoch - retainedEpochs` are NEVER
///   dropped, regardless of how many ticks run
/// - tick targets only `ClusterEventLogKey` (does not delete unrelated keys)
class ClusterEventLogSweeperTest {

    private static final long RETAINED_EPOCHS = 4L;

    private static final TimeSpan PERIOD = TimeSpan.timeSpan(1).seconds();

    private static ClusterEventValue value(long epoch, long seq) {
        return ClusterEventValue.clusterEventValue(HlcTimestamp.ZERO,
                                                    ClusterEventValue.EventType.NODE_JOINED,
                                                    ClusterEventValue.Severity.INFO,
                                                    "node",
                                                    "epoch=" + epoch + " seq=" + seq,
                                                    Map.of());
    }

    private static final class CapturingApplier implements java.util.function.Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> {
        final List<KVCommand<AetherKey>> captured = new ArrayList<>();

        @Override public Promise<List<Object>> apply(List<KVCommand<AetherKey>> commands) {
            captured.addAll(commands);
            return Promise.success(List.of());
        }
    }

    private static final NodeId NODE_A = new NodeId("sweeper-test-node");

    private static Map<AetherKey, AetherValue> buildLog(long lowestEpoch, long highestEpoch) {
        var map = new HashMap<AetherKey, AetherValue>();
        for (var e = lowestEpoch; e <= highestEpoch; e++) {
            map.put(ClusterEventLogKey.clusterEventLogKey(e, NODE_A, 0L), value(e, 0L));
            map.put(ClusterEventLogKey.clusterEventLogKey(e, NODE_A, 1L), value(e, 1L));
        }
        return map;
    }

    @Test
    void tick_nonLeader_neverDeletes() {
        var snap = buildLog(1L, 10L);
        var applier = new CapturingApplier();
        var sweeper = ClusterEventLogSweeper.clusterEventLogSweeper(() -> snap,
                                                                      () -> false,   // not leader
                                                                      () -> true,    // in quorum
                                                                      () -> 100L,    // currentEpoch
                                                                      RETAINED_EPOCHS,
                                                                      applier,
                                                                      PERIOD);

        var removed = sweeper.tick().await().unwrap();

        assertThat(removed).isZero();
        assertThat(applier.captured).isEmpty();
    }

    @Test
    void tick_minoritySideLeader_neverDeletes() {
        var snap = buildLog(1L, 10L);
        var applier = new CapturingApplier();
        var sweeper = ClusterEventLogSweeper.clusterEventLogSweeper(() -> snap,
                                                                      () -> true,    // is leader
                                                                      () -> false,   // BUT minority side
                                                                      () -> 100L,
                                                                      RETAINED_EPOCHS,
                                                                      applier,
                                                                      PERIOD);

        var removed = sweeper.tick().await().unwrap();

        assertThat(removed).isZero();
        assertThat(applier.captured).isEmpty();
    }

    @Test
    void tick_leaderAndQuorate_deletesOnlyKeysOlderThanCutoff() {
        // currentEpoch=10, retain=4 → cutoff=6. Epochs [1..5] should be deleted (10 entries
        // since 2 per epoch); epoch 6+ stay.
        var snap = buildLog(1L, 10L);
        var applier = new CapturingApplier();
        var sweeper = ClusterEventLogSweeper.clusterEventLogSweeper(() -> snap,
                                                                      () -> true,
                                                                      () -> true,
                                                                      () -> 10L,
                                                                      RETAINED_EPOCHS,
                                                                      applier,
                                                                      PERIOD);

        var removed = sweeper.tick().await().unwrap();

        assertThat(removed).isEqualTo(10L);   // epochs 1..5, 2 keys each
        for (var cmd : applier.captured) {
            var remove = (KVCommand.Remove<?>) cmd;
            var key = (ClusterEventLogKey) remove.key();
            assertThat(key.epoch()).isLessThan(6L);
        }
    }

    @Test
    void tick_retentionBoundary_keepsExactlyRetainedEpochs() {
        // currentEpoch=10, retain=4 → cutoff=6. Keys with epoch>=6 NEVER deleted.
        var snap = buildLog(1L, 10L);
        var applier = new CapturingApplier();
        var sweeper = ClusterEventLogSweeper.clusterEventLogSweeper(() -> snap,
                                                                      () -> true,
                                                                      () -> true,
                                                                      () -> 10L,
                                                                      RETAINED_EPOCHS,
                                                                      applier,
                                                                      PERIOD);

        sweeper.tick().await().unwrap();

        for (var cmd : applier.captured) {
            var key = (ClusterEventLogKey) ((KVCommand.Remove<?>) cmd).key();
            assertThat(key.epoch())
                .as("retained epoch boundary: epoch must be < cutoff (6)")
                .isLessThan(6L);
        }
    }

    @Test
    void tick_currentEpochUnderRetention_deletesNothing() {
        // currentEpoch=2, retain=4 → cutoff=-2 (i.e. <=0). Sweeper bails early.
        var snap = buildLog(1L, 2L);
        var applier = new CapturingApplier();
        var sweeper = ClusterEventLogSweeper.clusterEventLogSweeper(() -> snap,
                                                                      () -> true,
                                                                      () -> true,
                                                                      () -> 2L,
                                                                      RETAINED_EPOCHS,
                                                                      applier,
                                                                      PERIOD);

        var removed = sweeper.tick().await().unwrap();

        assertThat(removed).isZero();
        assertThat(applier.captured).isEmpty();
    }

    @Test
    void tick_ignoresNonEventLogKeys() {
        // KV-Store mixes ClusterEventLogKey with an unrelated key family. Sweeper must touch
        // only its own family.
        var snap = new HashMap<AetherKey, AetherValue>();
        snap.put(ClusterEventLogKey.clusterEventLogKey(1L, NODE_A, 0L), value(1L, 0L));
        snap.put(ConfigKey.forKey("timeout"), ConfigValue.configValue("timeout", "5000"));

        var applier = new CapturingApplier();
        var sweeper = ClusterEventLogSweeper.clusterEventLogSweeper(() -> snap,
                                                                      () -> true,
                                                                      () -> true,
                                                                      () -> 10L,
                                                                      RETAINED_EPOCHS,
                                                                      applier,
                                                                      PERIOD);

        sweeper.tick().await().unwrap();

        assertThat(applier.captured).hasSize(1);
        var remove = (KVCommand.Remove<?>) applier.captured.getFirst();
        assertThat(remove.key()).isInstanceOf(ClusterEventLogKey.class);
    }
}
