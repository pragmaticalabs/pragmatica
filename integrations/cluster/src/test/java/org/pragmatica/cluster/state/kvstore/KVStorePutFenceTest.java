package org.pragmatica.cluster.state.kvstore;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Lost-update fence (RFC-0018, #570): a `Put` of a [VersionFenced] value is applied only when its
/// version is the IMMEDIATE SUCCESSOR of the committed one. Equal is REJECTED — unlike the epoch
/// fence, where equality is a legitimate re-announcement — because on a version chain an equal
/// incoming version IS the second writer of the read-modify-write race, about to silently overwrite
/// the first. Jumps are rejected too: a write built on anything but the current committed value is
/// built on a stale read. A first write passes (no chain yet), which admits bootstrap seeds and
/// makes racing seeds resolve first-wins. Non-fenced values are untouched by the fence.
///
/// The stub value records need no codec — batches are applied directly and never snapshotted
/// (same posture as `KVStoreRemoveFenceTest`). Determinism is asserted by content comparison of two
/// independent stores fed the same batches, NOT by snapshot-byte equality: with the stub serializer
/// a byte comparison would be vacuously equal (both empty), proving nothing.
class KVStorePutFenceTest {
    private record FencedKey(String name) implements StructuredKey {}

    /// `tag` identifies WHICH writer's value is committed — the whole point of the fence.
    private record FencedValue(String tag, long version) implements VersionFenced {
        @Override
        public long fenceVersion() {
            return version;
        }
    }

    private record PlainKey(String name) implements StructuredKey {}

    private record PlainValue(String tag) {}

    private static final FencedKey KEY = new FencedKey("cluster-config");

    private MessageRouter.MutableRouter router;
    private KVStore<StructuredKey, Object> store;
    private List<Object> putNotifications;

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

    @BeforeEach
    void setUp() {
        router = MessageRouter.mutable();
        store = new KVStore<>(router, stubSerializer(), stubDeserializer());
        putNotifications = new ArrayList<>();
        router.addRoute(ValuePut.class, (ValuePut<StructuredKey, Object> event) -> putNotifications.add(event.cause().value()));
    }

    private void put(StructuredKey key, Object value) {
        store.process(store.createBatch(List.of(new Put<>(key, value))));
    }

    private Object stored(StructuredKey key) {
        return store.get(key).or((Object) null);
    }

    @Test
    void firstWrite_isAccepted_thereIsNoChainYet() {
        put(KEY, new FencedValue("seed", 1));

        assertThat(stored(KEY)).isEqualTo(new FencedValue("seed", 1));
    }

    @Test
    void successorWrite_isAccepted() {
        put(KEY, new FencedValue("a", 5));
        put(KEY, new FencedValue("b", 6));

        assertThat(stored(KEY)).isEqualTo(new FencedValue("b", 6));
    }

    /// THE lost-update race. Writers A and B both read v5 and both compute v6. A commits first; B's
    /// equal-version write used to silently overwrite A and must now be rejected — the committed
    /// value stays A's, and B's write emits no notification.
    @Test
    void equalVersionWrite_isRejected_secondWriterOfTheRaceLoses() {
        put(KEY, new FencedValue("base", 5));
        put(KEY, new FencedValue("writer-a", 6));
        putNotifications.clear();

        put(KEY, new FencedValue("writer-b", 6));

        assertThat(stored(KEY))
                .as("the first committed v6 must survive; the racing equal-version write must not overwrite it")
                .isEqualTo(new FencedValue("writer-a", 6));
        assertThat(putNotifications)
                .as("a rejected write must emit NO ValuePut — subscribers never observe the loser")
                .isEmpty();
    }

    @Test
    void olderVersionWrite_isRejected() {
        put(KEY, new FencedValue("current", 7));

        put(KEY, new FencedValue("stale", 5));

        assertThat(stored(KEY)).isEqualTo(new FencedValue("current", 7));
    }

    /// A jump is a write built on a value that was never committed — a stale or speculative read.
    @Test
    void versionJumpWrite_isRejected() {
        put(KEY, new FencedValue("current", 5));

        put(KEY, new FencedValue("jumper", 8));

        assertThat(stored(KEY)).isEqualTo(new FencedValue("current", 5));
    }

    /// Racing bootstrap seeds: both read "absent", both write v1. First wins; the second arrives
    /// against a committed v1 and 1 != 2 rejects it. Previously the second silently overwrote.
    @Test
    void racingSeeds_resolveFirstWins() {
        put(KEY, new FencedValue("seed-a", 1));
        put(KEY, new FencedValue("seed-b", 1));

        assertThat(stored(KEY)).isEqualTo(new FencedValue("seed-a", 1));
    }

    @Test
    void nonFencedValues_overwriteFreely_fenceIsInert() {
        var key = new PlainKey("free");
        put(key, new PlainValue("first"));
        put(key, new PlainValue("second"));

        assertThat(stored(key)).isEqualTo(new PlainValue("second"));
    }

    /// The determinism obligation the applier documents: the fence decision reads only committed
    /// storage and the incoming value, so two independent stores fed the same batches — including
    /// the rejected ones — must hold identical committed state. This is what a predicate reading
    /// wall-clock, randomness, or node-local state would break.
    @Test
    void twoStores_sameBatches_convergeIdentically_includingRejections() {
        var other = new KVStore<StructuredKey, Object>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());

        var batches = List.of(new Put<StructuredKey, Object>(KEY, new FencedValue("base", 5)),
                              new Put<StructuredKey, Object>(KEY, new FencedValue("writer-a", 6)),
                              new Put<StructuredKey, Object>(KEY, new FencedValue("writer-b", 6)),
                              new Put<StructuredKey, Object>(KEY, new FencedValue("late", 9)),
                              new Put<StructuredKey, Object>(KEY, new FencedValue("next", 7)));

        for (var command : batches) {
            store.process(store.createBatch(List.of(command)));
            other.process(other.createBatch(List.of(command)));
        }

        assertThat(stored(KEY)).isEqualTo(other.get(KEY).or((Object) null))
                               .isEqualTo(new FencedValue("next", 7));
    }
}
