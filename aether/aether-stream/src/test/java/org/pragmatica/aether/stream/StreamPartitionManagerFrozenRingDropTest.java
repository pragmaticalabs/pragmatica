// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.resource.DurableTopicSpec;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.aether.stream.topic.DurableTopicSubstrate;
import org.pragmatica.aether.stream.wal.PartitionWal;
import org.pragmatica.aether.stream.wal.PartitionWal.WalRecord;
import org.pragmatica.lang.Option;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #1233: a DROP_OLDEST (EVENTUAL) ring frozen at its floor segment (growth refused by the pool) cannot
/// store an event larger than its allocation. Before the fix the ring reported success at the PREVIOUS
/// head's offset without storing anything, and `publishLocal` then WAL-wrote (and replicated) the dropped
/// payload under that offset — the publisher was acked for an event that does not exist, and a restart
/// replayed the phantom as `head + 1`, shifting every later offset.
///
/// The drop must FAIL the publish for anything with durability semantics — `minSyncReplicas >= 2`
/// (durable topics and their DLQs are parse-enforced to `min-sync == replicas >= 2`) OR a partition WAL.
/// So is any entity keyspace log (`entity:`) or durable-topic / DLQ stream (`topic:`), identified by name.
/// Each disjunct is pinned by its own test so none can be weakened unnoticed. Any other stream is
/// best-effort: the drop is absorbed, counted and logged, and never stored.
class StreamPartitionManagerFrozenRingDropTest {

    private static final int PARTITION = 0;
    private static final int OVERSIZED = 300_000;   // > 256 KiB floor segment, < 1 MiB max event size
    private static final int STORED_BEFORE = 3;

    @TempDir
    Path walDir;

