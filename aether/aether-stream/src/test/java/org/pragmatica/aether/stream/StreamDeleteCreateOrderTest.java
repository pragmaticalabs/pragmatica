// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #2627: a stream's `StreamConfigKey` submissions (create Put, publish-path auto-create Put, destroy Remove) reach the
/// engine in the order the callers issued them, and a create the life fence refused is never reported as success.
///
/// The fixture is a REAL [KVStore] (so the life fence is the production one) behind a cluster node whose commits are held
/// by the test and released in a forced order: [ForcedOrderNode#commitNewestFirst] stands in for Rabia committing pending
/// batches in content-hash order, which is unrelated to submission order. No sleeps: the test thread observes the
/// create either having submitted its Put (unfixed code) or being parked behind the destroy (fixed code), then commits.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class StreamDeleteCreateOrderTest {
    private static final String STREAM = "orders";
    private static final StreamConfigKey KEY = StreamConfigKey.streamConfigKey(STREAM);

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private MessageRouter.MutableRouter router;
    private KVStore<AetherKey, AetherValue> store;
    private ForcedOrderNode node;
    private StreamPartitionManager manager;

    @BeforeEach
    void setUp() {
        router = MessageRouter.mutable();
        store = new KVStore<>(router, NO_SERIALIZER, NO_DESERIALIZER);
        node = new ForcedOrderNode(store);
        manager = streamPartitionManager(Long.MAX_VALUE, node);
    }

    @AfterEach
    void tearDown() {
        manager.close();
        executor.shutdownNow();
    }

    /// Delete then create of one name with the commit order forced reversed. Unfixed: the create's Put is submitted next to
    /// the unawaited Remove, commits first, is refused by the life fence, and the create reports SUCCESS; then the Remove
    /// deletes the stream the caller was told exists.
    @Test
    void createStream_afterDestroy_commitsTheNewLife_underForcedReversedCommitOrder() {
        wireNotifications();
        assertThat(manager.createStream(config()).isSuccess()).as("fixture: first life created").isTrue();
        var firstLife = committedIncarnation();

        node.hold();
        assertThat(manager.destroyStream(STREAM).isSuccess()).as("fixture: destroy accepted").isTrue();
        var create = startOnOwnThread(() -> manager.createStream(config()));

        create.awaitSubmittedOrParked(node);
        var outcome = driveUntilDone(create.future());

        assertThat(outcome.isSuccess()).as("the recreate commits once the destroy has: %s", outcome).isTrue();
        assertThat(committedIncarnation()).as("the committed config is the NEW life, not absent and not the old one")
                                          .isNotEqualTo(firstLife)
                                          .isNotEqualTo(-1L);
        assertThat(manager.streamInfo(STREAM).isPresent()).as("the stream reported as created exists on this node").isTrue();
        assertThat(node.committedKinds()).as("the Remove committed before the new life's Put").containsExactly("Put", "Remove", "Put");
    }

    /// Publish-path auto-create whose Put is still in flight when the stream is destroyed. Unfixed: the Remove is submitted
    /// at once, commits first (a no-op), and the Put then re-hydrates a ghost stream on every node.
    @Test
    void destroyStream_duringAnInFlightAutoCreate_isOrderedAfterIt_noGhostStream() {
        wireNotifications();
        node.hold();
        var autoCreate = startOnOwnThread(() -> manager.ensureStreamMaterialized(config()));
        node.awaitPending(1);

        assertThat(manager.destroyStream(STREAM).isSuccess()).as("fixture: the materialized stream is destroyed").isTrue();
        assertThat(manager.commitLaneCount()).as("the in-flight Put and the queued Remove share the name's one lane").isEqualTo(1);
        driveUntilDone(autoCreate.future(), () -> node.committedKinds().size() == 2);

        assertThat(store.get(KEY).isPresent()).as("the committed config is gone: the Remove committed last").isFalse();
        assertThat(manager.streamInfo(STREAM).isPresent()).as("no ghost stream is re-hydrated").isFalse();
        assertThat(node.committedKinds()).containsExactly("Put", "Remove");
    }

    /// A Put the life fence refused is not a successful create. The committed life is installed before this node's
    /// notifications are wired, so nothing is applied here: the refusal is visible only in the applier's answer.
    @Test
    void createStream_refusedByTheLifeFence_reportsFailure_notSuccess() {
        store.process(store.createBatch(List.of(new KVCommand.Put<AetherKey, AetherValue>(KEY, StreamConfigValue.streamConfigValue(config().withIncarnation(5L))))));
        wireNotifications();

        var outcome = manager.createStream(config());

        assertThat(outcome.isFailure()).as("a refused create must not report success: %s", outcome).isTrue();
        outcome.onSuccess(_ -> fail("refused create reported success"))
               .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.STREAM_ALREADY_EXISTS));
        assertThat(committedIncarnation()).as("the committed life stands").isEqualTo(5L);
    }

    /// The fence lets a life of incarnation zero be replaced (it carries no life), so a create over one is applied.
    @Test
    void createStream_overAZeroIncarnationLife_isApplied() {
        store.process(store.createBatch(List.of(new KVCommand.Put<AetherKey, AetherValue>(KEY, StreamConfigValue.streamConfigValue(config().withIncarnation(0L))))));
        wireNotifications();

        var outcome = manager.createStream(config());

        assertThat(outcome.isSuccess()).as("a zero-incarnation life fences nothing: %s", outcome).isTrue();
        assertThat(committedIncarnation()).as("the new life replaced it").isNotEqualTo(0L);
    }

    /// The applier's answer, read as the fence reads it.
    @Test
    void refusedByFence_readsTheReplacedValue_likeTheApplier() {
        var proposed = config().withIncarnation(7L);

        assertThat(StreamPartitionManager.refusedByFence(proposed, List.of(replaced(5L)))).as("another non-zero life refuses").isTrue();
        assertThat(StreamPartitionManager.refusedByFence(proposed, List.of(replaced(7L)))).as("the same life is a rewrite").isFalse();
        assertThat(StreamPartitionManager.refusedByFence(proposed, List.of(replaced(0L)))).as("a zero life fences nothing").isFalse();
        assertThat(StreamPartitionManager.refusedByFence(proposed, List.of(Option.none()))).as("nothing replaced").isFalse();
        assertThat(StreamPartitionManager.refusedByFence(proposed, List.of())).as("no answer says nothing").isFalse();
        assertThat(StreamPartitionManager.refusedByFence(proposed, List.of("not an option"))).as("a foreign answer says nothing").isFalse();
        assertThat(StreamPartitionManager.refusedByFence(proposed, List.of(Option.some("not a config")))).as("a foreign value says nothing").isFalse();
    }

    /// A failed submission settles its lane: the next create of the name is submitted, and no lane is left behind.
    @Test
    void createStream_afterAFailedCommit_isSubmitted_andLeavesNoLane() {
        wireNotifications();
        node.failNext();

        var failed = manager.createStream(config());
        var retried = manager.createStream(config());

        assertThat(failed.isFailure()).as("fixture: the first commit failed").isTrue();
        boolean republishSentinel = retried.fold(cause -> cause == StreamError.General.STREAM_ALREADY_EXISTS, _ -> false);

        assertThat(republishSentinel).as("the retry was submitted and committed (the republish sentinel), not wedged behind the failed one: %s", retried)
                                     .isTrue();
        assertThat(node.submitted()).as("both creates reached the engine").isEqualTo(2);
        assertThat(manager.commitLaneCount()).as("every lane settled").isZero();
    }

    private static Object replaced(long incarnation) {
        return Option.some(StreamConfigValue.streamConfigValue(config().withIncarnation(incarnation)));
    }

    private long committedIncarnation() {
        return store.get(KEY)
                    .map(value -> ((StreamConfigValue) value).config().incarnation())
                    .or(-1L);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void wireNotifications() {
        Consumer<ValuePut<StreamConfigKey, StreamConfigValue>> put = manager::onStreamConfigPut;
        Consumer<ValueRemove<StreamConfigKey, StreamConfigValue>> remove = manager::onStreamConfigRemove;

        router.addRoute((Class) ValuePut.class, (Consumer) put);
        router.addRoute((Class) ValueRemove.class, (Consumer) remove);
    }

    private Started startOnOwnThread(Supplier<Result<Unit>> action) {
        var thread = new AtomicReference<Thread>();
        var future = CompletableFuture.supplyAsync(() -> {
            thread.set(Thread.currentThread());
            return action.get();
        }, executor);

        return new Started(future, thread);
    }

    private Result<Unit> driveUntilDone(CompletableFuture<Result<Unit>> future) {
        return driveUntilDone(future, () -> true);
    }

    /// Commit whatever is pending, newest first, until the caller has returned AND `settled` holds.
    private Result<Unit> driveUntilDone(CompletableFuture<Result<Unit>> future, BooleanSupplier settled) {
        var deadline = System.nanoTime() + SPIN_LIMIT_NANOS;

        while (!future.isDone() || !settled.getAsBoolean() || node.pendingCount() > 0) {
            node.commitNewestFirst();
            spinOrFail(deadline, "the caller to return with every submission committed");
        }

        return future.join();
    }

    private record Started(CompletableFuture<Result<Unit>> future, AtomicReference<Thread> thread) {
        /// Unfixed code submits the create's Put next to the held Remove (two pending); fixed code parks the create behind
        /// the Remove (one pending, the creating thread waiting). Both are stable states to commit from.
        void awaitSubmittedOrParked(ForcedOrderNode node) {
            var deadline = System.nanoTime() + SPIN_LIMIT_NANOS;

            while (node.pendingCount() < 2 && !parked()) {
                spinOrFail(deadline, "the create to submit its Put or park behind the Remove");
            }
        }

        private boolean parked() {
            var t = thread.get();

            return t != null && (t.getState() == Thread.State.WAITING || t.getState() == Thread.State.TIMED_WAITING);
        }
    }

    /// A spin that never ends is a hang the class timeout cannot interrupt: every wait here is bounded and fails loudly.
    private static final long SPIN_LIMIT_NANOS = TimeUnit.SECONDS.toNanos(20);

    private static void spinOrFail(long deadlineNanos, String waitingFor) {
        if (System.nanoTime() - deadlineNanos > 0) {
            fail("gave up waiting for " + waitingFor);
        }

        Thread.onSpinWait();
    }

    private static StreamConfig config() {
        return StreamConfig.streamConfig(STREAM, 1, RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 600_000), "earliest");
    }

    private static final Serializer NO_SERIALIZER = new Serializer() {
        @Override
        public <T> void write(ByteBuf byteBuf, T object) {}
    };

    private static final Deserializer NO_DESERIALIZER = new Deserializer() {
        @Override
        public <T> T read(ByteBuf byteBuf) {
            throw new UnsupportedOperationException("unused");
        }
    };

    /// Commits into a real [KVStore] (notifications reach the manager synchronously, as in production). While held, batches
    /// stay pending until the test commits them.
    private static final class ForcedOrderNode implements ClusterNode<KVCommand<AetherKey>> {
        private record Pending(List<KVCommand<AetherKey>> commands, Promise<List<Object>> answer) {}

        private final KVStore<AetherKey, AetherValue> store;
        private final List<Pending> pending = new ArrayList<>();
        private final List<String> committed = new ArrayList<>();
        private boolean held;
        private boolean failNext;
        private int submitted;

        ForcedOrderNode(KVStore<AetherKey, AetherValue> store) {
            this.store = store;
        }

        synchronized void hold() {
            held = true;
        }

        synchronized void failNext() {
            failNext = true;
        }

        synchronized int submitted() {
            return submitted;
        }

        synchronized int pendingCount() {
            return pending.size();
        }

        void awaitPending(int count) {
            var deadline = System.nanoTime() + SPIN_LIMIT_NANOS;

            while (pendingCount() < count) {
                spinOrFail(deadline, count + " pending submission(s)");
            }
        }

        synchronized List<String> committedKinds() {
            return List.copyOf(committed);
        }

        void commitNewestFirst() {
            for (var next = takeNewest(); next != null; next = takeNewest()) {
                commit(next);
            }
        }

        private synchronized Pending takeNewest() {
            return pending.isEmpty()
                   ? null
                   : pending.removeLast();
        }

        private void commit(Pending batch) {
            var results = store.<Object> process(store.createBatch(batch.commands()));

            synchronized (this) {
                batch.commands().forEach(command -> committed.add(command instanceof KVCommand.Put<?, ?> ? "Put" : "Remove"));
            }
            batch.answer().succeed(results);
        }

        @SuppressWarnings("unchecked")
        @Override
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            var answer = Promise.<List<Object>> promise();
            var failed = false;
            var hold = false;

            synchronized (this) {
                submitted++;
                failed = failNext;
                failNext = false;
                hold = held;
                if (hold && !failed) {
                    pending.add(new Pending(commands, answer));
                }
            }

            if (failed) {
                answer.fail(Causes.cause("commit rejected"));
            } else if (!hold) {
                commit(new Pending(commands, answer));
            }

            return (Promise<List<R>>) (Promise<?>) answer;
        }

        @Override
        public NodeId self() {
            return NodeId.nodeId("node-order").unwrap();
        }

        @Override
        public TopologyManager topologyManager() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Promise<Unit> start() {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }
    }
}
