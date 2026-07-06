package org.pragmatica.cluster.state.kvstore;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.state.kvstore.KVCommand.Noop;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// #345 item 1e-a — the [KVCommand.Noop] consensus barrier. The applier IGNORES it: no storage
/// mutation, no notification. It exists only to be ordered through the single consensus log so the
/// submitting owner can await its own local apply of it (the linearizable no-op round). These tests
/// prove the applier case is a true no-op — over an empty key and over an existing value.
class KVStoreNoopTest {
    private record PlainKey(String id) implements StructuredKey {}

    private record PlainValue(String tag) {}

    private static final StructuredKey KEY = new PlainKey("k");

    private KVStore<StructuredKey, Object> store;
    private List<Object> notified;

    @BeforeEach
    void setUp() {
        var router = MessageRouter.mutable();
        store = new KVStore<>(router, stubSerializer(), stubDeserializer());
        notified = new ArrayList<>();
        router.addRoute(ValuePut.class, (ValuePut<StructuredKey, Object> put) -> notified.add(put.cause().value()));
    }

    @Test
    void noop_appliesAsNoOp_noStorageEffectNoNotification() {
        var results = store.process(store.createBatch(List.of(new Noop<>(KEY))));

        assertThat(store.get(KEY).isEmpty()).isTrue();
        assertThat(notified).isEmpty();
        assertThat(results).hasSize(1);
    }

    @Test
    void noop_leavesExistingValueUntouched() {
        store.process(store.createBatch(List.of(new Put<>(KEY, new PlainValue("v")))));
        notified.clear();

        store.process(store.createBatch(List.of(new Noop<>(KEY))));

        assertThat(store.get(KEY).or((Object) null)).isEqualTo(new PlainValue("v"));
        assertThat(notified).isEmpty();
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
