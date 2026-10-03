package org.pragmatica.cluster.state.kvstore;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVCommand.Remove;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// #806: a [WitnessedRemoval] value is deleted only by a witness EQUAL to the committed value, so a
/// holder whose claim was superseded cannot erase its successor's record. A plain [VersionFenced] value
/// keeps deleting freely: the rule is opt-in, because other `VersionFenced` records still have
/// witnessless removers (#972).
class KVStoreWitnessedRemovalTest {
    private record LockKey(String name) implements StructuredKey {}

    private record LockValue(String holder, long expiresAt, long version) implements WitnessedRemoval {
        @Override
        public long fenceVersion() {
            return version;
        }
    }

    private record PlainFencedValue(String tag, long version) implements VersionFenced {
        @Override
        public long fenceVersion() {
            return version;
        }
    }

    private static final LockKey KEY = new LockKey("orders");

    private KVStore<StructuredKey, Object> store;
    private List<Object> removed;

    @BeforeEach
    void setUp() {
        var router = MessageRouter.mutable();

        store = new KVStore<>(router, stubSerializer(), stubDeserializer());
        removed = new ArrayList<>();
        router.addRoute(ValueRemove.class, (ValueRemove<StructuredKey, Object> event) -> removed.add(event.value()));
    }

    @Test
    void bareRemove_ofWitnessedValue_isRefused() {
        put(new LockValue("A", 100, 1));

        store.process(store.createBatch(List.of(new Remove<>(KEY))));

        assertThat(stored()).isEqualTo(new LockValue("A", 100, 1));
    }

    @Test
    void equalWitness_removes_andNotifiesOnce() {
        put(new LockValue("A", 100, 1));
        removed.clear();

        removeWith(new LockValue("A", 100, 1));

        assertThat(stored()).isNull();
        assertThat(removed).containsExactly(Option.some(new LockValue("A", 100, 1)));
    }

    @Test
    void supersededHolder_cannotRemoveSuccessorsValue() {
        put(new LockValue("A", 100, 1));
        put(new LockValue("B", 200, 2));
        removed.clear();

        removeWith(new LockValue("A", 100, 1));

        assertThat(stored()).as("B's lock survives A's stale release").isEqualTo(new LockValue("B", 200, 2));
        assertThat(removed).as("a refused remove emits no notification").isEmpty();
    }

    /// A released key restarts its chain, so a stale holder's v1 witness meets an unrelated v1 claim:
    /// the version number alone would let it through; full-value equality does not.
    @Test
    void staleHolderWithSameVersionAsLaterClaim_cannotRemoveIt() {
        put(new LockValue("A", 100, 1));
        removeWith(new LockValue("A", 100, 1));
        put(new LockValue("C", 300, 1));

        removeWith(new LockValue("A", 100, 1));

        assertThat(stored()).isEqualTo(new LockValue("C", 300, 1));
    }

    @Test
    void renewedValue_isRemovedOnlyByTheRenewedWitness() {
        put(new LockValue("A", 100, 1));
        put(new LockValue("A", 400, 2));

        removeWith(new LockValue("A", 100, 1));
        assertThat(stored()).as("the pre-renewal value is no longer the witness").isEqualTo(new LockValue("A", 400, 2));

        removeWith(new LockValue("A", 400, 2));
        assertThat(stored()).isNull();
    }

    @Test
    void wrongTypedWitness_isRefused() {
        put(new LockValue("A", 100, 1));

        removeWith(new PlainFencedValue("A", 1));

        assertThat(stored()).isEqualTo(new LockValue("A", 100, 1));
    }

    @Test
    void removeOfAbsentKey_isHarmless() {
        removeWith(new LockValue("A", 100, 1));

        assertThat(stored()).isNull();
    }

    @Test
    void plainVersionFencedValue_stillDeletesWithoutWitness() {
        store.process(store.createBatch(List.of(new Put<>(KEY, new PlainFencedValue("x", 1)))));

        store.process(store.createBatch(List.of(new Remove<>(KEY))));

        assertThat(stored()).as("opt-in: VersionFenced alone does not fence delete").isNull();
    }

    private void put(Object value) {
        store.process(store.createBatch(List.of(new Put<>(KEY, value))));
    }

    private void removeWith(Object witness) {
        store.process(store.createBatch(List.of(new Remove<>(KEY, Option.some(witness)))));
    }

    private Object stored() {
        return store.get(KEY).or((Object) null);
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
