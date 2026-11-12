package org.pragmatica.cluster.state.kvstore;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/// #2981 §2.2: `peekTyped`, the lock-free record read of the node lease checks. It must not wait behind the store
/// monitor (the apply thread holds it for a whole batch), it must fail closed (absent or foreign-typed = none), and it
/// must already see a record when that record's own notification is delivered (the view leads delivery, §2.1).
class KVStorePeekTypedTest {
    private record PlainKey(String id) implements StructuredKey {}

    private record PlainValue(String tag) {}

    private record OtherValue(String tag) {}

    private static final StructuredKey KEY = new PlainKey("k");

    private KVStore<StructuredKey, Object> store;
    private MessageRouter.MutableRouter router;

    @BeforeEach
    void setUp() {
        router = MessageRouter.mutable();
        store = new KVStore<>(router, stubSerializer(), stubDeserializer());
    }

    @Test
    void peekTyped_whileAnotherThreadHoldsTheStoreMonitor_completesWithoutWaiting() throws Exception {
        store.process(store.createBatch(List.of(new Put<>(KEY, new PlainValue("v")))));
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holder = Thread.ofPlatform()
                           .daemon()
                           .start(() -> holdMonitor(held, release));

        try {
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();

            var read = CompletableFuture.supplyAsync(() -> store.peekTyped(KEY, PlainValue.class))
                                        .get(2, TimeUnit.SECONDS);

            assertThat(read.or((PlainValue) null)).isEqualTo(new PlainValue("v"));
        } finally {
            release.countDown();
            holder.join(5_000);
        }
    }

    @Test
    void peekTyped_absentKey_isNone() {
        assertThat(store.peekTyped(KEY, PlainValue.class).isEmpty()).isTrue();
    }

    @Test
    void peekTyped_foreignTypedValue_isNone() {
        store.process(store.createBatch(List.of(new Put<>(KEY, new OtherValue("v")))));

        assertThat(store.peekTyped(KEY, PlainValue.class).isEmpty()).isTrue();
        assertThat(store.peekTyped(KEY, OtherValue.class).isPresent()).isTrue();
    }

    @Test
    void peekTyped_insideTheDeliveryOfItsOwnPut_alreadySeesTheRecord() {
        List<Option<PlainValue>> seen = new ArrayList<>();
        router.addRoute(ValuePut.class, (ValuePut<StructuredKey, Object> put) -> seen.add(store.peekTyped(KEY, PlainValue.class)));

        store.process(store.createBatch(List.of(new Put<>(KEY, new PlainValue("v")))));

        assertThat(seen).hasSize(1);
        assertThat(seen.getFirst().or((PlainValue) null)).isEqualTo(new PlainValue("v"));
    }

    private void holdMonitor(CountDownLatch held, CountDownLatch release) {
        synchronized (store) {
            held.countDown();
            awaitQuietly(release);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
