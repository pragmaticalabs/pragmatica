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

/// Scoped advance-only fence (#1961, I-18): a `Put` of a [ScopedMonotonic] value is refused when its scope is lower than
/// the committed one, or equal with a lower watermark. A strictly newer scope may lower the watermark (the legitimate
/// regressions: an operator rewind, a KIP-320 re-seek). Scopes of different classes are never compared. Every case pins
/// WHICH writer's value is committed, not merely that one remains.
///
/// Same stub-codec posture as `KVStoreWatermarkFenceTest`: batches are applied directly, never snapshotted.
class KVStoreScopedMonotonicFenceTest {
    private record CursorKey(String name) implements StructuredKey {}

    private record Scope(long major, long minor) implements Comparable<Scope> {
        @Override
        public int compareTo(Scope other) {
            var byMajor = Long.compare(major, other.major);

            return byMajor != 0
                   ? byMajor
                   : Long.compare(minor, other.minor);
        }
    }

    private record OtherScope(long value) implements Comparable<OtherScope> {
        @Override
        public int compareTo(OtherScope other) {
            return Long.compare(value, other.value);
        }
    }

    private record CursorValue(String tag, Scope scope, long watermark) implements ScopedMonotonic<Scope> {}

    private record OtherKindValue(String tag, OtherScope scope, long watermark) implements ScopedMonotonic<OtherScope> {}

    private static final CursorKey KEY = new CursorKey("group-cursor");
    private static final Scope SCOPE = new Scope(1, 1);
    private static final Scope NEWER_MINOR = new Scope(1, 2);
    private static final Scope NEWER_MAJOR = new Scope(2, 0);

    private MessageRouter.MutableRouter router;
    private KVStore<StructuredKey, Object> store;
    private List<Object> putNotifications;

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
                throw new UnsupportedOperationException("never snapshotted in this test");
            }
        };
    }

    @BeforeEach
    void setUp() {
        router = MessageRouter.mutable();
        store = new KVStore<>(router, stubSerializer(), stubDeserializer());
        putNotifications = new ArrayList<>();
        router.addRoute(ValuePut.class,
                        (ValuePut<StructuredKey, Object> event) -> putNotifications.add(event.cause().value()));
    }

    private void put(StructuredKey key, Object value) {
        store.process(store.createBatch(List.of(new Put<>(key, value))));
    }

    private Object stored(StructuredKey key) {
        return store.get(key).or((Object) null);
    }

    /// The #1961 race: a late commit inside the same scope lands after a higher one and is refused.
    @Test
    void put_sameScopeLowerWatermark_isRefused_higherStands() {
        put(KEY, new CursorValue("current", SCOPE, 100));
        put(KEY, new CursorValue("late", SCOPE, 40));

        assertThat(stored(KEY)).isEqualTo(new CursorValue("current", SCOPE, 100));
    }

    @Test
    void put_sameScopeEqualWatermark_isAccepted() {
        put(KEY, new CursorValue("first", SCOPE, 100));
        put(KEY, new CursorValue("again", SCOPE, 100));

        assertThat(stored(KEY)).isEqualTo(new CursorValue("again", SCOPE, 100));
    }

    @Test
    void put_sameScopeHigherWatermark_isAccepted() {
        put(KEY, new CursorValue("first", SCOPE, 100));
        put(KEY, new CursorValue("advanced", SCOPE, 250));

        assertThat(stored(KEY)).isEqualTo(new CursorValue("advanced", SCOPE, 250));
    }

    /// A strictly newer scope in its SECOND component (the owner epoch of a re-seek) may lower the watermark.
    @Test
    void put_newerMinorScopeLowerWatermark_isAccepted() {
        put(KEY, new CursorValue("before-reseek", SCOPE, 100));
        put(KEY, new CursorValue("reseek", NEWER_MINOR, 40));

        assertThat(stored(KEY)).isEqualTo(new CursorValue("reseek", NEWER_MINOR, 40));
    }

    /// A strictly newer scope in its FIRST component (the rewind epoch) may lower the watermark, whatever the second.
    @Test
    void put_newerMajorScopeLowerWatermark_isAccepted() {
        put(KEY, new CursorValue("before-rewind", NEWER_MINOR, 100));
        put(KEY, new CursorValue("rewind", NEWER_MAJOR, 0));

        assertThat(stored(KEY)).isEqualTo(new CursorValue("rewind", NEWER_MAJOR, 0));
    }

    /// An older scope is refused even when its watermark is higher: the scope ranks first.
    @Test
    void put_olderScopeHigherWatermark_isRefused() {
        put(KEY, new CursorValue("current", NEWER_MINOR, 40));
        put(KEY, new CursorValue("zombie", SCOPE, 500));

        assertThat(stored(KEY)).isEqualTo(new CursorValue("current", NEWER_MINOR, 40));
    }

    @Test
    void put_firstWrite_passesWithNoCommittedScope() {
        put(KEY, new CursorValue("bootstrap", SCOPE, 0));

        assertThat(stored(KEY)).isEqualTo(new CursorValue("bootstrap", SCOPE, 0));
    }

    /// Scopes of different classes are not comparable: the arm does not fence (and must not throw inside the applier).
    @Test
    void put_differentScopeKinds_areNeverCompared() {
        put(KEY, new CursorValue("one-kind", SCOPE, 100));
        put(KEY, new OtherKindValue("another-kind", new OtherScope(0), 1));

        assertThat(stored(KEY)).isEqualTo(new OtherKindValue("another-kind", new OtherScope(0), 1));
    }

    /// A refused write must be invisible downstream: no `ValuePut` for a regression that never happened.
    @Test
    void put_refusedWrite_emitsNoNotification() {
        put(KEY, new CursorValue("current", SCOPE, 100));
        putNotifications.clear();
        put(KEY, new CursorValue("late", SCOPE, 40));

        assertThat(putNotifications).isEmpty();
    }
}
