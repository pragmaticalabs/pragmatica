// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.state.kvstore.RingFixtures.Key;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.cluster.state.kvstore.RingFixtures.commit;
import static org.pragmatica.cluster.state.kvstore.RingFixtures.genesisStore;
import static org.pragmatica.cluster.state.kvstore.RingFixtures.put;

/// NT-O43, I-W13: the lock order is `mutationLock` < store monitor < ring lock, and nothing reachable with the ring lock held calls the store.
///
/// The positive control runs FIRST in intent and must be red in effect: [InvertedRing] takes its own lock and then reads the store, the
/// edge the design forbids, while a writer holds the store monitor and wants the ring lock. The detector (`ThreadMXBean.findDeadlockedThreads`)
/// must report that cycle; a detector that cannot see it proves nothing about the real ring. The real ring is then run through the same
/// three-thread workload (a writer looping `processCommitted`/`applyEffects`, a flusher looping heartbeat, `subscribeFrom` and sink overflow,
/// and a reader looping `get`/`getTyped`) and must show no cycle.
class CommitRingLockOrderTest {
    private static final long TIMEOUT_MILLIS = 5_000;

    /// A ring variant that violates the order: it holds its own lock while reading the store (the tag through `getTyped`, as a heartbeat would).
    private static final class InvertedRing {
        private final ReentrantLock ringLock = new ReentrantLock();

        void heartbeatReadingTheStore(KVStore<StructuredKey, Object> store, CountDownLatch writerHoldsStoreMonitor) throws InterruptedException {
            ringLock.lock();
            try {
                writerHoldsStoreMonitor.await();
                store.getTyped(RingFixtures.IncKey.INSTANCE, Object.class);
            } finally {
                ringLock.unlock();
            }
        }

        /// What the writer does inside `mutate`, in the right order: store monitor first, then the ring lock.
        void appendHoldingTheStoreMonitor(KVStore<StructuredKey, Object> store, CountDownLatch writerHoldsStoreMonitor) throws InterruptedException {
            synchronized (store) {
                writerHoldsStoreMonitor.countDown();
                Thread.sleep(100);
                ringLock.lock();
                ringLock.unlock();
            }
        }
    }

    private static final List<String> CONTROL_THREADS = List.of("inverted-heartbeat", "writer-in-mutate");

    private static long[] deadlocked() {
        var ids = ManagementFactory.getThreadMXBean().findDeadlockedThreads();

        return ids == null ? new long[0] : ids;
    }

    /// The deadlocked threads that are NOT the positive control's own (its two daemon threads stay deadlocked for the life of the JVM).
    private static List<String> deadlockedOutsideTheControl() {
        var ids = deadlocked();

        return ids.length == 0
               ? List.of()
               : java.util.Arrays.stream(ManagementFactory.getThreadMXBean().getThreadInfo(ids))
                                 .map(java.lang.management.ThreadInfo::getThreadName)
                                 .filter(name -> !CONTROL_THREADS.contains(name))
                                 .toList();
    }

    @Test
    void positiveControl_aRingThatReadsTheStoreUnderItsLock_deadlocksAndTheDetectorSeesTheCycle() throws InterruptedException {
        var store = genesisStore();
        var inverted = new InvertedRing();
        var latch = new CountDownLatch(1);
        var reader = new Thread(() -> runQuietly(() -> inverted.heartbeatReadingTheStore(store, latch)), "inverted-heartbeat");
        var writer = new Thread(() -> runQuietly(() -> inverted.appendHoldingTheStoreMonitor(store, latch)), "writer-in-mutate");

        reader.setDaemon(true);
        writer.setDaemon(true);
        reader.start();
        writer.start();
        var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS);

        while (deadlocked().length == 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }

        var cycle = deadlocked();

