package org.pragmatica.cluster.state.kvstore;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.lang.Option;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KVStoreOwnerFenceTest {
    private record Key(String value) implements StructuredKey {}
    private record Owner(Long fenceEpoch, String fenceOwner, String payload) implements OwnerFenced<Long, String> {}
    private static final Key KEY = new Key("community");

    private KVStore<Key, Object> store() {
        return new KVStore<>(MessageRouter.mutable(), new Serializer() {
            @Override public <T> void write(ByteBuf buffer, T value) {}
        }, new Deserializer() {
            @Override public <T> T read(ByteBuf buffer) { return null; }
        });
    }

    private void put(KVStore<Key, Object> store, Object value) {
        store.process(store.createBatch(List.of(new KVCommand.Put<>(KEY, value))));
    }

    @Test
    void equalEpoch_differentOwnerCannotOverwriteOrDelete() {
        var store = store();
        var incumbent = new Owner(7L, "a", "initial");
        var contender = new Owner(7L, "b", "conflicting");
        put(store, incumbent);
        put(store, contender);
        store.process(store.createBatch(List.of(new KVCommand.Remove<>(KEY, Option.some(contender)))));

        assertThat(store.get(KEY).unwrap()).isEqualTo(incumbent);
    }

    @Test
    void sameOwnerRefreshAndSuccessorTransfer_areAccepted() {
        var store = store();
        put(store, new Owner(7L, "a", "initial"));
        var refreshed = new Owner(7L, "a", "refreshed");
        put(store, refreshed);
        assertThat(store.get(KEY).unwrap()).isEqualTo(refreshed);
        var successor = new Owner(8L, "b", "successor");
        put(store, successor);
        put(store, refreshed);
        assertThat(store.get(KEY).unwrap()).isEqualTo(successor);
    }

    @Test
    void plainValueCannotEraseOwnerFence() {
        var store = store();
        var owner = new Owner(1L, "a", "initial");
        put(store, owner);
        put(store, "unfenced");
        assertThat(store.get(KEY).unwrap()).isEqualTo(owner);
    }
    @Test
    void witnessedSameOwnerRemovalCannotClearFenceForSameEpochContender() {
        var store = store();
        var owner = new Owner(7L, "a", "initial");
        put(store, owner);
        store.process(store.createBatch(List.of(new KVCommand.Remove<>(KEY, Option.some(owner)))));
        put(store, new Owner(7L, "b", "contender"));
        assertThat(store.get(KEY).unwrap()).isEqualTo(owner);
        store.process(store.createBatch(List.of(new KVCommand.Remove<>(KEY, Option.some(new Owner(8L, "a", "release"))))));
        assertThat(store.get(KEY).isEmpty()).isTrue();
    }

}
