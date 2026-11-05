// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.cluster.state.kvstore.KVStoreDispatcherTest.InstallingDeserializer;
import org.pragmatica.cluster.state.kvstore.KVStoreDispatcherTest.Key;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Serializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/// `notificationRevision` is MONOTONE per dispatcher for the life of the process (CTO ruling 2026-11-05; FSM spec R-3.1.7:
/// FSM2 orders its inputs by it and defines no epoch boundary). An engine stop's `reset()` zeroes the store's committed
/// revision, but the reset's remove burst is delivered at the last delivered revision +1, and later batches continue from
/// `max(last delivered + 1, committed)`. Adopted from the vd1A2 ordering probe (wave3/v-2648-A2), whose first arm read
/// the reset burst at revision 0 before the fix. Mutations: deliver every batch at its committed revision (the pre-fix
/// code), or drop the +1 (red: the first arm, exact revisions).
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class DispatcherRevisionMonotoneTest {
    final MessageRouter.MutableRouter router = MessageRouter.mutable();
    final HeldDelivery hold = HeldDelivery.heldDelivery();
    final InstallingDeserializer deserializer = new InstallingDeserializer();
    final List<KVStore<StructuredKey, Object>> opened = new ArrayList<>();
    final List<String> seen = new CopyOnWriteArrayList<>();
    final List<Long> seqs = new CopyOnWriteArrayList<>();
    final Set<Thread> threads = ConcurrentHashMap.newKeySet();

    KVStore<StructuredKey, Object> store() {
        var delivery = new NotificationDelivery.Dispatched("rev" + System.nanoTime(),
                                                           NotificationDelivery.ReplayRank.flat(),
                                                           DispatcherFailureSink.loggingOnly(),
                                                           hold,
                                                           (code, subject, _) -> Unit.unit(),
                                                           NotificationDelivery.DEFAULT_SLOW_HANDLER_NANOS);
        var store = new KVStore<StructuredKey, Object>(router, new Serializer() {
            @Override public <T> void write(ByteBuf buffer, T value) {}
        }, deserializer, delivery);
        opened.add(store);
        router.addRoute(KVStoreNotification.ValuePut.class,
                        (KVStoreNotification.ValuePut<StructuredKey, Object> p) -> record(store, "put", p.cause().key()));
        router.addRoute(KVStoreNotification.ValueRemove.class,
                        (KVStoreNotification.ValueRemove<StructuredKey, Object> r) -> record(store, "rm", r.cause().key()));
        return store;
    }

    void record(KVStore<StructuredKey, Object> store, String kind, StructuredKey key) {
        threads.add(Thread.currentThread());
        seqs.add(store.deliveringSeq().or(-1L));
        seen.add(kind + ":" + ((Key) key).value() + "@" + store.notificationRevision().or(-1L)
                 + (store.isReplaying() ? "r" : ""));
    }

    @AfterEach
    void closeStores() {
        hold.release();
        opened.forEach(KVStore::close);
    }

    static void committed(KVStore<StructuredKey, Object> store, Key key, Object value, long nextSlot) {
        store.processCommitted(store.createBatch(List.of(new KVCommand.Put<>(key, value))), nextSlot);
    }

    static long revisionOf(String entry) {
        var at = entry.substring(entry.indexOf('@') + 1).replace("r", "");
        return Long.parseLong(at);
    }

    void assertNeverRegresses() {
        for (int i = 1; i < seqs.size(); i++) {
            assertThat(seqs.get(i)).as("seq order at %d: %s", i, seqs).isGreaterThanOrEqualTo(seqs.get(i - 1));
        }
        for (int i = 1; i < seen.size(); i++) {
            assertThat(revisionOf(seen.get(i))).as("revision regressed at %d: %s", i, seen)
                                               .isGreaterThanOrEqualTo(revisionOf(seen.get(i - 1)));
        }
    }

    /// The engine stop path (shutdownAndReset -> reset(), then restore + replay, RE:1670 / RE:2696 / RE:2803). Before the
    /// fix the reset burst read `rm:a@0`, `rm:b@0`. Exact revisions: the burst at the last delivered +1 (10), a write
    /// committed below that after the reset at the next one (11), the replay at its install revision (12, above the
    /// floor), the next live batch at its slot (13).
    @Test
    void revisionsNeverRegressAcrossAnEngineStopRestoreReplay() throws Exception {
        var store = store();
        committed(store, new Key("a"), "one", 5);
        committed(store, new Key("b"), "two", 9);
        store.awaitDelivered(store.enqueuedSeq()).await();
        store.reset();
        store.awaitDelivered(store.enqueuedSeq()).await();
        committed(store, new Key("c"), "low", 3);
        store.awaitDelivered(store.enqueuedSeq()).await();
        deserializer.next = Map.of(new Key("a"), "one", new Key("k"), "three");
        store.restoreCommittedSnapshot(new byte[0], 12);
        store.replayNotifications();
        committed(store, new Key("m"), "four", 13);
        store.awaitDelivered(store.enqueuedSeq()).await();

        assertThat(threads).hasSize(1);
        assertThat(threads.iterator().next().getName()).isEqualTo(store.dispatcherThreadName());
        assertThat(seen).as("all deliveries: %s", seen).hasSize(9);
        assertThat(seen.subList(0, 2)).containsExactly("put:a@5", "put:b@9");
        assertThat(seen.subList(2, 4)).as("the reset burst carries last + 1").containsExactlyInAnyOrder("rm:a@10", "rm:b@10");
        assertThat(seen.get(4)).as("a write committed below the floor after the reset").isEqualTo("put:c@11");
        assertThat(seen.subList(5, 8)).as("the replay burst carries its install revision")
                                      .containsExactlyInAnyOrder("put:a@12r", "put:k@12r", "rm:c@12r");
        assertThat(seen.get(8)).isEqualTo("put:m@13");
        assertNeverRegresses();
    }

    /// Two resets in a row with nothing delivered between them: the second burst is empty and consumes no revision, so the
    /// next batch still carries the last delivered +1, never a revision a subscriber did not see skipped twice.
    @Test
    void anEmptyResetBurstConsumesNoRevision() throws Exception {
        var store = store();
        committed(store, new Key("a"), "one", 5);
        store.reset();
        store.reset();
        committed(store, new Key("b"), "two", 2);
        store.awaitDelivered(store.enqueuedSeq()).await();

        assertThat(seen).containsExactly("put:a@5", "rm:a@6", "put:b@7");
    }

    /// A live batch applied while a replay waits (the premise "the caller is the only live applier" broken on purpose).
    /// FIFO: the burst, carrying the restore revision, is delivered before the live batch. The burst is computed at
    /// dequeue, so it also carries the live key, labelled with the replay's (lower) revision.
    @Test
    void liveBatchDuringReplayIsDeliveredAfterTheBurst() throws Exception {
        var store = store();
        committed(store, new Key("a"), "one", 5);
        store.awaitDelivered(store.enqueuedSeq()).await();
        hold.holdFrom(store.enqueuedSeq() + 1);
        deserializer.next = Map.of(new Key("a"), "one", new Key("k"), "three");
        store.restoreCommittedSnapshot(new byte[0], 12);
        assertThat(hold.awaitHeld(10)).isTrue();
        var replayEnqueued = store.enqueuedSeq() + 1;
        var replayDone = new CountDownLatch(1);
        Thread.ofPlatform().start(() -> replayThenSignal(store, replayDone));
        while (store.enqueuedSeq() < replayEnqueued) {
            Thread.sleep(5);
        }
        committed(store, new Key("l"), "live", 20);
        hold.release();
        assertThat(replayDone.await(10, TimeUnit.SECONDS)).isTrue();
        store.awaitDelivered(store.enqueuedSeq()).await();

        var liveIndex = seen.indexOf("put:l@20");
        assertThat(liveIndex).as("live delivered: %s", seen).isGreaterThanOrEqualTo(0);
        for (int i = 0; i < seen.size(); i++) {
            if (seen.get(i).endsWith("r")) {
                assertThat(i).as("replayed entry %s after the live batch: %s", seen.get(i), seen).isLessThan(liveIndex);
            }
        }
        assertNeverRegresses();
    }

    private static void replayThenSignal(KVStore<StructuredKey, Object> store, CountDownLatch done) {
        store.replayNotifications();
        done.countDown();
    }

    /// Four concurrent writers: every delivery on one thread, `deliveringSeq` increasing, each writer's own puts delivered
    /// in its program order, and the revision increasing per delivered batch (every batch here is at committed revision 0).
    @Test
    void concurrentWritersDeliverInCommitOrderOnOneThread() throws Exception {
        var store = store();
        var writers = 4;
        var perWriter = 500;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(writers);
        for (int w = 0; w < writers; w++) {
            var id = w;
            Thread.ofPlatform().start(() -> write(store, id, perWriter, start, done));
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        store.awaitDelivered(store.enqueuedSeq()).await();

        assertThat(seen).hasSize(writers * perWriter);
        assertThat(threads).hasSize(1);
        for (int i = 1; i < seqs.size(); i++) {
            assertThat(seqs.get(i)).as("seq order at %d", i).isGreaterThan(seqs.get(i - 1));
            assertThat(revisionOf(seen.get(i))).as("revision increases per batch at %d", i)
                                               .isGreaterThan(revisionOf(seen.get(i - 1)));
        }
        for (int w = 0; w < writers; w++) {
            var prefix = "put:w" + w + "-";
            var mine = seen.stream()
                           .filter(s -> s.startsWith(prefix))
                           .map(s -> Integer.parseInt(s.substring(prefix.length(), s.indexOf('@'))))
                           .toList();
            for (int i = 0; i < mine.size(); i++) {
                assertThat(mine.get(i)).as("writer %d program order", w).isEqualTo(i);
            }
        }
    }

    private static void write(KVStore<StructuredKey, Object> store, int id, int count, CountDownLatch start, CountDownLatch done) {
        try {
            start.await();
            for (int i = 0; i < count; i++) {
                store.process(store.createBatch(List.of(new KVCommand.Put<>(new Key("w" + id + "-" + i), i))));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            done.countDown();
        }
    }
}
