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
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/// Grow-only merge (#1778): a `Put` of a [GrowOnlyMergeable] value over a committed value of the same class is
/// stored MERGED, so concurrent writers fold instead of overwriting each other. Batches are applied directly, in
/// the order the consensus log would deliver them.
class KVStoreGrowOnlyMergeTest {
    private record SetKey(String name) implements StructuredKey {}

    private record Names(Set<String> values) implements GrowOnlyMergeable<Names> {
        Names {
            values = new TreeSet<>(values);
        }

        static Names of(String... names) {
            return new Names(Set.of(names));
        }

        @Override
        public Names mergeInto(Names committed) {
            var merged = new TreeSet<>(committed.values());

            merged.addAll(values);

            return new Names(merged);
        }
    }

    private record Plain(String value) {}

    /// A first-write-wins binding: merging into a committed value yields the committed value.
    private record Binding(String digest) implements GrowOnlyMergeable<Binding> {
        @Override
        public Binding mergeInto(Binding committed) {
            return committed;
        }
    }

    private static final SetKey KEY = new SetKey("names");

    private KVStore<StructuredKey, Object> store;
    private List<Object> notified;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        notified = new ArrayList<>();

        var router = MessageRouter.mutable();

        router.addRoute((Class) ValuePut.class, (java.util.function.Consumer) message -> notified.add(((ValuePut) message).cause().value()));
        store = new KVStore<>(router, new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        }, new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void put(StructuredKey key, Object value) {
        store.process(store.createBatch((List) List.of(new Put<>(key, value))));
    }

    @Test
    void put_foldsWriters_inEitherOrder() {
        put(KEY, Names.of("a"));
        put(KEY, Names.of("b"));
        put(KEY, Names.of("a"));

        assertThat(store.get(KEY).or((Object) null)).isEqualTo(Names.of("a", "b"));
    }

    @Test
    void put_keepsTheFirstCommittedBinding_whateverIsProposedLater() {
        var key = new SetKey("binding");

        put(key, new Binding("first"));
        put(key, new Binding("second"));
        put(key, new Binding("first"));

        assertThat(store.get(key).or((Object) null)).isEqualTo(new Binding("first"));
    }

    @Test
    void put_storesAFirstWriteAsItIs() {
        put(KEY, Names.of("a"));

        assertThat(store.get(KEY).or((Object) null)).isEqualTo(Names.of("a"));
    }

    @Test
    void put_announcesTheMergedValue_notTheWrittenOne() {
        put(KEY, Names.of("a"));
        put(KEY, Names.of("b"));

        assertThat(notified).containsExactly(Names.of("a"), Names.of("a", "b"));
    }

    @Test
    void put_overwritesAValueOfAnotherClass_andLeavesOrdinaryValuesAlone() {
        put(KEY, new Plain("old"));
        put(KEY, Names.of("a"));

        assertThat(store.get(KEY).or((Object) null)).as("no merge across classes").isEqualTo(Names.of("a"));

        var plainKey = new SetKey("plain");

        put(plainKey, new Plain("one"));
        put(plainKey, new Plain("two"));

        assertThat(store.get(plainKey).or((Object) null)).isEqualTo(new Plain("two"));
    }
}