    /// The ticket's acceptance test: durable-topic config + WAL + a pool that refuses growth.
    @Test
    void publishLocal_fails_andLeavesWalAndOffsetsIntact_whenFrozenDurableTopicRingCannotFitEvent() {
        var config = durableTopicConfig();
        var manager = streamPartitionManager(floorBudget(config), Option.some(walDir));
        create(manager, config);

        IntStream.range(0, STORED_BEFORE).forEach(i -> publishExpecting(manager, config, i, i));
        var walLastOffsetBefore = walLastOffset(config);
        var walRecordsBefore = walRecords(config).size();

        manager.publishLocal(config.name(), PARTITION, new byte[OVERSIZED], 9_999L)
               .onSuccess(offset -> fail("a dropped event on a durable topic must fail the publish, but it"
                                         + " was acked at offset " + offset))
               .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.EVENT_DROPPED));
        assertThat(manager.droppedEventsSinceBoot()).as("a failed publish is not a best-effort drop").isZero();

        assertThat(walLastOffset(config)).as("WAL lastOffset after the drop").isEqualTo(walLastOffsetBefore);
        assertThat(walRecords(config)).as("a dropped event must never reach the WAL (not even at the old"
                                          + " head's offset)")
                                      .hasSize(walRecordsBefore);

        // The next real event takes the next contiguous offset.
        publishExpecting(manager, config, STORED_BEFORE, STORED_BEFORE);
        manager.close();

        assertRebuiltRingHoldsExactly(config, STORED_BEFORE + 1);
    }

    /// `minSyncReplicas >= 2` alone (no WAL wired) is durability semantics: the drop fails the publish.
    @Test
    void publishLocal_fails_whenFrozenRingCannotFitEvent_onMinSyncTwoStreamWithoutWal() {
        var config = durableTopicConfig();
        var manager = streamPartitionManager(floorBudget(config), Option.none());
        create(manager, config);

        publishExpecting(manager, config, 0, 0);

        manager.publishLocal(config.name(), PARTITION, new byte[OVERSIZED], 9_999L)
               .onSuccess(offset -> fail("min-sync >= 2 drop must fail the publish, but it was acked at "
                                         + offset))
               .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.EVENT_DROPPED));

        publishExpecting(manager, config, 1, 1);
        manager.close();
    }

    /// A partition WAL alone (min-sync 0) is durability semantics: the drop fails the publish and the
    /// WAL never records it.
    @Test
    void publishLocal_fails_andWritesNoWalRecord_whenFrozenRingCannotFitEvent_onWalBackedStream() {
        var config = plainConfig();
        var manager = streamPartitionManager(floorBudget(config), Option.some(walDir));
        create(manager, config);

        publishExpecting(manager, config, 0, 0);

        manager.publishLocal(config.name(), PARTITION, new byte[OVERSIZED], 9_999L)
               .onSuccess(offset -> fail("a drop on a WAL-backed stream must fail the publish, but it was"
                                         + " acked at " + offset))
               .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.EVENT_DROPPED));

        assertThat(walRecords(config)).as("dropped event must not be WAL-written").hasSize(1);
        publishExpecting(manager, config, 1, 1);
        manager.close();

        assertRebuiltRingHoldsExactly(config, 2);
    }

    /// An entity keyspace log (`entity:<keyspace>`) is durable state even at RF=1 with no WAL (Forge,
    /// embedded, or the non-durable opt-in): acking a dropped record would report a lost write as
    /// committed. The drop fails the publish on the stream NAME alone.
    @Test
    void publishLocal_fails_whenFrozenRingCannotFitEvent_onEntityStreamWithoutWalOrMinSync() {
        assertDropFailsWithoutWal(namedConfig("entity:ledger-1233"));
    }

    /// A durable-topic stream or its DLQ (`topic:<address>`, `topic:<address>.dlq`) fails the drop on the
    /// stream NAME alone, independent of the parse-enforced `min-sync >= 2`.
    @Test
    void publishLocal_fails_whenFrozenRingCannotFitEvent_onTopicAndDlqStreamsWithoutWalOrMinSync() {
        assertDropFailsWithoutWal(namedConfig("topic:orders-1233"));
        assertDropFailsWithoutWal(namedConfig("topic:orders-1233.dlq"));
    }

    /// Bring-up disclosure: rebuilding a partition from a WAL whose tail holds a record larger than the
    /// ring can grow to (pool refuses growth) FAILS the partition bring-up and leaves the stream absent on
    /// this node. Before #1233 the record was silently skipped and every later record shifted down one
    /// offset. Failing is the intended outcome — a restart must be given enough pool budget.
    @Test
    void createStream_failsBringUp_whenWalRecoveryMeetsRecordLargerThanFrozenRing() {
        var config = durableTopicConfig();
        var unconstrained = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));
        create(unconstrained, config);

        publishExpecting(unconstrained, config, 0, 0);
        unconstrained.publishLocal(config.name(), PARTITION, new byte[OVERSIZED], 9_999L)
                     .onFailure(cause -> fail("an unconstrained ring stores the large event: " + cause.message()));
        publishExpecting(unconstrained, config, 2, 2);
        unconstrained.close();

        assertThat(walRecords(config)).hasSize(3);

        var frozen = streamPartitionManager(floorBudget(config), Option.some(walDir));

        frozen.createStream(config)
              .onSuccess(_ -> fail("recovering a record larger than the frozen ring must fail bring-up, not"
                                   + " skip the record and shift later offsets"))
              .onFailure(cause -> assertThat(cause.message()).contains(StreamError.General.EVENT_DROPPED.message()));
        frozen.readLocal(config.name(), PARTITION, 0, 100)
              .onSuccess(events -> fail("no partial ring may be served, read " + events.size() + " events"));

        frozen.close();
    }

    /// Best-effort (min-sync < 2, no WAL): the drop is absorbed — acked at the UNCHANGED head, counted,
    /// logged — and nothing is stored, so the next event still takes the next contiguous offset.
    @Test
    void publishLocal_absorbsDropAtUnchangedHead_andCountsIt_onBestEffortStream() {
        var config = plainConfig();
        var manager = streamPartitionManager(floorBudget(config), Option.none());
        create(manager, config);

        publishExpecting(manager, config, 0, 0);

        manager.publishLocal(config.name(), PARTITION, new byte[OVERSIZED], 9_999L)
               .onFailure(cause -> fail("best-effort drop must be absorbed, but failed: " + cause.message()))
               .onSuccess(offset -> assertThat(offset).as("acked at the unchanged head").isZero());
        assertThat(manager.droppedEventsSinceBoot()).isEqualTo(1L);

        publishExpecting(manager, config, 1, 1);

        var events = manager.readLocal(config.name(), PARTITION, 0, 100)
                            .onFailure(cause -> fail(cause.message()))
                            .unwrap();

        assertThat(events).as("the dropped event was never stored").hasSize(2);
        IntStream.range(0, 2).forEach(i -> assertEvent(events.get(i), i));

        manager.close();
    }

    // === helpers ===

    private static StreamConfig durableTopicConfig() {
        var spec = DurableTopicSpec.durableTopicSpec(1, 2, 2, DurableTopicSpec.DEFAULT_RETENTION).unwrap();

        return DurableTopicSubstrate.topicStreamConfig("orders-1233", spec);
    }

    private static StreamConfig namedConfig(String name) {
        return StreamConfig.streamConfig(name,
                                         1,
                                         RetentionPolicy.retentionPolicy(),
                                         "earliest",
                                         StreamConfig.DEFAULT.maxEventSizeBytes(),
                                         StreamConfig.DEFAULT.consistencyMode(),
                                         1,
                                         1,
                                         StreamCompression.NONE,
                                         Option.none());
    }

    private static void assertDropFailsWithoutWal(StreamConfig config) {
        var manager = streamPartitionManager(floorBudget(config), Option.none());
        create(manager, config);

        publishExpecting(manager, config, 0, 0);

        manager.publishLocal(config.name(), PARTITION, new byte[OVERSIZED], 9_999L)
               .onSuccess(offset -> fail("'" + config.name() + "' must fail a dropped publish, but it was acked at "
                                         + offset))
               .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.EVENT_DROPPED));

        publishExpecting(manager, config, 1, 1);
        manager.close();
    }

    private static StreamConfig plainConfig() {
        return StreamConfig.streamConfig("feed-1233", 1, RetentionPolicy.retentionPolicy(), "earliest");
    }

    /// A pool sized to exactly one partition floor: creation succeeds, every growth request is refused,
    /// so a DROP_OLDEST ring freezes at its 256 KiB floor segment.
    private static long floorBudget(StreamConfig config) {
        return OffHeapRingBuffer.floorBytes(config.retention().maxCount(), config.retention().maxBytes());
    }

    private static void create(StreamPartitionManager manager, StreamConfig config) {
        manager.createStream(config).onFailure(cause -> fail(cause.message()));
    }

    private static void publishExpecting(StreamPartitionManager manager, StreamConfig config, int i, long offset) {
        manager.publishLocal(config.name(), PARTITION, payload(i), 1000L + i)
               .onFailure(cause -> fail(cause.message()))
               .onSuccess(actual -> assertThat(actual).as("offset of evt-" + i).isEqualTo(offset));
    }

    private void assertRebuiltRingHoldsExactly(StreamConfig config, int count) {
        var rebuilt = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));
        create(rebuilt, config);

        var events = rebuilt.readLocal(config.name(), PARTITION, 0, 100)
                            .onFailure(cause -> fail(cause.message()))
                            .unwrap();

        assertThat(events).as("ring rebuilt from the WAL holds only acked events").hasSize(count);
        IntStream.range(0, count).forEach(i -> assertEvent(events.get(i), i));

        rebuilt.close();
    }

    private Path walFile(StreamConfig config) {
        return walDir.resolve(config.name()).resolve(PARTITION + ".wal");
    }

    private long walLastOffset(StreamConfig config) {
        var wal = PartitionWal.open(walFile(config)).unwrap();
        var last = wal.lastOffset();

        wal.close();
        return last;
    }

    private List<WalRecord> walRecords(StreamConfig config) {
        var wal = PartitionWal.open(walFile(config)).unwrap();
        var records = new ArrayList<WalRecord>();

        wal.replay(-1L, records::add).onFailure(cause -> fail(cause.message()));
        wal.close();
        return records;
    }

    private static void assertEvent(RawEvent event, int i) {
        assertThat(event.offset()).isEqualTo((long) i);
        assertThat(new String(event.data(), UTF_8)).isEqualTo("evt-" + i);
    }

    private static byte[] payload(int i) {
        return ("evt-" + i).getBytes(UTF_8);
    }
}