        assertThat(cycle).as("the control must go red: the inverted order is a real ring -> store edge and the detector must report the cycle").hasSize(2);
        System.out.println("NT-O43 positive control: deadlock cycle reported over threads " + java.util.Arrays.toString(cycle));
    }

    /// The real ring under the same three-thread workload: no cycle, and nothing finishes late.
    @Test
    void realRing_underAWriterAFlusherAndAReader_hasNoDeadlockCycle() throws InterruptedException {
        var store = genesisStore();
        var stop = new AtomicBoolean();
        var overflowCloses = new AtomicInteger();
        var closeRanUnderRingLock = new AtomicBoolean();
        var ring = store.replicaRing();
        var writer = new Thread(() -> {
            for (var slot = 1L; !stop.get(); slot += 2) {
                commit(store, slot, put("k" + (slot % 50), "v" + slot));
                store.applyEffects(new ReplicaEntry(IncarnationTag.incarnationTag(1, "A"), slot + 1, slot + 1, "raw-" + slot, List.of()));
            }
        }, "writer");
        var flusher = new Thread(() -> {
            while (!stop.get()) {
                ring.flush();
                var anchor = ring.anchor();
                var small = CommitRing.subscription(200, _ -> {
                    overflowCloses.incrementAndGet();
                    closeRanUnderRingLock.compareAndSet(false, ring.isLockHeldByCurrentThread());
                    store.get(new Key("k1"));
                });

                ring.subscribeFrom(IncarnationTag.incarnationTag(1, "A"), ring.nextSlot(), anchor, small);
            }
        }, "flusher");
        var reader = new Thread(() -> {
            while (!stop.get()) {
                store.get(new Key("k1"));
                store.getTyped(RingFixtures.IncKey.INSTANCE, Object.class);
                store.captureReplicaSnapshot(ReplicaKeyFilter.includeAll());
            }
        }, "reader");
        var threads = List.of(writer, flusher, reader);

        threads.forEach(Thread::start);
        Thread.sleep(1_500);
        var cycle = deadlockedOutsideTheControl();

        stop.set(true);
        for (var thread : threads) {
            thread.join(TIMEOUT_MILLIS);
        }

        assertThat(cycle).as("I-W13 (mutation: read the tag via kvStore.getTyped under the ring lock, see the control): no thread is in a deadlock cycle").isEmpty();
        assertThat(threads).as("every thread finished").noneMatch(Thread::isAlive);
        assertThat(closeRanUnderRingLock.get()).as("the transport close, which reads the store, never ran under the ring lock").isFalse();
    }

    // ---- the ranked-lock wrapper (I-W13, test builds) ----

    /// A real store over a real ring whose lock is a [RankedLock]; the store's entry points declare the monitors they take, in order.
    private static final class RankedStore extends KVStore<StructuredKey, Object> {
        RankedStore() {
            super(org.pragmatica.messaging.MessageRouter.mutable(),
                  RingFixtures.TEXT,
                  RingFixtures.deserializerReturning(java.util.Map.of()),
                  ReplicaKeyFilter.includeAll(),
                  RingFixtures.SOURCE,
                  CommitRing.commitRing(CommitRing.DEFAULT_BUDGET_BYTES, ValueSizeTable.valueSizeTable()::estimate, Runnable::run, new RankedLock(RankedLock.RING)));
        }

        @Override
        public <R> List<R> processCommitted(org.pragmatica.consensus.StateMachine.Batch<KVCommand<StructuredKey>> batch, long nextSlot) {
            return RankedLock.underRank(RankedLock.MUTATION, () -> RankedLock.underRank(RankedLock.STORE, () -> super.processCommitted(batch, nextSlot)));
        }

        @Override
        public org.pragmatica.lang.Result<org.pragmatica.lang.Unit> applyEffects(ReplicaEntry entry) {
            return RankedLock.underRank(RankedLock.MUTATION, () -> RankedLock.underRank(RankedLock.STORE, () -> super.applyEffects(entry)));
        }

        @Override
        public org.pragmatica.lang.Unit onPhaseCompleted(long nextSlot) {
            return RankedLock.underRank(RankedLock.STORE, () -> super.onPhaseCompleted(nextSlot));
        }

        @Override
        public org.pragmatica.lang.Option<Object> get(StructuredKey key) {
            return RankedLock.underRank(RankedLock.STORE, () -> super.get(key));
        }

        @Override
        public <VV> org.pragmatica.lang.Option<VV> getTyped(StructuredKey key, Class<VV> valueClass) {
            return RankedLock.underRank(RankedLock.STORE, () -> super.getTyped(key, valueClass));
        }

        @Override
        public org.pragmatica.lang.Result<ReplicaSnapshot> captureReplicaSnapshot(ReplicaKeyFilter filter) {
            return RankedLock.underRank(RankedLock.STORE, () -> super.captureReplicaSnapshot(filter));
        }
    }

    /// The wrapper itself can fail: taking the store monitor while holding the ring lock is recorded and thrown.
    @Test
    void rankedLock_throwsOnAnOutOfOrderAcquire() {
        var ringLock = new RankedLock(RankedLock.RING);

        RankedLock.clearViolations();
        ringLock.lock();
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> RankedLock.underRank(RankedLock.STORE, () -> "store read under the ring lock"))
                                           .isInstanceOf(IllegalStateException.class)
                                           .hasMessageContaining("rank 2 while holding rank 3");
        } finally {
            ringLock.unlock();
        }

        assertThat(RankedLock.underRank(RankedLock.STORE, () -> RankedLock.underRank(RankedLock.RING, () -> "in order"))).isEqualTo("in order");
        assertThat(RankedLock.violations()).hasSize(1);
        RankedLock.clearViolations();
    }

    /// The real ring and a real store under the ranked wrapper and the same three-thread workload. Subscriber closes run on the thread that
    /// decided them (a synchronous close executor), and every close reads the store. Mutation (inverts the order): run the closes before the
    /// ring lock is released, and the close's store read is an out-of-order acquire that the wrapper reports.
    @Test
    void rankedLocks_onTheRealRingAndStore_reportNoOutOfOrderAcquire() throws InterruptedException {
        var store = new RankedStore();
        var stop = new AtomicBoolean();
        var closes = new AtomicInteger();
        var failures = new java.util.concurrent.CopyOnWriteArrayList<Throwable>();
        var ring = store.replicaRing();

        RankedLock.clearViolations();
        var acquiredBefore = RankedLock.acquisitions();

        commit(store, 0, RingFixtures.putIncarnation(1, "A"));
        var writer = new Thread(() -> {
            for (var slot = 1L; !stop.get(); slot += 2) {
                commit(store, slot, put("k" + (slot % 50), "v" + slot));
                store.applyEffects(new ReplicaEntry(IncarnationTag.incarnationTag(1, "A"), slot + 1, slot + 1, "raw-" + slot, List.of()));
            }
        }, "ranked-writer");
        var flusher = new Thread(() -> {
            while (!stop.get()) {
                ring.flush();
                ring.subscribeFrom(IncarnationTag.incarnationTag(1, "A"), ring.nextSlot(), ring.anchor(), CommitRing.subscription(200, _ -> {
                    closes.incrementAndGet();
                    store.get(new Key("k1"));
                }));
            }
        }, "ranked-flusher");
        var reader = new Thread(() -> {
            while (!stop.get()) {
                store.get(new Key("k1"));
                store.getTyped(RingFixtures.IncKey.INSTANCE, Object.class);
                store.captureReplicaSnapshot(ReplicaKeyFilter.includeAll());
            }
        }, "ranked-reader");
        var threads = List.of(writer, flusher, reader);

        threads.forEach(thread -> thread.setUncaughtExceptionHandler((_, failure) -> failures.add(failure)));
        threads.forEach(Thread::start);
        Thread.sleep(1_500);
        stop.set(true);
        for (var thread : threads) {
            thread.join(TIMEOUT_MILLIS);
        }

        assertThat(RankedLock.acquisitions()).as("control: the ring really takes the ranked lock (mutation: the ring ignores the lock it is given)").isGreaterThan(acquiredBefore);
        assertThat(closes.get()).as("control: subscribers overflowed and their closes, which read the store, ran").isPositive();
        assertThat(RankedLock.violations()).as("I-W13: no lock is taken out of order (mutation: run the closes under the ring lock)").isEmpty();
        assertThat(failures).isEmpty();
        assertThat(threads).as("every thread finished").noneMatch(Thread::isAlive);
    }

    /// `CommitRing` has no reference to the store, so it cannot take the store monitor or read storage while it holds its own lock.
    @Test
    void commitRing_hasNoReferenceToKVStore() throws IOException {
        var classes = Path.of(CommitRing.class.getProtectionDomain().getCodeSource().getLocation().getPath(), "org/pragmatica/cluster/state/kvstore");
        var offenders = Files.list(classes)
                             .filter(path -> path.getFileName().toString().startsWith("CommitRing"))
                             .filter(path -> mentionsKvStore(path))
                             .toList();

        assertThat(offenders).as("I-W13: no CommitRing class mentions KVStore in its constant pool (mutation: add a KVStore field or parameter)").isEmpty();
    }

    private static boolean mentionsKvStore(Path classFile) {
        try {
            return new String(Files.readAllBytes(classFile), java.nio.charset.StandardCharsets.ISO_8859_1).contains("KVStore");
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @FunctionalInterface
    private interface InterruptibleTask {
        void run() throws InterruptedException;
    }

    private static void runQuietly(InterruptibleTask task) {
        try {
            task.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
