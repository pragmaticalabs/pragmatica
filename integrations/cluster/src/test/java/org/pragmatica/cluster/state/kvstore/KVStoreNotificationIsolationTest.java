package org.pragmatica.cluster.state.kvstore;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class KVStoreNotificationIsolationTest {
    record Key(String value) implements StructuredKey {}
    private static final Key A = new Key("a");
    private static final Key B = new Key("b");
    private static final Key C = new Key("c");

    private KVStore<StructuredKey, Object> store(MessageRouter router) {
        return new KVStore<>(router, new Serializer() {
            @Override public <T> void write(ByteBuf buffer, T value) {}
        }, new Deserializer() {
            @Override public <T> T read(ByteBuf buffer) { return null; }
        });
    }

    @Test
    void blockedSubscriberDoesNotBlockCoherentReaders() throws Exception {
        var router = MessageRouter.mutable();
        var store = store(router);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        router.addRoute(KVStoreNotification.ValuePut.class, _ -> {
            entered.countDown();
            Result.lift(Causes::fromThrowable, () -> release.await(5, TimeUnit.SECONDS)).unwrap();
        });
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = executor.submit(() -> store.process(store.createBatch(List.of(new KVCommand.Put<>(A, "value")))));
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                var reader = executor.submit(store::snapshot);
                assertThat(reader.get(1, TimeUnit.SECONDS)).containsEntry(A, "value");
            } finally {
                release.countDown();
            }
            writer.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void transactionCallbacksSeeAllKeysAndReentrantWritesStayBehindItsNotifications() {
        var router = MessageRouter.mutable();
        var store = store(router);
        var leader = new LeaderValue(new NodeId("core"), 1);
        store.process(store.createBatch(List.of(new KVCommand.Put<>(LeaderKey.INSTANCE, leader))));
        var observed = new ArrayList<StructuredKey>();
        var firstView = new AtomicReference<Map<StructuredKey, Object>>();
        router.addRoute(KVStoreNotification.ValuePut.class, (KVStoreNotification.ValuePut<StructuredKey, Object> put) -> {
            observed.add(put.cause().key());
            if (put.cause().key().equals(A)) {
                firstView.set(store.snapshot());
                store.process(store.createBatch(List.of(new KVCommand.Put<>(C, "nested"))));
            }
        });
        var transaction = new KVCommand.LeaderTransaction<StructuredKey, Object>(A, "atomic", leader, List.of(), List.of(
            new KVCommand.Mutation<>(A, Option.none(), Option.some("one")),
            new KVCommand.Mutation<>(B, Option.none(), Option.some("two"))));
        store.process(store.createBatch(List.of(transaction)));
        assertThat(firstView.get()).containsEntry(A, "one").containsEntry(B, "two");
        assertThat(observed).containsExactly(A, B, C);
    }

    @Test
    void reentrantReplayDoesNotDuplicateCapturedViewOrMarkNestedLiveWriteAsReplay() {
        var router = MessageRouter.mutable();
        var store = store(router);
        store.process(store.createBatch(List.of(new KVCommand.Put<>(A, "one"))));
        var observed = new ArrayList<String>();
        router.addRoute(KVStoreNotification.ValuePut.class, (KVStoreNotification.ValuePut<StructuredKey, Object> put) -> {
            observed.add(((Key) put.cause().key()).value() + ":" + store.isReplaying());
            if (put.cause().key().equals(A)) {
                store.replayNotifications();
                store.process(store.createBatch(List.of(new KVCommand.Put<>(B, "nested"))));
            }
        });
        store.replayNotifications();
        assertThat(observed).containsExactly("a:true", "b:false");
        assertThat(store.isReplaying()).isFalse();
    }

    @Test
    void resetAndForEachInvokeCallbacksOutsideTheStoreMonitor() {
        var router = MessageRouter.mutable();
        var store = store(router);
        store.process(store.createBatch(List.of(new KVCommand.Put<>(A, "one"))));
        var lockHeld = new ArrayList<Boolean>();
        store.forEach(Key.class, String.class, (_, _) -> lockHeld.add(Thread.holdsLock(store)));
        router.addRoute(KVStoreNotification.ValueRemove.class, _ -> lockHeld.add(Thread.holdsLock(store)));
        store.reset();
        assertThat(lockHeld).containsExactly(false, false);
        assertThat(store.snapshot()).isEmpty();
    }
}
