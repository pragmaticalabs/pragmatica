// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.wal.PartitionWal;
import org.pragmatica.aether.stream.wal.PartitionWal.WalRecord;
import org.pragmatica.lang.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// Proves streaming-persistence W3 (append-before-ack): an owner `publishLocal` does not resolve as
/// success until the event is fsync-durable in the partition WAL, AND the WAL is fully optional — with
/// no `walBaseDir` configured no WAL file is created and behavior is unchanged.
class StreamPartitionManagerWalTest {

    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final int EVENTS = 6;

    @TempDir
    Path walDir;

    @Test
    void publishLocal_appendsEveryAckedEventToPartitionWal_fsyncDurable() {
        var manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));
        createStream(manager);

        IntStream.range(0, EVENTS).forEach(i -> publishOne(manager, i));

        // Open a SECOND WAL on the same file while the manager's WAL is still open: only an fsync (not
        // mere buffering) makes the records visible to this independent reader — proving the publish
        // appended durable records BEFORE it acked.
        var verifier = PartitionWal.open(walFile()).unwrap();
        var records = replayAll(verifier);

        assertThat(records).hasSize(EVENTS);
        IntStream.range(0, EVENTS).forEach(i -> assertRecord(records.get(i), i));

        verifier.close();
        manager.close();
    }

    /// #634 item 1: REPLICATED records are crash-durable too, not RAM-until-seal. `appendRecovered`
    /// enters the same WAL the owner's publish uses, and [StreamPartitionManager#syncReplicated] is the
    /// fsync barrier an acking replica awaits. Verified the same way W3 is: an independent reader on the
    /// same file sees the records only if they were fsynced.
    @Test
    void appendRecovered_afterSyncReplicated_isFsyncDurable_inTheSameWal() {
        var manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));
        createStream(manager);

        IntStream.range(0, EVENTS)
                 .forEach(i -> manager.appendRecovered(STREAM, PARTITION, payload(i), 1000L + i)
                                      .onFailure(cause -> fail(cause.message())));
        manager.syncReplicated(STREAM, PARTITION)
               .await()
               .onFailure(cause -> fail(cause.message()));

        var verifier = PartitionWal.open(walFile()).unwrap();
        var records = replayAll(verifier);

        assertThat(records).as("replica-side records must reach the WAL — RAM-until-seal loses acked"
                              + " entity writes to correlated power loss at ANY replication factor")
                           .hasSize(EVENTS);
        IntStream.range(0, EVENTS).forEach(i -> assertRecord(records.get(i), i));

        verifier.close();
        manager.close();
    }

    @Test
    void syncReplicated_withoutWal_resolvesImmediately_preservingWallessSemantics() {
        var manager = streamPartitionManager(Long.MAX_VALUE, Option.none());
        createStream(manager);

        manager.appendRecovered(STREAM, PARTITION, payload(0), 1000L).onFailure(cause -> fail(cause.message()));

        assertThat(manager.syncReplicated(STREAM, PARTITION).await().isSuccess())
            .as("wall-less deployments keep their exact pre-barrier ack semantics")
            .isTrue();

        manager.close();
    }

    @Test
    void publishLocal_writesNoWalFiles_whenNoWalBaseDir() {
        var manager = streamPartitionManager(Long.MAX_VALUE, Option.none());
        createStream(manager);

        publishOne(manager, 0);

        assertThat(Files.exists(walDir.resolve(STREAM))).isFalse();

        manager.close();
    }

    // === helpers ===

    private Path walFile() {
        return walDir.resolve(STREAM).resolve(PARTITION + ".wal");
    }

    private static void createStream(StreamPartitionManager manager) {
        manager.createStream(StreamConfig.streamConfig(STREAM))
               .onFailure(cause -> fail(cause.message()));
    }

    private static void publishOne(StreamPartitionManager manager, int i) {
        manager.publishLocal(STREAM, PARTITION, payload(i), 1000L + i)
               .onFailure(cause -> fail(cause.message()))
               .onSuccess(offset -> assertThat(offset).isEqualTo((long) i));
    }

    private static List<WalRecord> replayAll(PartitionWal wal) {
        var records = new ArrayList<WalRecord>();

        wal.replay(-1L, records::add).onFailure(cause -> fail(cause.message()));
        return records;
    }

    private static void assertRecord(WalRecord record, int i) {
        assertThat(record.offset()).isEqualTo((long) i);
        assertThat(record.timestampMillis()).isEqualTo(1000L + i);
        assertThat(new String(record.payload(), UTF_8)).isEqualTo("evt-" + i);
    }

    private static byte[] payload(int i) {
        return ("evt-" + i).getBytes(UTF_8);
    }
}
