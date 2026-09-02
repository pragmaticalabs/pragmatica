// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.node;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.PartitionedStreamAccess;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.segment.CursorStore;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #478 — cursor auto-resume across a same-node restart, exercised over the SAME production storage
/// path AetherNode uses ({@link StorageFactory#defaultStreamStorage}: a `LocalDiskTier` for blocks
/// plus a snapshot-restored metadata store for refs, both under one per-node data dir).
///
/// This is the empirical proof the #474 trace found missing: `CursorStoreTest` covers one storage
/// instance with no restart, and `StreamCrashDurabilityTest` (forge) proves EVENT durability but
/// explicitly excludes cursor durability (its `test-events` slice keeps no server-side cursor). Here
/// a cursor is committed through the app `StreamAccess`, the node is "restarted" by rebuilding the
/// stream storage over the same data dir, and the re-attached consumer must resume at the committed
/// offset automatically — via {@link StreamAccess#fetchFromCommitted}, not an explicit app re-seek.
///
/// Scope: this test proves CURSOR durability + framework-driven auto-resume. Event log durability
/// (WAL replay) is `StreamCrashDurabilityTest`'s job, so the post-restart event log is reconstructed
/// by re-publishing the same batch into a fresh in-memory partition manager (identical offsets).
class CursorAutoResumeRestartTest {
    private static final String NODE_ID = "node-1";
    private static final String STREAM = "test-events";
    private static final String GROUP = "orders-consumer";
    private static final int PARTITION = 0;
    private static final int EVENT_COUNT = 5;
    private static final long RESUME_AT = 3L;
    private static final long ONE_GB = 1024L * 1024 * 1024;
    private static final long ONE_HOUR_MS = 3_600_000L;

    /// After restart the re-attached consumer resumes at the committed offset automatically — the
    /// first `fetchFromCommitted` returns the tail from RESUME_AT, not the whole log from 0.
    @Test
    void fetchFromCommitted_resumesAtCommittedOffset_afterRestart(@TempDir Path dataDir) {
        var restarted = commitCursorThenRestart(dataDir);

        restarted.committedOffset(GROUP, PARTITION)
                 .await()
                 .onFailure(cause -> fail("committedOffset failed after restart: " + cause.message()))
                 .onSuccess(committed -> {
                     assertThat(committed.isPresent())
                         .describedAs("committed cursor must survive same-node restart via the disk CursorStore")
                         .isTrue();
                     committed.onPresent(offset -> assertThat(offset).isEqualTo(RESUME_AT));
                 });

        restarted.fetchFromCommitted(GROUP, PARTITION, 100)
                 .await()
                 .onFailure(cause -> fail("fetchFromCommitted failed after restart: " + cause.message()))
                 .onSuccess(events -> {
                     assertThat(events)
                         .describedAs("auto-resume must deliver only the tail from the committed offset")
                         .hasSize(EVENT_COUNT - (int) RESUME_AT);
                     assertThat(events.getFirst().offset())
                         .describedAs("first delivered event after auto-resume is the committed offset, not 0")
                         .isEqualTo(RESUME_AT);
                 });
    }

    /// Guard: auto-resume must not leak into the plain `fetch` — an explicit offset still wins, so a
    /// caller asking for offset 0 after restart re-reads the whole log (Kafka-style client-tracked read).
    @Test
    void fetch_honorsExplicitOffset_afterRestart(@TempDir Path dataDir) {
        var restarted = commitCursorThenRestart(dataDir);

        restarted.fetch(PARTITION, 0L, 100)
                 .await()
                 .onFailure(cause -> fail("explicit fetch failed after restart: " + cause.message()))
                 .onSuccess(events -> {
                     assertThat(events).hasSize(EVENT_COUNT);
                     assertThat(events.getFirst().offset()).isZero();
                 });
    }

    /// The no-cursor branch of `fetchFromCommitted`: a group that never committed (even after another
    /// group's cursor was persisted + restart) has no committed offset, so auto-resume reads from 0.
    @Test
    void fetchFromCommitted_readsFromZero_forUncommittedGroup_afterRestart(@TempDir Path dataDir) {
        var restarted = commitCursorThenRestart(dataDir);

        restarted.fetchFromCommitted("never-committed-group", PARTITION, 100)
                 .await()
                 .onFailure(cause -> fail("fetchFromCommitted failed for uncommitted group: " + cause.message()))
                 .onSuccess(events -> {
                     assertThat(events)
                         .describedAs("no committed cursor → auto-resume reads the whole log from 0")
                         .hasSize(EVENT_COUNT);
                     assertThat(events.getFirst().offset()).isZero();
                 });
    }

    /// Commit RESUME_AT through the app `StreamAccess`, persist the cursor ref to disk, then rebuild the
    /// stream storage + partition log over the same data dir (the same-node restart) and return the
    /// re-attached access.
    private PartitionedStreamAccess<byte[]> commitCursorThenRestart(Path dataDir) {
        var setup1 = StorageFactory.defaultStreamStorage(Option.none(), dataDir, NODE_ID);
        var manager1 = partitionManagerWithBatch();
        var access1 = durableAccess(manager1, CursorStore.cursorStore(setup1.instance()));

        access1.commit(GROUP, PARTITION, RESUME_AT)
               .await()
               .onFailure(cause -> fail("commit failed: " + cause.message()));
        // The offset block is write-through to the disk tier; the ref is in the snapshot-backed
        // metadata store, so force a snapshot to make it durable before the restart.
        setup1.snapshotManager().forceSnapshot();
        manager1.close();

        var setup2 = StorageFactory.defaultStreamStorage(Option.none(), dataDir, NODE_ID);
        var manager2 = partitionManagerWithBatch();

        return durableAccess(manager2, CursorStore.cursorStore(setup2.instance()));
    }

    private static StreamPartitionManager partitionManagerWithBatch() {
        var manager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);

        manager.createStream(StreamConfig.streamConfig(STREAM,
                                                       1,
                                                       RetentionPolicy.retentionPolicy(100_000, ONE_GB, ONE_HOUR_MS),
                                                       "earliest"));

        for (int i = 0; i < EVENT_COUNT; i++) {
            manager.publishLocal(STREAM, PARTITION, ("event-" + i).getBytes(), 1000L + i);
        }

        return manager;
    }

    private static PartitionedStreamAccess<byte[]> durableAccess(StreamPartitionManager manager, CursorStore cursorStore) {
        return PartitionedStreamAccess.streamAccess(manager,
                                                    identitySerializer(),
                                                    identityDeserializer(),
                                                    STREAM,
                                                    1,
                                                    Option.none(),
                                                    Option.none(),
                                                    Option.some(cursorStore));
    }

    private static Serializer identitySerializer() {
        return new Serializer() {
            @SuppressWarnings("unchecked")
            @Override
            public <T> byte[] encode(T object) {
                return (byte[]) object;
            }

            @Override
            public <T> void write(ByteBuf byteBuf, T object) {
                byteBuf.writeBytes((byte[]) object);
            }
        };
    }

    private static Deserializer identityDeserializer() {
        return new Deserializer() {
            @SuppressWarnings("unchecked")
            @Override
            public <T> T decode(byte[] bytes) {
                return (T) bytes;
            }

            @SuppressWarnings("unchecked")
            @Override
            public <T> T read(ByteBuf byteBuf) {
                var bytes = new byte[byteBuf.readableBytes()];

                byteBuf.readBytes(bytes);

                return (T) bytes;
            }
        };
    }
}
