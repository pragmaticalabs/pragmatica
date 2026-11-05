// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;

/// D1 exits at the store level (KVStore dispatcher design §5). Every probe that could pass by race holds the dispatcher
/// with [HeldDelivery] and asserts the hold was reached before it asserts anything else.
/// Every test is bounded on a separate thread: a mutation that deadlocks an arm reads red, never as a hung fork.
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class KVStoreDispatcherTest {
    record Key(String value) implements StructuredKey {}

    static final Key A = new Key("a");
    static final Key B = new Key("b");
    static final Key K = new Key("k");
    static final Key L = new Key("l");
    static final Duration BOUND = Duration.ofSeconds(10);

    /// Records every stop request; the production sink halts, the in-JVM one stops the node.
    static final class RecordingSink implements DispatcherFailureSink {
        final List<String> reasons = new CopyOnWriteArrayList<>();
        final List<Throwable> causes = new CopyOnWriteArrayList<>();
        final CountDownLatch stopped = new CountDownLatch(1);

        @Override
        public Unit stopNode(String reason, Throwable cause) {
            reasons.add(reason);
            causes.add(cause);
            stopped.countDown();
            return Unit.unit();
        }

        boolean awaitStopped() throws InterruptedException {
            return stopped.await(BOUND.toSeconds(), TimeUnit.SECONDS);
        }
    }

    /// A deserializer that installs whatever map the test sets, so a silent install needs no codec.
    static final class InstallingDeserializer implements Deserializer {
        volatile Map<StructuredKey, Object> next = Map.of();

        @Override
        @SuppressWarnings("unchecked")
        public <T> T read(ByteBuf buffer) {
            return (T) next;
        }
    }

    final MessageRouter.MutableRouter router = MessageRouter.mutable();
    final HeldDelivery hold = HeldDelivery.heldDelivery();
    final RecordingSink sink = new RecordingSink();
    final InstallingDeserializer deserializer = new InstallingDeserializer();
    final List<KVStore<StructuredKey, Object>> opened = new ArrayList<>();
    /// Every operator event the fixture's stores raise, as `code|subject`.
    final List<String> operatorEvents = new CopyOnWriteArrayList<>();

    KVStore<StructuredKey, Object> store() {
        return store(NotificationDelivery.ReplayRank.flat());
    }

    KVStore<StructuredKey, Object> store(NotificationDelivery.ReplayRank rank) {
        return store(router, rank, NotificationDelivery.DEFAULT_SLOW_HANDLER_NANOS);
    }

    KVStore<StructuredKey, Object> store(MessageRouter routedBy, NotificationDelivery.ReplayRank rank, long slowHandlerNanos) {
        var delivery = new NotificationDelivery.Dispatched("t" + System.nanoTime(), rank, sink, hold,
                                                           (code, subject, _) -> recordOperatorEvent(code.code() + "|" + subject),
                                                           slowHandlerNanos);
        var store = new KVStore<StructuredKey, Object>(routedBy, new Serializer() {
            @Override public <T> void write(ByteBuf buffer, T value) {}
        }, deserializer, delivery);
        opened.add(store);
        return store;
    }

    /// The recorded operator events of one code; a slow-handler event (5 ms) can join any test on a loaded host.
    List<String> eventsOf(String code) {
        return operatorEvents.stream()
                             .filter(event -> event.startsWith(code + "|"))
                             .toList();
    }

    Unit recordOperatorEvent(String event) {
        operatorEvents.add(event);

        return Unit.unit();
    }

    @AfterEach
    void closeStores() {
        hold.release();
        opened.forEach(KVStore::close);
    }

    static List<Object> put(KVStore<StructuredKey, Object> store, Key key, Object value) {
        return store.process(store.createBatch(List.of(new KVCommand.Put<>(key, value))));
    }

    static List<Object> remove(KVStore<StructuredKey, Object> store, Key key) {
        return store.process(store.createBatch(List.of(new KVCommand.Remove<>(key, Option.none()))));
    }

    static void delivered(KVStore<StructuredKey, Object> store) {
        store.awaitDelivered(store.enqueuedSeq()).await();
    }

    /// The private state the tests below pin has no accessor (production is owned by another lane): read it by reflection.
    static Object privateField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    static NotificationDispatcher dispatcherOf(KVStore<?, ?> store) throws Exception {
        return (NotificationDispatcher) privateField(store, "dispatcher");
    }

    static <T> boolean resolvedWithin(Promise<T> promise, long millis) {
        return promise.await(TimeSpan.timeSpan(millis).millis()).isSuccess();
    }

    @Nested
    class Delivery {
        /// (a) handlers run on the dispatcher thread, never the writer's: mutation, deliver inline.
        @Test
        void handlerRunsOnTheDispatcherThread_notTheWriter() throws Exception {
            var store = store();
            var thread = new AtomicReference<String>();
            var delivered = new CountDownLatch(1);
            router.addRoute(KVStoreNotification.ValuePut.class, _ -> {
                thread.set(Thread.currentThread().getName());
                delivered.countDown();
            });

            put(store, A, "one");

            assertThat(delivered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(thread.get()).isEqualTo(store.dispatcherThreadName())
                                    .isNotEqualTo(Thread.currentThread().getName());
        }

        /// The delivery thread is a daemon, so a store nobody closed cannot hold the JVM open. Mutation: a non-daemon thread (red).
        @Test
        void deliveryThreadIsADaemon() throws Exception {
            var store = store();
            var daemon = new AtomicReference<Boolean>();
            var delivered = new CountDownLatch(1);
            router.addRoute(KVStoreNotification.ValuePut.class, _ -> {
                daemon.set(Thread.currentThread().isDaemon());
                delivered.countDown();
            });

            put(store, A, "one");

            assertThat(delivered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(daemon.get()).isTrue();
        }

        /// Once the removal batch is delivered the dispatcher keeps no tracking entry for the removed key (the tombstone
        /// is trimmed at delivery). The removal is the last batch, so its tombstone seq equals the delivered seq.
        /// Mutations: record no tombstone / never trim it / trim strictly below the delivered seq (each leaves an entry, red).
        @Test
        void removedKeyLeavesNoTrackingEntryOnceDelivered() throws Exception {
            var store = store();
            router.addRoute(KVStoreNotification.ValuePut.class, _ -> {});
            router.addRoute(KVStoreNotification.ValueRemove.class, _ -> {});

            put(store, A, "one");
            remove(store, A);
            delivered(store);
            sleep(200);

            assertThat((Map<?, ?>) privateField(dispatcherOf(store), "lastModifiedSeq")).isEmpty();
        }

        /// (d) FIFO across a reentrant write:a write made by batch 1's handler is delivered after batch 2, which was
        /// already queued: mutation, deliver a write made on the dispatcher thread at once.
        @Test
        void reentrantWriteFromAHandlerQueuesBehindEarlierBatches() throws Exception {
            var store = store();
            var order = new CopyOnWriteArrayList<String>();
            var done = new CountDownLatch(3);
            router.addRoute(KVStoreNotification.ValuePut.class, (KVStoreNotification.ValuePut<StructuredKey, Object> p) -> {
                order.add(((Key) p.cause().key()).value());
                if (p.cause().key().equals(A)) {
                    put(store, K, "nested");
                }
                done.countDown();
            });
            hold.holdFrom(1);

            put(store, A, "one");
            assertThat(hold.awaitHeld(10)).isTrue();
            put(store, B, "two");
            hold.release();

            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(order).containsExactly("a", "b", "k");
        }

        /// (f) a refused command emits nothing, and its batch still delivers (empty), so its promise completes.
        @Test
        void refusedCommandEmitsNothingButItsBatchStillCompletes() {
            record Marked(String v) implements LeaderAuthorized {}
            var store = store();
            var notifications = new CopyOnWriteArrayList<Object>();
            router.addRoute(KVStoreNotification.ValuePut.class, notifications::add);

            var before = store.enqueuedSeq();
            put(store, A, new Marked("refused"));
            var promise = Promise.<Unit>promise();
            store.completeAfterDelivery(promise, Unit.unit());

            assertThat(store.enqueuedSeq()).isEqualTo(before + 1);
            assertThat(resolvedWithin(promise, 10_000)).isTrue();
            assertThat(notifications).isEmpty();
            assertThat(store.get(A).isEmpty()).isTrue();
        }

        /// (f) the epoch-fence arm: a `Put` whose epoch is older than the committed one is refused, emits nothing, and
        /// its batch still delivers (empty), so its promise completes. Mutation: publish the refused put.
        @Test
        void staleEpochPutEmitsNothingButItsBatchStillCompletes() {
            record Epoched(String v, Long epoch) implements EpochBearing<Long> {
                @Override
                public Long fenceEpoch() {
                    return epoch;
                }
            }
            var store = store();
            var notifications = new CopyOnWriteArrayList<Object>();
            put(store, A, new Epoched("current", 2L));
            var current = Promise.<Unit>promise();
            store.completeAfterDelivery(current, Unit.unit());
            assertThat(resolvedWithin(current, 10_000)).isTrue();
            router.addRoute(KVStoreNotification.ValuePut.class, notifications::add);

            put(store, A, new Epoched("stale", 1L));
            var promise = Promise.<Unit>promise();
            store.completeAfterDelivery(promise, Unit.unit());

            assertThat(resolvedWithin(promise, 10_000)).isTrue();
            assertThat(notifications).isEmpty();
            assertThat(store.get(A)
                            .map(Epoched.class::cast)
                            .map(Epoched::v)).isEqualTo(Option.some("current"));
        }

        /// (g) enqueue never blocks: with the dispatcher held, the writer applies N batches: mutation, a capacity-1
        /// blocking queue (the preemptive bound turns its deadlock red).
        @Test
        void enqueueNeverBlocksUnderAHeldDispatcher() {
            var store = store();
            router.addRoute(KVStoreNotification.ValuePut.class, _ -> {});
            hold.holdFrom(1);

            assertTimeoutPreemptively(BOUND, () -> {
                put(store, A, 0);
                assertThat(hold.awaitHeld(10)).isTrue();
                for (int i = 1; i <= 50; i++) {
                    put(store, A, i);
                }
            });
            assertThat(store.enqueuedSeq() - store.deliveredSeq()).isEqualTo(51);
        }

        /// (i) a Noop barrier resolves only after delivery of everything before it: mutation, skip empty batches.
        @Test
        void noopBarrierResolvesOnlyAfterDelivery() {
            var store = store();
            var seen = new CopyOnWriteArrayList<Object>();
            router.addRoute(KVStoreNotification.ValuePut.class, (KVStoreNotification.ValuePut<StructuredKey, Object> p) -> seen.add(p.cause().value()));
            hold.holdFrom(1);

            put(store, A, "one");
            assertThat(hold.awaitHeld(10)).isTrue();
            store.process(store.createBatch(List.of(new KVCommand.Noop<StructuredKey>(A))));
            var barrier = Promise.<Unit>promise();
            store.completeAfterDelivery(barrier, Unit.unit());

            assertThat(resolvedWithin(barrier, 300)).as("barrier must wait for the held batch").isFalse();
            hold.release();
            assertThat(resolvedWithin(barrier, 10_000)).isTrue();
            assertThat(seen).containsExactly("one");
        }

        /// notificationRevision per delivered batch (FSM spec R-3.1.7): increasing across batches delivered in order; a
        /// replayed batch carries the revision of the state it replays: mutation, a constant revision.
        @Test
        void deliveredBatchesCarryIncreasingRevisions_replayCarriesItsOwn() throws Exception {
            var store = store();
            var revisions = new CopyOnWriteArrayList<String>();
            var done = new CountDownLatch(3);
            router.addRoute(KVStoreNotification.ValuePut.class, (KVStoreNotification.ValuePut<StructuredKey, Object> p) -> {
                revisions.add(((Key) p.cause().key()).value() + "@" + store.notificationRevision().or(-1L) + (store.isReplaying() ? "r" : ""));
                done.countDown();
            });

            store.processCommitted(store.createBatch(List.of(new KVCommand.Put<>(A, "one"))), 5);
            store.processCommitted(store.createBatch(List.of(new KVCommand.Put<>(B, "two"))), 9);
            store.awaitDelivered(store.enqueuedSeq()).await();
            deserializer.next = Map.of(A, "one", B, "two", K, "three");
            store.restoreCommittedSnapshot(new byte[0], 12);
            store.replayNotifications();

            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(revisions).containsExactly("a@5", "b@9", "k@12r");
            assertThat(store.notificationRevision().isEmpty()).as("no revision outside a delivery").isTrue();
        }
    }

    @Nested
    class ReadYourWrite {
        /// (b), store level: the apply promise resolves only after its batch was delivered, so a read through a
        /// subscriber's view after it resolves sees the write, with the dispatcher held: mutation, complete at apply.
        @Test
        void applyPromiseResolvesAfterDeliveryUnderAHeldDispatcher() {
            var store = store();
            var view = Collections.synchronizedMap(new java.util.HashMap<StructuredKey, Object>());
            router.addRoute(KVStoreNotification.ValuePut.class, (KVStoreNotification.ValuePut<StructuredKey, Object> p) -> view.put(p.cause().key(), p.cause().value()));
            hold.holdFrom(1);

            var promise = Promise.<List<Object>>promise();
            var results = put(store, A, "one");
            store.completeAfterDelivery(promise, results);

            assertThat(hold.awaitHeld(10)).isTrue();
            assertThat(resolvedWithin(promise, 300)).as("not before delivery").isFalse();
            hold.release();
            assertThat(resolvedWithin(promise, 10_000)).isTrue();
            assertThat(view).containsEntry(A, "one");
        }

        /// An apply promise parks behind its OWN batch, not the one before it: with batch 2's handler still running, the
        /// promise of batch 2 stays unresolved, and resolves once that handler returns.
        /// Mutation: park on the previous seq (resolves while batch 2 is still being delivered, red).
        @Test
        void applyPromiseNotResolvedBeforeItsOwnBatchIsDelivered() throws Exception {
            var store = store();
            var releaseB = new CountDownLatch(1);
            var inB = new CountDownLatch(1);
            router.addRoute(KVStoreNotification.ValuePut.class, (KVStoreNotification.ValuePut<StructuredKey, Object> p) -> {
                if (p.cause().key().equals(B)) {
                    inB.countDown();
                    awaitUninterrupted(releaseB);
                }
            });
            hold.holdFrom(1);
            put(store, A, "one");
            assertThat(hold.awaitHeld(10)).isTrue();
            put(store, B, "two");
            var promise = Promise.<Unit>promise();
            store.completeAfterDelivery(promise, Unit.unit());

            hold.release();

            assertThat(inB.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(resolvedWithin(promise, 300)).as("batch B (its own) is still being delivered").isFalse();
            releaseB.countDown();
            assertThat(resolvedWithin(promise, 10_000)).isTrue();
        }

        private static void awaitUninterrupted(CountDownLatch latch) {
            try {
                latch.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /// (nd23) read-your-write with an earlier batch still undelivered: a second writer's apply promise waits for ITS OWN
        /// batch, not just the previous one. Batch 1 (writer A) is held; batch 2 (writer B) has a handler blocked on a latch.
        /// Releasing only batch 1 resolves A and must leave B's promise pending while B's notification is in flight; B
        /// resolves only after that notification ran. Mutation: park at `enqueuedSeq - 1`: B resolves with batch 1, red.
        @Test
        void secondWritersPromiseWaitsForItsOwnBatch() throws Exception {
            var store = store();
            var bEntered = new CountDownLatch(1);
            var releaseB = new CountDownLatch(1);
            var bNotificationRan = new java.util.concurrent.atomic.AtomicBoolean(false);
            router.addRoute(KVStoreNotification.ValuePut.class,
                            (KVStoreNotification.ValuePut<StructuredKey, Object> p) -> awaitIfB(p, bEntered, releaseB, bNotificationRan));
            hold.holdFrom(1);

            var promiseA = Promise.<Unit>promise();
            put(store, A, "one");
            store.completeAfterDelivery(promiseA, Unit.unit());
            var promiseB = Promise.<Unit>promise();
            put(store, B, "two");
            store.completeAfterDelivery(promiseB, Unit.unit());
            assertThat(hold.awaitHeld(10)).as("batch 1 is held").isTrue();

            hold.release();

            assertThat(resolvedWithin(promiseA, 10_000)).as("A resolves with its own batch").isTrue();
            assertThat(bEntered.await(10, TimeUnit.SECONDS)).as("batch 2's notification is in flight").isTrue();
            assertThat(resolvedWithin(promiseB, 300)).as("B waits for its own batch, not the previous one").isFalse();
            assertThat(bNotificationRan.get()).isFalse();
            releaseB.countDown();
            assertThat(resolvedWithin(promiseB, 10_000)).isTrue();
            assertThat(bNotificationRan.get()).as("B resolved after its notification ran").isTrue();
        }

        private static Unit awaitIfB(KVStoreNotification.ValuePut<StructuredKey, Object> put, CountDownLatch entered,
                                     CountDownLatch release, java.util.concurrent.atomic.AtomicBoolean ran) {
            if (put.cause().key().equals(B)) {
                entered.countDown();
                await(release);
                ran.set(true);
            }

            return Unit.unit();
        }

        private static void await(CountDownLatch latch) {
            try {
                latch.await(BOUND.toSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /// Lane-1 decision review: a parked apply promise waits for the newest `enqueuedSeq` AT PARK TIME, a target fixed
        /// when it parks, so a continuous writer stream can delay it only by the batches enqueued between its own batch
        /// and its park, never starve it. A writer keeps the queue about 50 batches deep for up to 3 s while each
        /// delivery takes about 1 ms; the parked promise must resolve within 1 s while the stream is still running.
        /// Mutation: complete waiters only once the queue drains (`deliveredSeq >= enqueuedSeq` at delivery): it starves
        /// until the stream stops, red.
        @Test
        void parkedPromiseCompletesUnderAContinuousWriterStream() throws Exception {
            var store = store();
            router.addRoute(KVStoreNotification.ValuePut.class, _ -> sleep(1));
            var streamDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            var stop = new CountDownLatch(1);
            var writer = Thread.ofPlatform().daemon().start(() -> writeContinuously(store, streamDeadline, stop));
            awaitDepth(store, 20);

            var promise = Promise.<Unit>promise();
            put(store, A, "mine");
            var target = store.enqueuedSeq();
            store.completeAfterDelivery(promise, Unit.unit());

            assertThat(resolvedWithin(promise, 1_000)).as("the parked promise completes while the stream runs").isTrue();
            assertThat(writer.isAlive()).as("the stream was still running at completion").isTrue();
            assertThat(store.enqueuedSeq()).as("later writers kept enqueuing").isGreaterThan(target);
            stop.countDown();
            writer.join(BOUND.toMillis());
        }

        private static void writeContinuously(KVStore<StructuredKey, Object> store, long deadline, CountDownLatch stop) {
            var i = 0L;

            while (System.nanoTime() < deadline && stop.getCount() > 0) {
                if (store.enqueuedSeq() - store.deliveredSeq() < 50) {
                    put(store, B, ++i);
                } else {
                    Thread.onSpinWait();
                }
            }
        }

        private static void awaitDepth(KVStore<StructuredKey, Object> store, long depth) {
            var deadline = System.nanoTime() + BOUND.toNanos();

            while (store.enqueuedSeq() - store.deliveredSeq() < depth && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }

            assertThat(store.enqueuedSeq() - store.deliveredSeq()).as("the stream reached its depth").isGreaterThanOrEqualTo(depth);
        }

        /// (q) a completion continuation awaiting a second commit does not delay a third writer's completion: mutation,
        /// one FIFO completion thread.
        @Test
        void blockedContinuationDoesNotDelayAnotherWritersCompletion() {
            var store = store();
            router.addRoute(KVStoreNotification.ValuePut.class, _ -> {});
            var second = Promise.<Unit>promise();
            var first = Promise.<Unit>promise();
            var firstContinuationDone = new CountDownLatch(1);
            first.map(_ -> second.await())
                 .onResultRun(firstContinuationDone::countDown);

            put(store, A, "one");
            store.completeAfterDelivery(first, Unit.unit());
            var third = Promise.<Unit>promise();
            put(store, B, "two");
            store.completeAfterDelivery(third, Unit.unit());

            assertThat(resolvedWithin(third, 10_000)).as("third completes while first's continuation waits").isTrue();
            second.succeed(Unit.unit());
        }
    }

    @Nested
    class Fence {
        /// (c) fence, both directions: hold at batch 1 whose handler reads K; batch 2 changes K: one read-ahead for that
        /// handler and K, phase=live. Control: batch 2 changes L: zero. Mutations: skip the check (first arm red) /
        /// compare against enqueuedSeq instead of K's seq (control red).
        /// The first read-ahead of a notification type also raises `kv-dispatch-read-ahead` with the key type as its subject (v-2648 F5).
        @Test
        void readOfAKeyALaterBatchWroteCountsOneReadAhead() throws Exception {
            assertThat(readAheadsWhenBatchTwoWrites(K)).isEqualTo(1);
            assertThat(eventsOf("kv-dispatch-read-ahead")).containsExactly("kv-dispatch-read-ahead|Key");
        }

        @Test
        void control_readOfAKeyNoLaterBatchWroteCountsNothing() throws Exception {
            assertThat(readAheadsWhenBatchTwoWrites(L)).isZero();
            assertThat(eventsOf("kv-dispatch-read-ahead")).isEmpty();
        }

        /// A handler reading the key its OWN batch wrote is not a read-ahead (fence compares strictly after the batch).
        /// Mutation: the point-read fence is inclusive (counts one, red).
        @Test
        void handlerReadingItsOwnBatchsKeyCountsNoReadAhead() throws Exception {
            var store = store();
            var done = new CountDownLatch(1);
            router.addRoute(KVStoreNotification.ValuePut.class, (KVStoreNotification.ValuePut<StructuredKey, Object> p) -> {
                store.get(p.cause().key());
                done.countDown();
            });

            put(store, A, "one");

            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            delivered(store);
            assertThat(store.readAheadReads(NotificationDispatcher.PHASE_LIVE, "Key")).isZero();
        }

        /// A snapshot taken by a handler while no later batch is enqueued is not a read-ahead.
        /// Mutation: the snapshot fence is inclusive (counts one, red).
        @Test
        void snapshotWithNoLaterBatchCountsNoSnapshotReadAhead() throws Exception {
            var store = store();
            var done = new CountDownLatch(1);
            router.addRoute(KVStoreNotification.ValuePut.class, _ -> {
                store.snapshot();
                done.countDown();
            });

            put(store, A, "one");

            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            delivered(store);
            assertThat(store.deliveryMetrics().snapshotReadAheadsLive()).isZero();
        }

        /// (kv20) a whole-store read by a handler on the delivering thread counts one snapshot read-ahead when a later
        /// batch is already enqueued, and nothing when none is. Mutation: the store's `snapshot()` skips the fence call.
        @Test
        void snapshotReadWhileALaterBatchIsEnqueuedCountsOneSnapshotReadAhead() throws Exception {
            assertThat(snapshotReadAheadsWithLaterBatch(true)).isEqualTo(1);
        }

        @Test
        void control_snapshotReadWithNoLaterBatchCountsNothing() throws Exception {
            assertThat(snapshotReadAheadsWithLaterBatch(false)).isZero();
        }

        private long snapshotReadAheadsWithLaterBatch(boolean laterBatch) throws Exception {
            var store = store();
            var done = new CountDownLatch(1);
            router.addRoute(KVStoreNotification.ValuePut.class, (KVStoreNotification.ValuePut<StructuredKey, Object> p) -> {
                if (p.cause().key().equals(A)) {
                    store.snapshot();
                    done.countDown();
                }
            });
            hold.holdFrom(1);

            put(store, A, "one");
            assertThat(hold.awaitHeld(10)).isTrue();
            if (laterBatch) {
                put(store, L, "later");
            }
            hold.release();

            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            store.awaitDelivered(store.enqueuedSeq()).await();
            assertThat(store.deliveryMetrics().snapshotReadAheadsReplay()).isZero();
            return store.deliveryMetrics().snapshotReadAheadsLive();
        }

        private long readAheadsWhenBatchTwoWrites(Key written) throws Exception {
            var store = store();
            put(store, K, "k0");
            var done = new CountDownLatch(1);
            router.addRoute(KVStoreNotification.ValuePut.class, (KVStoreNotification.ValuePut<StructuredKey, Object> p) -> {
                if (p.cause().key().equals(A)) {
                    store.get(K);
                    done.countDown();
                }
            });
            store.awaitDelivered(store.enqueuedSeq()).await();
            hold.holdFrom(store.enqueuedSeq() + 1);

            put(store, A, "one");
            assertThat(hold.awaitHeld(10)).isTrue();
            put(store, written, "changed");
            hold.release();

            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            store.awaitDelivered(store.enqueuedSeq()).await();
            assertThat(store.deliveryMetrics().readAheadReadsReplay()).isZero();
            return store.readAheadReads(NotificationDispatcher.PHASE_LIVE, "Key");
        }
    }

    @Nested
    class Replay {
        /// (e) replayNotifications() returns only after its burst is delivered. The dispatcher is held before the
        /// replay item, the replay runs on its own thread, and the call must not have returned while the hold stands,
        /// so the probe never passes by race: mutation, return at enqueue.
        @Test
        void replayReturnsOnlyAfterItsBurstIsDelivered() throws Exception {
            var store = store();
            var burst = new CopyOnWriteArrayList<Object>();
            router.addRoute(KVStoreNotification.ValuePut.class, (KVStoreNotification.ValuePut<StructuredKey, Object> p) -> burst.add(p.cause().key()));
            deserializer.next = Map.of(A, "one", B, "two");
            store.restoreSnapshot(new byte[0]);
            hold.holdFrom(store.enqueuedSeq() + 1);
            var returned = new CountDownLatch(1);
            var burstAtReturn = new AtomicReference<List<Object>>();

            Thread.ofVirtual().start(() -> returnAfterReplay(store, burst, burstAtReturn, returned));

            assertThat(hold.awaitHeld(10)).as("the replay item reached the hold").isTrue();
            assertThat(returned.await(300, TimeUnit.MILLISECONDS)).as("not returned while its burst is held").isFalse();
            hold.release();
            assertThat(returned.await(BOUND.toSeconds(), TimeUnit.SECONDS)).isTrue();
            assertThat(burstAtReturn.get()).as("the whole burst was delivered at return").containsExactlyInAnyOrder(A, B);
        }

        private static void returnAfterReplay(KVStore<StructuredKey, Object> store,
                                              List<Object> burst,
                                              AtomicReference<List<Object>> burstAtReturn,
                                              CountDownLatch returned) {
            store.replayNotifications();
            burstAtReturn.set(List.copyOf(burst));
            returned.countDown();
        }

        /// (t) boot read-ahead is zero: the replay caller is the only live applier, so a burst handler reading a key the
        /// caller writes right after the replay never reads ahead: mutation, a non-blocking replayNotifications().
        @Test
        void burstHandlerNeverReadsAheadOfTheReplayCaller() {
            var store = store();
            router.addRoute(KVStoreNotification.ValuePut.class, (KVStoreNotification.ValuePut<StructuredKey, Object> p) -> {
                if (store.isReplaying()) {
                    sleep(50);
                    store.get(L);
                }
            });
            deserializer.next = Map.of(A, "one", B, "two");
            store.restoreSnapshot(new byte[0]);

            store.replayNotifications();
            put(store, L, "live");
            store.awaitDelivered(store.enqueuedSeq()).await();

            assertThat(store.deliveryMetrics().readAheadReadsReplay()).isZero();
        }

        /// (o) install with undelivered batches ahead: no phantom key, no missed restore notification: mutation, diff
        /// against the last replay instead of the delivered view.
        @Test
        void installBehindUndeliveredBatchesLeavesNoPhantomKey() {
            var store = store();
            var removed = new CopyOnWriteArrayList<Object>();
            router.addRoute(KVStoreNotification.ValuePut.class, _ -> {});
            router.addRoute(KVStoreNotification.ValueRemove.class, (KVStoreNotification.ValueRemove<StructuredKey, Object> r) -> removed.add(r.cause().key()));
            hold.holdFrom(1);

            put(store, A, "live");
            assertThat(hold.awaitHeld(10)).isTrue();
            deserializer.next = Map.of(B, "restored");
            store.restoreSnapshot(new byte[0]);
            hold.release();
            store.replayNotifications();

            assertThat(removed).as("the live key absent from the snapshot gets its remove").containsExactly(A);
        }

        /// (r) cold-boot replay, then a mid-life install changing one key, fires exactly one notification: mutation, the
        /// delivered view skips replay notifications.
        @Test
        void midLifeInstallChangingOneKeyFiresExactlyOneNotification() {
            var store = store();
            var puts = new CopyOnWriteArrayList<Object>();
            router.addRoute(KVStoreNotification.ValuePut.class, (KVStoreNotification.ValuePut<StructuredKey, Object> p) -> puts.add(p.cause().key()));
            deserializer.next = Map.of(A, "one", B, "two");
            store.restoreSnapshot(new byte[0]);
            store.replayNotifications();
            puts.clear();

            deserializer.next = Map.of(A, "one", B, "changed");
            store.restoreSnapshot(new byte[0]);
            store.replayNotifications();

            assertThat(puts).containsExactly(B);
        }

        /// (z), store level: with the injected rank the burst is rank-major (each rank's removals, then its puts), so a
        /// rank-1 put precedes a rank-2 removal; the control arm with the flat rank shows the probe reaches an inversion
        /// (all removals first). Mutations: flat rank (red) / removals-first instead of rank-major (red).
        @Test
        void rankedReplayEmitsRankMajor() {
            assertThat(replayOrder(key -> ((Key) key).value().equals("p1") ? 1 : 2)).containsExactly("put:p1", "remove:r2");
        }

        @Test
        void control_flatRankEmitsAllRemovalsFirst() {
            assertThat(replayOrder(NotificationDelivery.ReplayRank.flat())).containsExactly("remove:r2", "put:p1");
        }

        /// (kv13) put order across ranks: three fresh keys replay rank-ascending, whichever way the ranks run against the
        /// storage's iteration order (one arm ascending by name, one descending, so a flat rank matches at most one).
        /// Mutation: puts bucketed under rank 0 (replay puts flat), red in one arm.
        @Test
        void rankedReplayEmitsPutsInRankOrder() {
            assertThat(putOrder(key -> ((Key) key).value().charAt(1) - '0')).containsExactly("put:p1", "put:p2", "put:p3");
            assertThat(putOrder(key -> 4 - (((Key) key).value().charAt(1) - '0'))).containsExactly("put:p3", "put:p2", "put:p1");
        }

        private List<String> putOrder(NotificationDelivery.ReplayRank rank) {
            var store = store(rank);
            var order = new CopyOnWriteArrayList<String>();
            router.addRoute(KVStoreNotification.ValuePut.class, (KVStoreNotification.ValuePut<StructuredKey, Object> p) -> order.add("put:" + ((Key) p.cause().key()).value()));
            deserializer.next = Map.of(new Key("p1"), "1", new Key("p2"), "2", new Key("p3"), "3");
            store.restoreSnapshot(new byte[0]);
            store.replayNotifications();
            return List.copyOf(order);
        }

        /// (kv17) a delivered removal leaves the delivered view: put k, remove k, deliver; a replay with k absent from
        /// storage emits no `remove:k`, and a replay that installs k again with its old value emits `put:k`.
        /// Mutation: the view ignores a delivered `ValueRemove`: the first replay emits `remove:k`, red.
        @Test
        void removedKeyLeavesTheDeliveredView() {
            var store = store();
            var order = new CopyOnWriteArrayList<String>();
            router.addRoute(KVStoreNotification.ValuePut.class, (KVStoreNotification.ValuePut<StructuredKey, Object> p) -> order.add("put:" + ((Key) p.cause().key()).value()));
            router.addRoute(KVStoreNotification.ValueRemove.class, (KVStoreNotification.ValueRemove<StructuredKey, Object> r) -> order.add("remove:" + ((Key) r.cause().key()).value()));
            put(store, K, "v");
            store.process(store.createBatch(List.of(new KVCommand.Remove<>(K))));
            store.awaitDelivered(store.enqueuedSeq()).await();
            assertThat(order).as("the put and the remove were delivered").containsExactly("put:k", "remove:k");
            order.clear();

            deserializer.next = Map.of();
            store.restoreSnapshot(new byte[0]);
            store.replayNotifications();
            assertThat(order).as("k left the delivered view, so no remove burst").isEmpty();

            deserializer.next = Map.of(K, "v");
            store.restoreSnapshot(new byte[0]);
            store.replayNotifications();
            assertThat(order).as("k is new to the delivered view, so a put is emitted").containsExactly("put:k");
        }

        private List<String> replayOrder(NotificationDelivery.ReplayRank rank) {
            var store = store(rank);
            var order = new CopyOnWriteArrayList<String>();
            router.addRoute(KVStoreNotification.ValuePut.class, (KVStoreNotification.ValuePut<StructuredKey, Object> p) -> order.add("put:" + ((Key) p.cause().key()).value()));
            router.addRoute(KVStoreNotification.ValueRemove.class, (KVStoreNotification.ValueRemove<StructuredKey, Object> r) -> order.add("remove:" + ((Key) r.cause().key()).value()));
            deserializer.next = Map.of(new Key("r2"), "x");
            store.restoreSnapshot(new byte[0]);
            store.replayNotifications();
            order.clear();

            deserializer.next = Map.of(new Key("p1"), "1");
            store.restoreSnapshot(new byte[0]);
            store.replayNotifications();
            return List.copyOf(order);
        }

        /// (u) replay caller rule: a replay called holding the store monitor, or from the dispatcher thread, goes to the
        /// failure path at once (`replay-refused`); one called after a `synchronized (store)` section completes returns
        /// once its burst is delivered. Every arm under a preemptive bound: mutations, drop the holdsLock check (the
        /// monitor arm deadlocks, red by the bound) / wait inside mutate.
        @Test
        void replayHoldingTheStoreMonitorIsRefused() throws Exception {
            var store = store();
            put(store, A, "one");

            assertTimeoutPreemptively(BOUND, () -> {
                synchronized (store) {
                    store.replayNotifications();
                }
            });
            assertThat(sink.awaitStopped()).isTrue();
            assertThat(sink.causes.getFirst().getMessage()).contains("store monitor");
        }

        @Test
        void replayFromTheDispatcherThreadIsRefused() throws Exception {
            var store = store();
            router.addRoute(KVStoreNotification.ValuePut.class, _ -> store.replayNotifications());

            assertTimeoutPreemptively(BOUND, () -> put(store, A, "one"));
            assertThat(sink.awaitStopped()).isTrue();
            assertThat(sink.causes.getFirst().getMessage()).contains("dispatcher thread");
        }

        /// (u) mutationLock arm: a replay from a thread holding the mutation guard but not the monitor (the window in
        /// which `mutate` appends a batch) is refused. No public path holds the guard alone, so the test takes the
        /// store's private guard by reflection. Mutation: drop the guard check.
        @Test
        void replayHoldingTheMutationGuardIsRefused() throws Exception {
            var store = store();
            put(store, A, "one");
            var guardField = KVStore.class.getDeclaredField("mutationLock");
            guardField.setAccessible(true);
            var guard = guardField.get(store);

            assertTimeoutPreemptively(BOUND, () -> {
                synchronized (guard) {
                    store.replayNotifications();
                }
            });
            assertThat(sink.awaitStopped()).isTrue();
            assertThat(sink.causes.getFirst().getMessage()).contains("mutation guard");
        }

        @Test
        void replayAfterASynchronizedSectionCompletesReturnsOnceDelivered() {
            var store = store();
            var burst = new CopyOnWriteArrayList<Object>();
            router.addRoute(KVStoreNotification.ValuePut.class, (KVStoreNotification.ValuePut<StructuredKey, Object> p) -> burst.add(p.cause().key()));
            deserializer.next = Map.of(A, "one");

            assertTimeoutPreemptively(BOUND, () -> {
                synchronized (store) {
                    store.restoreSnapshot(new byte[0]);
                }
                store.replayNotifications();
            });
            assertThat(burst).containsExactly(A);
            assertThat(sink.reasons).isEmpty();
        }
    }

    @Nested
    class Lifecycle {
        /// (j) awaitDelivered after reset() and a stop/start does not resolve early: mutation, key on committedRevision.
        @Test
        void awaitDeliveredAfterResetDoesNotResolveEarly() {
            var store = store();
            router.addRoute(KVStoreNotification.ValuePut.class, _ -> {});
            router.addRoute(KVStoreNotification.ValueRemove.class, _ -> {});
            store.processCommitted(store.createBatch(List.of(new KVCommand.Put<>(A, "one"))), 40);
            store.awaitDelivered(store.enqueuedSeq()).await();
            hold.holdFrom(store.enqueuedSeq() + 1);

            store.reset();
            store.processCommitted(store.createBatch(List.of(new KVCommand.Put<>(B, "two"))), 1);
            var afterRestart = store.awaitDelivered(store.enqueuedSeq());

            assertThat(hold.awaitHeld(10)).isTrue();
            assertThat(resolvedWithin(afterRestart, 300)).isFalse();
            hold.release();
            assertThat(resolvedWithin(afterRestart, 10_000)).isTrue();
        }

        /// (w) engine stop/start on one store: apply, reset(), apply again: the post-restart batch is delivered and its
        /// promise succeeds; a promise parked before the stop succeeds after its delivery: mutations, close the store on
        /// reset() (post-restart arm red) / fail parked promises on stop (parked arm red).
        @Test
        void storeStaysOpenAcrossAnEngineStopAndStart() {
            var store = store();
            var seen = new CopyOnWriteArrayList<Object>();
            router.addRoute(KVStoreNotification.ValuePut.class, (KVStoreNotification.ValuePut<StructuredKey, Object> p) -> seen.add(p.cause().value()));
            router.addRoute(KVStoreNotification.ValueRemove.class, _ -> {});
            hold.holdFrom(1);

            put(store, A, "before");
            var parked = Promise.<Unit>promise();
            store.completeAfterDelivery(parked, Unit.unit());
            assertThat(hold.awaitHeld(10)).isTrue();
            store.reset();
            put(store, B, "after");
            var afterRestart = Promise.<Unit>promise();
            store.completeAfterDelivery(afterRestart, Unit.unit());
            hold.release();

            assertThat(parked.await(TimeSpan.timeSpan(10).seconds()).isSuccess()).as("parked before stop").isTrue();
            assertThat(afterRestart.await(TimeSpan.timeSpan(10).seconds()).isSuccess()).as("post-restart").isTrue();
            assertThat(seen).containsExactly("before", "after");
        }

        /// (k) closed state: a pending await and a parked promise fail with outcome-unknown; ones registered after the
        /// close fail at once: mutations, leave waiters / fail with a definite cause.
        @Test
        void closeFailsEveryWaiterWithOutcomeUnknown_andLaterOnesAtOnce() {
            var store = store();
            router.addRoute(KVStoreNotification.ValuePut.class, _ -> {});
            hold.holdFrom(1);

            put(store, A, "one");
            assertThat(hold.awaitHeld(10)).isTrue();
            var pending = store.awaitDelivered(store.enqueuedSeq());
            var parked = Promise.<Unit>promise();
            store.completeAfterDelivery(parked, Unit.unit());

            store.close();
            var late = store.awaitDelivered(store.enqueuedSeq() + 5);
            var lateParked = Promise.<Unit>promise();
            store.completeAfterDelivery(lateParked, Unit.unit());

            for (var promise : List.of(pending, parked, late, lateParked)) {
                var result = promise.await(TimeSpan.timeSpan(2).seconds());
                assertThat(result.isFailure()).isTrue();
                result.onFailure(cause -> assertThat(cause).isInstanceOf(KVDeliveryError.DeliveryOutcomeUnknown.class));
            }
        }

        /// §3.2 step 1 / Lifecycle: `awaitDelivered` on an already closed store completes at once, never parks, with the
        /// outcome-unknown failure, for a seq not yet delivered and for one already delivered: mutations, closed check
        /// removed (the future seq parks, unresolved) and closed check after the delivered check (the delivered seq
        /// succeeds). The node-stop test cannot pin this, a real stop never awaits on a closed store.
        @Test
        void awaitDeliveredOnAClosedStore_failsAtOnce_neverParks() {
            var store = store();
            router.addRoute(KVStoreNotification.ValuePut.class, _ -> {});
            put(store, A, "one");
            store.awaitDelivered(store.enqueuedSeq()).await();
            store.close();

            var alreadyDelivered = store.awaitDelivered(store.enqueuedSeq());
            var notYetDelivered = store.awaitDelivered(store.enqueuedSeq() + 5);

            for (var promise : List.of(alreadyDelivered, notYetDelivered)) {
                assertThat(promise.isResolved()).isTrue();
                promise.await()
                       .onSuccess(_ -> fail("a closed store must fail the wait"))
                       .onFailure(cause -> assertThat(cause).isInstanceOf(KVDeliveryError.DeliveryOutcomeUnknown.class));
            }
        }

        /// (x) teardown: after close() no dispatcher thread of this store remains, and the pending wait failed: mutation,
        /// no join (thread arm red).
        @Test
        void closeJoinsTheDispatcherThread() {
            var store = store();
            router.addRoute(KVStoreNotification.ValuePut.class, _ -> {});
            put(store, A, "one");
            store.awaitDelivered(store.enqueuedSeq()).await();

            store.close();

            assertThat(Thread.getAllStackTraces().keySet())
                .extracting(Thread::getName)
                .doesNotContain(store.dispatcherThreadName());
        }

        /// A mutate on a closed store still applies, allocates no sequence and appends nothing (§3.2 step 1).
        @Test
        void closedStoreStillAppliesButAppendsNothing() {
            var store = store();
            store.close();
            var seq = store.enqueuedSeq();

            put(store, A, "one");

            assertThat(store.get(A).or("absent")).isEqualTo("one");
            assertThat(store.enqueuedSeq()).isEqualTo(seq);
        }

        /// A wait parked on an INLINE store after close fails with outcome-unknown: the inline executor would otherwise
        /// complete it as a success. Mutation: completeNow ignores the closed state (succeeds, red).
        @Test
        void closedInlineStoreFailsACompleteAfterDeliveryWithOutcomeUnknown() {
            var store = new KVStore<StructuredKey, Object>(router, new Serializer() {
                @Override public <T> void write(ByteBuf buffer, T value) {}
            }, deserializer, NotificationDelivery.inline());
            store.close();
            var promise = Promise.<Unit>promise();

            store.completeAfterDelivery(promise, Unit.unit());

            promise.await(TimeSpan.timeSpan(5).seconds())
                   .onSuccess(_ -> fail("a closed store must fail the wait"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(KVDeliveryError.DeliveryOutcomeUnknown.class));
        }

        /// On a closed dispatched store the failure carries the "closed" reason, not the executor-rejection one (the shut
        /// down executor also yields outcome-unknown, which masks the mutation in a type-only assertion).
        /// Mutation: completeNow ignores the closed state (reason becomes the rejection, red).
        @Test
        void closedDispatchedStoreFailsACompleteAfterDeliveryWithReasonClosed() {
            var store = store();
            router.addRoute(KVStoreNotification.ValuePut.class, _ -> {});
            store.close();
            var promise = Promise.<Unit>promise();

            store.completeAfterDelivery(promise, Unit.unit());

            promise.await(TimeSpan.timeSpan(5).seconds())
                   .onSuccess(_ -> fail("a closed store must fail the wait"))
                   .onFailure(cause -> assertThat(((KVDeliveryError.DeliveryOutcomeUnknown) cause).reason()).isEqualTo("closed"));
        }

        /// An append that lands after close() starts no delivery thread and no watchdog (close() takes no mutation guard,
        /// so a writer past its allocate can race it). Mutation: ensureStarted runs after stop (a watchdog leaks, red).
        @Test
        void appendAfterCloseStartsNoThread() throws Exception {
            var store = store();
            var dispatcher = dispatcherOf(store);
            long seq;
            synchronized (store) {
                seq = dispatcher.allocate();
            }
            var batch = dispatcher.batch(seq, 0, List.of(), false).fold(() -> null, b -> b);
            store.close();

            dispatcher.append(batch);
            sleep(300);

            var alive = Thread.getAllStackTraces().keySet().stream()
                              .filter(t -> t.getName().startsWith(store.dispatcherThreadName()) && t.isAlive())
                              .count();
            assertThat(alive).as("no delivery thread and no watchdog thread started after close").isZero();
        }

        /// close() drops the undelivered queue: with batch 1 held and two more enqueued, the queue is empty after close.
        /// `droppedAtClose` is computed before the clear, so only the queue itself can see this.
        /// Mutation: close keeps the queue (red).
        @Test
        void closeDropsTheUndeliveredQueue() throws Exception {
            var store = store();
            router.addRoute(KVStoreNotification.ValuePut.class, _ -> {});
            hold.holdFrom(1);
            put(store, A, "one");
            assertThat(hold.awaitHeld(10)).isTrue();
            put(store, B, "two");
            put(store, K, "three");

            store.close();

            assertThat((java.util.Collection<?>) privateField(dispatcherOf(store), "queue")).isEmpty();
        }

        /// close() stops the watchdog thread: control that `<name>-watchdog` runs, then it is gone within 5 s of close.
        /// (`closeJoinsTheDispatcherThread` compares the delivery thread's name only.) Mutation: close leaves the watchdog (red).
        @Test
        void closeStopsTheWatchdogThread() {
            var store = store();
            router.addRoute(KVStoreNotification.ValuePut.class, _ -> {});
            put(store, A, "one");
            delivered(store);
            var watchdog = store.dispatcherThreadName() + "-watchdog";
            assertThat(threadAlive(watchdog)).as("control: the watchdog runs while the store is open").isTrue();

            store.close();

            var deadline = System.nanoTime() + BOUND.toNanos() / 2;
            while (threadAlive(watchdog) && System.nanoTime() < deadline) {
                sleep(20);
            }
            assertThat(threadAlive(watchdog)).as("watchdog thread gone after close").isFalse();
        }

        private static boolean threadAlive(String name) {
            return Thread.getAllStackTraces().keySet().stream()
                         .anyMatch(thread -> thread.getName().equals(name) && thread.isAlive());
        }
    }

    @Nested
    class Failures {
        /// R4.4: the node stop runs once although the core fatal-escape hook and the store may both report the same
        /// failure. Mutation: once() returns the sink unwrapped (red).
        @Test
        void onceSinkStopsTheNodeOnlyOnce() {
            var once = DispatcherFailureSink.once(sink);

            once.stopNode("kv-dispatcher-failed", new StackOverflowError("first"));
            once.stopNode("fatal-escape", new StackOverflowError("second"));

            assertThat(sink.reasons).containsExactly("kv-dispatcher-failed");
        }

        /// (h) a handler Error stops the node through the sink, and the store closes: mutation, swallow.
        @Test
        void handlerErrorStopsTheNodeThroughTheSink() throws Exception {
            var store = store();
            router.addRoute(KVStoreNotification.ValuePut.class, _ -> { throw new AssertionError("handler error"); });

            put(store, A, "one");

            assertThat(sink.awaitStopped()).isTrue();
            assertThat(sink.reasons).containsExactly("kv-dispatcher-failed");
            assertThat(store.deliveryMetrics().closed()).isTrue();
            assertThat(eventsOf("kv-dispatcher-failed")).as("the failure is an operator event, its subject the store (v-2648 F5)")
                                      .containsExactly("kv-dispatcher-failed|" + store.dispatcherThreadName().substring("kv-dispatcher-".length()));
        }

        /// A RuntimeException in one handler is isolated (P6): the next notification is still delivered.
        @Test
        void handlerRuntimeExceptionIsIsolated() throws Exception {
            var store = store();
            var seen = new CountDownLatch(1);
            router.addRoute(KVStoreNotification.ValuePut.class, (KVStoreNotification.ValuePut<StructuredKey, Object> p) -> {
                if (p.cause().key().equals(A)) {
                    throw new IllegalStateException("isolated");
                }
                seen.countDown();
            });

            put(store, A, "one");
            put(store, B, "two");

            assertThat(seen.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(sink.reasons).isEmpty();
        }

        /// An exception escaping the router itself (not a handler: the stock router already swallows those) is isolated per
        /// notification: delivery continues and the node is not stopped. Mutation: the loop rethrows it (red).
        @Test
        void routerThrowingIsIsolatedPerNotification() throws Exception {
            var calls = new AtomicInteger();
            var seen = new CountDownLatch(1);
            MessageRouter throwing = new MessageRouter.ImmutableRouter<Message>() {
                @Override
                public Map<Class<Message>, List<java.util.function.Consumer<Message>>> routingTable() {
                    return Map.of();
                }

                @Override
                public <R extends Message> void route(R message) {
                    if (calls.incrementAndGet() == 1) {
                        throw new IllegalStateException("router blew up");
                    }
                    seen.countDown();
                }
            };
            var store = store(throwing, NotificationDelivery.ReplayRank.flat(), NotificationDelivery.DEFAULT_SLOW_HANDLER_NANOS);

            put(store, A, "one");
            put(store, B, "two");

            assertThat(seen.await(10, TimeUnit.SECONDS)).as("the second notification is delivered").isTrue();
            assertThat(sink.reasons).isEmpty();
        }

        /// A failure reported after a normal close() is ignored: the node is not stopped and no failure event is raised.
        /// Mutations: fail() not guarded by the close-once / close-once not idempotent (the sink is called, red).
        @Test
        void failureAfterCloseDoesNotStopTheNode() throws Exception {
            var store = store();
            router.addRoute(KVStoreNotification.ValuePut.class, _ -> {});
            put(store, A, "one");
            delivered(store);
            store.close();

            dispatcherOf(store).fail("late", new IllegalStateException("late failure"));

            assertThat(sink.reasons).isEmpty();
            assertThat(eventsOf("kv-dispatcher-failed")).isEmpty();
        }

        /// Two failures stop the node once: the sink and the failure event are each hit once.
        /// Mutations: close-once not idempotent / fail() not guarded (twice, red).
        @Test
        void twoFailuresStopTheNodeOnce() throws Exception {
            var store = store();
            router.addRoute(KVStoreNotification.ValuePut.class, _ -> {});
            put(store, A, "one");
            delivered(store);

            dispatcherOf(store).fail("first", new IllegalStateException("one"));
            dispatcherOf(store).fail("second", new IllegalStateException("two"));

            assertThat(sink.reasons).hasSize(1);
            assertThat(eventsOf("kv-dispatcher-failed")).hasSize(1);
        }

        /// (l) an Error raised while applying (between the storage change and the append) stops the node and reaches
        /// the writer: mutation, return normally.
        @Test
        void errorDuringApplyClosesTheStoreAndStopsTheNode() throws Exception {
            record Exploding(int n) implements GrowOnlyMergeable<Exploding> {
                @Override public Exploding mergeInto(Exploding committed) { throw new AssertionError("merge error"); }
            }
            var store = store();
            put(store, A, new Exploding(1));

            var escaped = new AtomicReference<Throwable>();
            try {
                put(store, A, new Exploding(2));
            } catch (Throwable t) {
                escaped.set(t);
            }

            assertThat(escaped.get()).isInstanceOf(AssertionError.class);
            assertThat(sink.awaitStopped()).isTrue();
            assertThat(store.deliveryMetrics().closed()).isTrue();
        }

        /// (p) a RuntimeException in the loop body outside any handler (here: the replay diff calling a user equals)
        /// closes the store and stops the node: mutation, catch only around handler calls.
        @Test
        void runtimeExceptionInTheLoopBodyClosesTheStore() throws Exception {
            record BadEquals(int n) {
                @Override public boolean equals(Object other) { throw new IllegalStateException("equals"); }
                @Override public int hashCode() { return n; }
            }
            var store = store();
            router.addRoute(KVStoreNotification.ValuePut.class, _ -> {});
            deserializer.next = Map.of(A, new BadEquals(1));
            store.restoreSnapshot(new byte[0]);

            var replayed = new AtomicReference<Throwable>();
            assertTimeoutPreemptively(BOUND, () -> {
                try {
                    store.replayNotifications();
                } catch (Throwable t) {
                    replayed.set(t);
                }
            });

            assertThat(sink.awaitStopped()).isTrue();
            assertThat(store.deliveryMetrics().closed()).isTrue();
            assertThat(replayed.get()).as("the waiting replay caller fails through the closed state").isNotNull();
        }
    }

    @Nested
    class Metrics {
        /// (n) queue.bytes rises under a held dispatcher; lag.oldestAgeMs grows with the hold and drops to 0 on release:
        /// mutations, age taken from the newest batch instead of the oldest.
        @Test
        void lagAndBytesFollowTheHold() {
            var store = store();
            router.addRoute(KVStoreNotification.ValuePut.class, _ -> {});
            hold.holdFrom(1);

            put(store, A, "one");
            assertThat(hold.awaitHeld(10)).isTrue();
            sleep(200);
            put(store, B, "two");
            var held = store.deliveryMetrics();

            assertThat(held.queueBytesEstimate()).isPositive();
            assertThat(held.lagBatches()).isEqualTo(2);
            assertThat(held.lagOldestAgeMillis()).isGreaterThanOrEqualTo(200);
            hold.release();
            store.awaitDelivered(store.enqueuedSeq()).await();
            var drained = store.deliveryMetrics();
            assertThat(drained.lagBatches()).isZero();
            assertThat(drained.lagOldestAgeMillis()).isZero();
            assertThat(drained.queueBytesEstimate()).isZero();
            assertThat(drained.queueHighWaterLive()).isGreaterThanOrEqualTo(1);
        }

        /// (n) both lags stay 0 across empty slots: the engine's phase hook moves the revision, not the seq.
        /// (m) the watchdog fires on a held handler: while a handler is still running past the threshold, the slow-handler
        /// event is raised, and the crossing is counted when the handler ends. Mutation: disable the watchdog (red).
        /// v-2648 F5: the handler's return raises the recovery, same subject, exactly once. Mutation: drop the recovery (red).
        @Test
        void watchdogFiresOnAHeldHandler() throws Exception {
            var events = new CopyOnWriteArrayList<String>();
            var delivery = new NotificationDelivery.Dispatched("w" + System.nanoTime(),
                                                               NotificationDelivery.ReplayRank.flat(),
                                                               sink,
                                                               DeliveryHook.none(),
                                                               (code, subject, _) -> recordEvent(events, code.code() + "|" + subject),
                                                               TimeUnit.MILLISECONDS.toNanos(20));
            var store = new KVStore<StructuredKey, Object>(router, new Serializer() {
                @Override public <T> void write(ByteBuf buffer, T value) {}
            }, deserializer, delivery);
            opened.add(store);
            var handlerHeld = new CountDownLatch(1);
            router.addRoute(KVStoreNotification.ValuePut.class, _ -> awaitQuietly(handlerHeld));

            put(store, A, "one");

            assertThat(eventuallyContains(events, "kv-dispatch-slow-handler|ValuePut Key")).as("raised while the handler runs").isTrue();
            assertThat(events).as("no recovery while the handler is held").containsExactly("kv-dispatch-slow-handler|ValuePut Key");
            assertThat(store.hasPendingNotifications()).as("the handler is still held").isTrue();
            handlerHeld.countDown();
            store.awaitDelivered(store.enqueuedSeq()).await();
            assertThat(store.deliveryMetrics().slowHandlers()).isEqualTo(1);
            assertThat(events).containsExactly("kv-dispatch-slow-handler|ValuePut Key", "kv-dispatch-slow-handler-returned|ValuePut Key");
        }

        /// A second slow handler of the same tag inside the throttle interval raises no second event: two 400 ms handlers
        /// of one tag over a 5 ms threshold give exactly one `kv-dispatch-slow-handler`. Mutation: unthrottled report (two, red).
        @Test
        void slowHandlerReportIsThrottledPerTag() throws Exception {
            var store = store(router, NotificationDelivery.ReplayRank.flat(), TimeUnit.MILLISECONDS.toNanos(5));
            var done = new CountDownLatch(2);
            router.addRoute(KVStoreNotification.ValuePut.class, _ -> {
                sleep(400);
                done.countDown();
            });

            put(store, A, "one");
            put(store, B, "two");

            assertThat(done.await(15, TimeUnit.SECONDS)).isTrue();
            delivered(store);
            sleep(200);
            assertThat(eventsOf("kv-dispatch-slow-handler")).hasSize(1);
        }

        private static Unit recordEvent(List<String> events, String event) {
            events.add(event);

            return Unit.unit();
        }

        private static boolean eventuallyContains(List<String> events, String event) {
            var deadline = System.nanoTime() + BOUND.toNanos();

            while (!events.contains(event) && System.nanoTime() < deadline) {
                sleep(10);
            }

            return events.contains(event);
        }

        private static void awaitQuietly(CountDownLatch latch) {
            try {
                latch.await(BOUND.toSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Test
        void emptySlotsMoveNoLag() {
            var store = store();
            store.onPhaseCompleted(100);
            store.onPhaseCompleted(200);

            assertThat(store.deliveryMetrics().lagBatches()).isZero();
            assertThat(store.deliveryMetrics().lagOldestAgeMillis()).isZero();
        }

        /// hasPendingNotifications() reads enqueuedSeq != deliveredSeq under the monitor (§3.7).
        @Test
        void pendingFollowsTheHold() {
            var store = store();
            router.addRoute(KVStoreNotification.ValuePut.class, _ -> {});
            hold.holdFrom(1);

            put(store, A, "one");
            assertThat(hold.awaitHeld(10)).isTrue();
            assertThat(store.hasPendingNotifications()).isTrue();
            hold.release();
            store.awaitDelivered(store.enqueuedSeq()).await();
            assertThat(store.hasPendingNotifications()).isFalse();
        }
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
