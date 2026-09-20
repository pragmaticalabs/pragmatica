// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #1241 — the append path applies its record to the fold INSIDE the promise chain, so the fold already
/// reflects a write by the time anything downstream of the append — the key's serialization tail, and
/// therefore the key's next operation — can run.
///
/// ## Why the substrate resolves appends from the TEST thread, with every carrier pinned
/// The defect is only reachable on a PENDING append promise: attaching `onSuccess` to an already-resolved
/// promise runs the handler inline, so a substrate answering `Promise.success(offset)` makes the old code
/// look correct. On a pending promise `onSuccess` is an event handler, which `Promise` submits to its
/// virtual-thread executor, while dependent `map`/`mapError`/`withSuccess` steps run inline in the
/// resolving thread. Pinning every virtual-thread carrier while the test thread (a platform thread)
/// resolves the append removes the race from the measurement: an apply dispatched asynchronously CANNOT
/// run before the read below, and an apply performed inline has already run. The read therefore
/// discriminates the two shapes deterministically instead of by timing luck.
class PartitionFencedDurableEntityApplyTest {
    private static final String KEYSPACE = "orders";
    private static final int PARTITIONS = 8;
    private static final String KEY = "k";

    private DeferrableSubstrate substrate;
    private EntityPartitionArc arc;
    private PartitionFencedDurableEntity<String, Integer, IntOp> entity;

    @BeforeEach
    void setUp() {
        substrate = new DeferrableSubstrate();
        arc = EntityPartitionArc.entityPartitionArc(KEYSPACE, PARTITIONS);
        entity = (PartitionFencedDurableEntity<String, Integer, IntOp>) PartitionFencedDurableEntity.<String, Integer, IntOp> partitionFencedDurableEntity(KEYSPACE,
                                                                                                                                                           substrate,
                                                                                                                                                           arc,
                                                                                                                                                           new IntSerializer(),
                                                                                                                                                           new IntDeserializer());
    }

    @Test
    @Timeout(60)
    void update_foldReflectsTheWrite_beforeTheAppendChainContinues() throws InterruptedException {
        entity.create(KEY, 1).await().onFailure(cause -> fail(cause.message()));
        substrate.deferAppends();

        var update = entity.update(KEY, new IntOp.Add(1));
        var pending = substrate.awaitPendingAppend();
        var observed = withCarriersPinned(() -> resolveThenRead(pending));

        update.await().onFailure(cause -> fail(cause.message()));

        assertThat(observed).as("the fold must hold the committed write the moment the append resolves — an"
                                + " apply left to an asynchronous handler lets the key's next operation read"
                                + " the state from before it")
                            .isEqualTo(Option.some(2));
    }

    /// The rationale for applying even a write that missed its durability target must survive the move
    /// into the chain: the record IS in the log, so the fold takes it while the caller still learns of the
    /// miss. Regression fence — this also passes against the pre-#1241 code, where the same apply ran in
    /// `mapError`; it pins that moving the success-path apply did not drop the failure-path one.
    @Test
    void update_appliesToFold_evenWhenReplicationBarrierUnmet() {
        entity.create(KEY, 1).await().onFailure(cause -> fail(cause.message()));
        substrate.missBarrier();

        entity.update(KEY, new IntOp.Add(1))
              .await()
              .onSuccess(_ -> fail("a write that missed its replication barrier must report the miss"))
              .onFailure(cause -> assertThat(cause.stream()).hasAtLeastOneElementOfType(EntityLogError.ReplicationBarrierUnmet.class));

        assertThat(foldState()).isEqualTo(Option.some(2));
    }

    private Option<Integer> resolveThenRead(PendingAppend pending) {
        substrate.commit(pending);

        return foldState();
    }

    private Option<Integer> foldState() {
        return entity.fold()
                     .get(arc.partitionOf(KEY), KEY)
                     .map(bytes -> Integer.valueOf(new String(bytes, StandardCharsets.UTF_8)));
    }

