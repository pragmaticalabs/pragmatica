// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/// D1 exits at the store level (KVStore dispatcher design §5). Every probe that could pass by race holds the dispatcher
/// with [HeldDelivery] and asserts the hold was reached before it asserts anything else.
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

    KVStore<StructuredKey, Object> store() {
        return store(NotificationDelivery.ReplayRank.flat());
    }

    KVStore<StructuredKey, Object> store(NotificationDelivery.ReplayRank rank) {
        var delivery = new NotificationDelivery.Dispatched("t" + System.nanoTime(), rank, sink, hold,
                                                           NotificationDelivery.OperatorEvents.none(),
                                                           NotificationDelivery.DEFAULT_SLOW_HANDLER_NANOS);
        var store = new KVStore<StructuredKey, Object>(router, new Serializer() {
            @Override public <T> void write(ByteBuf buffer, T value) {}
        }, deserializer, delivery);
        opened.add(store);
        return store;
    }

    @AfterEach
    void closeStores() {
        hold.release();
        opened.forEach(KVStore::close);
    }

    static List<Object> put(KVStore<StructuredKey, Object> store, Key key, Object value) {
        return store.process(store.createBatch(List.of(new KVCommand.Put<>(key, value))));
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

        /// (d) FIFO across a reentrant write: a write made by batch 1's handler is delivered after batch 2, which was
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
        @Test
        void readOfAKeyALaterBatchWroteCountsOneReadAhead() throws Exception {
            assertThat(readAheadsWhenBatchTwoWrites(K)).isEqualTo(1);
        }

        @Test
        void control_readOfAKeyNoLaterBatchWroteCountsNothing() throws Exception {
            assertThat(readAheadsWhenBatchTwoWrites(L)).isZero();
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
        /// (e) replayNotifications() returns only after its burst is delivered: mutation, return at enqueue.
        @Test
        void replayReturnsOnlyAfterItsBurstIsDelivered() {
            var store = store();
            var burst = new CopyOnWriteArrayList<Object>();
            router.addRoute(KVStoreNotification.ValuePut.class, (KVStoreNotification.ValuePut<StructuredKey, Object> p) -> burst.add(p.cause().key()));
            deserializer.next = Map.of(A, "one", B, "two");
            store.restoreSnapshot(new byte[0]);

            assertTimeoutPreemptively(BOUND, store::replayNotifications);
            assertThat(burst).containsExactlyInAnyOrder(A, B);
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
    }

    @Nested
    class Failures {
        /// (h) a handler Error stops the node through the sink, and the store closes: mutation, swallow.
        @Test
        void handlerErrorStopsTheNodeThroughTheSink() throws Exception {
            var store = store();
            router.addRoute(KVStoreNotification.ValuePut.class, _ -> { throw new AssertionError("handler error"); });

            put(store, A, "one");

            assertThat(sink.awaitStopped()).isTrue();
            assertThat(sink.reasons).containsExactly("kv-dispatcher-failed");
            assertThat(store.deliveryMetrics().closed()).isTrue();
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
