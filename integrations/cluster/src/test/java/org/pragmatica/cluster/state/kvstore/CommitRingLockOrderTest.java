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

    private static long[] deadlocked() {
        var ids = ManagementFactory.getThreadMXBean().findDeadlockedThreads();

        return ids == null ? new long[0] : ids;
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
            for (var slot = 1L; !stop.get(); slot++) {
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
        var cycle = deadlocked();

        stop.set(true);
        for (var thread : threads) {
            thread.join(TIMEOUT_MILLIS);
        }

        assertThat(cycle).as("I-W13 (mutation: read the tag via kvStore.getTyped under the ring lock, see the control): no thread is in a deadlock cycle").isEmpty();
        assertThat(threads).as("every thread finished").noneMatch(Thread::isAlive);
        assertThat(closeRanUnderRingLock.get()).as("the transport close, which reads the store, never ran under the ring lock").isFalse();
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