    private static <T> T withCarriersPinned(Supplier<T> action) throws InterruptedException {
        var carriers = Integer.getInteger("jdk.virtualThreadScheduler.parallelism",
                                          Runtime.getRuntime().availableProcessors());
        var started = new CountDownLatch(carriers);
        var release = new AtomicBoolean();
        var spinners = IntStream.range(0, carriers)
                                .mapToObj(_ -> Thread.ofVirtual().start(() -> spin(started, release)))
                                .toList();

        try {
            assertThat(started.await(10, TimeUnit.SECONDS)).as("every virtual-thread carrier must be pinned, or"
                                                               + " the test cannot exclude the asynchronous apply")
                                                           .isTrue();

            return action.get();
        } finally {
            release.set(true);

            for (var spinner : spinners) {
                spinner.join();
            }
        }
    }

    private static void spin(CountDownLatch started, AtomicBoolean release) {
        started.countDown();

        while (!release.get()) {
            Thread.onSpinWait();
        }
    }

    private record PendingAppend(int partition, byte[] record, Promise<Long> promise) {}

    /// A log whose appends resolve immediately until [#deferAppends], after which each append waits for
    /// the test to [#commit] it — from whichever thread the test chooses. `headOffset` counts only
    /// COMMITTED records, so a deferred append is invisible to catch-up exactly as an in-flight one is.
    private static final class DeferrableSubstrate implements EntityLogSubstrate {
        private final Map<Integer, List<byte[]>> log = new ConcurrentHashMap<>();
        private final LinkedBlockingQueue<PendingAppend> pending = new LinkedBlockingQueue<>();
        private volatile boolean deferred;
        private volatile boolean barrierMissed;

        void deferAppends() {
            deferred = true;
        }

        void missBarrier() {
            barrierMissed = true;
        }

        PendingAppend awaitPendingAppend() throws InterruptedException {
            var next = pending.poll(10, TimeUnit.SECONDS);

            assertThat(next).as("the update must reach the log").isNotNull();

            return next;
        }

        void commit(PendingAppend append) {
            append.promise().succeed(appendToLog(append.partition(), append.record()));
        }

        private synchronized long appendToLog(int partition, byte[] record) {
            var records = log.computeIfAbsent(partition, _ -> new ArrayList<>());

            records.add(record);

            return records.size() - 1L;
        }

        @Override
        public Result<Unit> ensureLog(String keyspace, int partitionCount, int replicationFactor, int minSyncReplicas) {
            return Result.unitResult();
        }

        @Override
        public Promise<Long> append(String keyspace, int partition, byte[] record) {
            if (barrierMissed) {
                return new EntityLogError.ReplicationBarrierUnmet(keyspace,
                                                                  partition,
                                                                  appendToLog(partition, record),
                                                                  2,
                                                                  Causes.cause("replica lagging")).promise();
            }

            if (!deferred) {
                return Promise.success(appendToLog(partition, record));
            }

            var promise = Promise.<Long> promise();

            pending.add(new PendingAppend(partition, record, promise));

            return promise;
        }

        @Override
        public synchronized Promise<List<byte[]>> read(String keyspace, int partition, long fromOffset, int maxRecords) {
            var records = log.getOrDefault(partition, List.of());
            var start = (int) fromOffset;

            return Promise.success(start >= records.size()
                                   ? List.of()
                                   : List.copyOf(records.subList(start, Math.min(records.size(), start + maxRecords))));
        }

        @Override
        public synchronized long headOffset(String keyspace, int partition) {
            return log.getOrDefault(partition, List.of()).size() - 1L;
        }

        @Override
        public synchronized long earliestRetainedOffset(String keyspace, int partition) {
            return log.getOrDefault(partition, List.of()).isEmpty() ? -1L : 0L;
        }

        @Override
        public boolean holdsPartition(String keyspace, int partition) {
            return true;
        }

        @Override
        public boolean localLogComplete(String keyspace, int partition) {
            return true;
        }

        @Override
        public Promise<Unit> saveCheckpoint(String keyspace, int partition, long throughOffset, byte[] snapshot) {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Option<EntityCheckpoint>> loadCheckpoint(String keyspace, int partition) {
            return Promise.success(Option.none());
        }
    }

    private static final class IntSerializer implements Serializer {
        @Override
        public byte[] encode(Object value) {
            return String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public <T> void write(ByteBuf byteBuf, T object) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }

    private static final class IntDeserializer implements Deserializer {
        @Override
        public <T> T decode(byte[] bytes) {
            @SuppressWarnings("unchecked")
            var value = (T) Integer.valueOf(new String(bytes, StandardCharsets.UTF_8));

            return value;
        }

        @Override
        public <T> T read(ByteBuf byteBuf) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }
}
