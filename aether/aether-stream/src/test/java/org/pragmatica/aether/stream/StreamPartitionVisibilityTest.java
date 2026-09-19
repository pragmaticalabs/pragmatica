// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.wal.PartitionWal;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateAck.replicateAck;

/// #1235: consumers see a partition only up to its VISIBLE position — durable on the owner (WAL fsync)
/// AND acknowledged by `minSyncReplicas - 1` distinct peers. A push listener fires when that position
/// advances, never on the bare append. Before the fix the ring notified listeners inside `append` and
/// every read served the raw head, so a consumer could act on an event that owner failover then erased.
class StreamPartitionVisibilityTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId SELF = NodeId.randomNodeId();
    private static final NodeId PEER = NodeId.randomNodeId();

    @TempDir
    Path walDir;

    private StreamPartitionManager manager;

    @AfterEach
    void closeManager() {
        Option.option(manager).onPresent(StreamPartitionManager::close);
    }

    /// minSync = 2 with one peer: the publish is durable on the owner at once (no WAL), but it is not
    /// visible until the peer acknowledges it.
    @Nested
    class MinSyncTwo {

        /// The ticket's acceptance test.
        @Test
        void publish_staysInvisibleAndSilent_whilePeerAckIsPending_thenBecomesVisibleOnce() {
            var replication = replicationWithPeer();
            manager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replication);
            createStream(manager, 2, 2);
            var notifications = listen(manager);

            var offset = publish(manager, "e0");
            var pending = manager.awaitReplication(STREAM, PARTITION, offset, 1);

            assertThat(pending.isResolved()).as("the publish is waiting for the peer").isFalse();
            assertThat(notifications).as("no push notification for an unacknowledged event").hasValue(0);
            assertThat(readAll(manager)).as("read(0) must not expose the unacknowledged event").isEmpty();

            replication.handleAck(replicateAck(PEER, STREAM, PARTITION, offset));

            assertThat(pending.await().isSuccess()).as("the peer ack resolves the publish").isTrue();
            assertThat(readAll(manager)).as("acknowledged ⇒ visible").containsExactly("e0");
            assertThat(notifications).as("the listener fires once, when the event becomes visible").hasValue(1);
        }

        /// Read-your-writes for an acknowledged publish: whatever continues the publish promise already
        /// sees the event. The ack must advance visibility BEFORE it resolves the pending await.
        @Test
        void ackedPublish_isVisibleToTheContinuationOfItsOwnAwait() {
            var replication = replicationWithPeer();
            manager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replication);
            createStream(manager, 2, 2);

            var offset = publish(manager, "e0");
            var seenByContinuation = manager.awaitReplication(STREAM, PARTITION, offset, 1)
                                            .map(_ -> readAll(manager));

            replication.handleAck(replicateAck(PEER, STREAM, PARTITION, offset));

            assertThat(seenByContinuation.await().or(List.of())).containsExactly("e0");
        }

        /// A peer that acknowledges only the first of two events makes exactly that prefix visible.
        @Test
        void ack_exposesOnlyTheAcknowledgedPrefix() {
            var replication = replicationWithPeer();
            manager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replication);
            createStream(manager, 2, 2);

            var first = publish(manager, "e0");
            publish(manager, "e1");
            replication.handleAck(replicateAck(PEER, STREAM, PARTITION, first));

            assertThat(readAll(manager)).containsExactly("e0");
        }
    }

    /// An owner WAL failure AFTER the ring append (ruling 801a8b54e routes it here): the append is in the
    /// ring, the publish failed, and the event must never be offered to a consumer.
    @Nested
    class OwnerWalFailure {

        @Test
        void failedFrameWrite_eventNeverBecomesVisible() {
            manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));
            createStream(manager, 1, 0);
            var notifications = listen(manager);
            var channel = FailingChannel.inject(walOf(manager));

            channel.failWrites = true;
            publishExpectingFailure(manager, "lost");
            channel.failWrites = false;
            publishExpectingFailure(manager, "after-fail-stop");

            assertThat(readAll(manager)).as("an event whose WAL frame never landed is never readable").isEmpty();
            assertThat(notifications).as("and never announced").hasValue(0);
        }

        @Test
        void failedFsync_eventNeverBecomesVisible() {
            manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));
            createStream(manager, 1, 0);
            var notifications = listen(manager);
            var channel = FailingChannel.inject(walOf(manager));

            channel.failForce = true;
            publishExpectingFailure(manager, "lost");

            assertThat(readAll(manager)).as("an event whose fsync failed is never readable").isEmpty();
            assertThat(notifications).as("and never announced").hasValue(0);
        }

        /// The #1258 case: the event is replicated inside the ordered section, then the OWNER's fsync
        /// fails. A peer ack arriving afterwards must not make it visible — the owner never durably held it.
        @Test
        void failedFsyncAfterReplication_peerAckDoesNotExposeIt() {
            var replication = replicationWithPeer();
            manager = replicatingWalManager(replication, walDir);
            createStream(manager, 2, 2);
            var notifications = listen(manager);
            var channel = FailingChannel.inject(walOf(manager));

            channel.failForce = true;
            publishExpectingFailure(manager, "lost");
            replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 0L));

            assertThat(readAll(manager)).isEmpty();
            assertThat(notifications).hasValue(0);
        }
    }

    /// Owner-only durability (minSync <= 1) with no WAL: visible aliases appended. This is the control
    /// that shows a visible event IS read and announced by the same instruments.
    @Nested
    class OwnerOnly {

        @Test
        void publish_isVisibleAndAnnouncedOnce_whenPublishReturns() {
            manager = streamPartitionManager(Long.MAX_VALUE);
            createStream(manager, 1, 1);
            var notifications = listen(manager);

            publish(manager, "e0");

            assertThat(readAll(manager)).containsExactly("e0");
            assertThat(notifications).hasValue(1);
        }

        @Test
        void walPublish_isVisibleWhenPublishReturns() {
            manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));
            createStream(manager, 1, 0);

            publish(manager, "e0");

            assertThat(readAll(manager)).containsExactly("e0");
        }
    }

    // === helpers ===

    private static ReplicationManager replicationWithPeer() {
        ReplicaRegistry registry = replicaRegistry();

        registry.registerReplica(STREAM, PARTITION, SELF);
        registry.registerReplica(STREAM, PARTITION, PEER);
        return replicationManager(SELF, registry);
    }

    /// Replication AND a WAL, without a cluster node or an epoch fence. No public factory combines the two
    /// (production wires both through the fenced factory), so the private constructor is used directly.
    private static StreamPartitionManager replicatingWalManager(ReplicationManager replication, Path walDir) {
        try {
            var constructor = StreamPartitionManager.class.getDeclaredConstructor(long.class,
                                                                                  EvictionListener.class,
                                                                                  ReplicationManager.class,
                                                                                  Option.class,
                                                                                  Option.class,
                                                                                  StreamOwnerEpochSource.class,
                                                                                  Option.class,
                                                                                  LastSealedOffsetSource.class);

            constructor.setAccessible(true);
            return constructor.newInstance(Long.MAX_VALUE,
                                           EvictionListener.NOOP,
                                           replication,
                                           Option.none(),
                                           Option.none(),
                                           StreamOwnerEpochSource.zero(),
                                           Option.some(walDir),
                                           LastSealedOffsetSource.none());
        } catch (ReflectiveOperationException e) {
            return fail("manager construction failed: " + e);
        }
    }

    private static void createStream(StreamPartitionManager manager, int replicas, int minSyncReplicas) {
        var config = StreamConfig.streamConfig(STREAM,
                                               1,
                                               RetentionPolicy.retentionPolicy(),
                                               "earliest",
                                               1_048_576L,
                                               ConsistencyMode.EVENTUAL,
                                               replicas,
                                               minSyncReplicas,
                                               StreamCompression.NONE,
                                               Option.none());

        manager.createStream(config).onFailure(cause -> fail(cause.message()));
    }

    private static AtomicInteger listen(StreamPartitionManager manager) {
        var notifications = new AtomicInteger();

        manager.partitionBuffer(STREAM, PARTITION)
               .onEmpty(() -> fail("partition not materialized"))
               .onPresent(ring -> ring.addAppendListener(_ -> notifications.incrementAndGet()));
        return notifications;
    }

    private static long publish(StreamPartitionManager manager, String payload) {
        return manager.publishLocal(STREAM, PARTITION, payload.getBytes(UTF_8), 1L)
                      .onFailure(cause -> fail("publish failed: " + cause.message()))
                      .or(-1L);
    }

    private static void publishExpectingFailure(StreamPartitionManager manager, String payload) {
        manager.publishLocal(STREAM, PARTITION, payload.getBytes(UTF_8), 1L)
               .onSuccess(offset -> fail("the WAL was injected to fail, yet offset " + offset + " was acked"));
    }

    private static List<String> readAll(StreamPartitionManager manager) {
        return manager.readLocal(STREAM, PARTITION, 0L, 100)
                      .map(events -> events.stream()
                                           .map(event -> new String(event.data(), UTF_8))
                                           .toList())
                      .onFailure(cause -> fail("read failed: " + cause.message()))
                      .or(List.of());
    }

    /// The partition's WAL, reached through the manager's private stream map — the WAL's channel is the
    /// only I/O seam, and the manager exposes neither.
    @SuppressWarnings("unchecked")
    private static PartitionWal walOf(StreamPartitionManager manager) {
        try {
            var field = StreamPartitionManager.class.getDeclaredField("streams");

            field.setAccessible(true);
            var streams = (Map<String, StreamPartitionManager.StreamEntry>) field.get(manager);

            return streams.get(STREAM)
                          .materialized()
                          .get(PARTITION)
                          .wal()
                          .fold(() -> fail("no WAL configured"), wal -> wal);
        } catch (ReflectiveOperationException e) {
            return fail("stream map unreachable: " + e);
        }
    }

    /// Delegates to the real channel; `failWrites` makes positional writes throw (a failed frame write)
    /// and `failForce` makes `force` throw (a failed fsync).
    private static final class FailingChannel extends FileChannel {
        private final FileChannel delegate;
        private volatile boolean failWrites;
        private volatile boolean failForce;

        private FailingChannel(FileChannel delegate) {
            this.delegate = delegate;
        }

        static FailingChannel inject(PartitionWal wal) {
            try {
                var field = channelField();
                var wrapper = new FailingChannel((FileChannel) field.get(wal));

                field.set(wal, wrapper);
                return wrapper;
            } catch (ReflectiveOperationException e) {
                return fail("channel injection failed: " + e);
            }
        }

        private static Field channelField() throws NoSuchFieldException {
            var field = PartitionWal.class.getDeclaredField("channel");

            field.setAccessible(true);
            return field;
        }

        @Override
        public void force(boolean metaData) throws IOException {
            if (failForce) {
                throw new IOException("injected fsync failure");
            }
            delegate.force(metaData);
        }

        @Override
        public int write(ByteBuffer src, long position) throws IOException {
            if (failWrites) {
                throw new IOException("injected write failure");
            }
            return delegate.write(src, position);
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            return delegate.read(dst);
        }

        @Override
        public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
            return delegate.read(dsts, offset, length);
        }

        @Override
        public int read(ByteBuffer dst, long position) throws IOException {
            return delegate.read(dst, position);
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            return delegate.write(src);
        }

        @Override
        public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
            return delegate.write(srcs, offset, length);
        }

        @Override
        public long position() throws IOException {
            return delegate.position();
        }

        @Override
        public FileChannel position(long newPosition) throws IOException {
            return delegate.position(newPosition);
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        @Override
        public FileChannel truncate(long size) throws IOException {
            return delegate.truncate(size);
        }

        @Override
        public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
            return delegate.transferTo(position, count, target);
        }

        @Override
        public long transferFrom(ReadableByteChannel src, long position, long count) throws IOException {
            return delegate.transferFrom(src, position, count);
        }

        @Override
        public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
            return delegate.map(mode, position, size);
        }

        @Override
        public FileLock lock(long position, long size, boolean shared) throws IOException {
            return delegate.lock(position, size, shared);
        }

        @Override
        public FileLock tryLock(long position, long size, boolean shared) throws IOException {
            return delegate.tryLock(position, size, shared);
        }

        @Override
        protected void implCloseChannel() throws IOException {
            delegate.close();
        }
    }
}
