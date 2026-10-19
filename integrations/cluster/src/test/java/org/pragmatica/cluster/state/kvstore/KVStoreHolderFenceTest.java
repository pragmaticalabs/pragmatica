package org.pragmatica.cluster.state.kvstore;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Holder fences (#1155). A release of a [HolderFenced] hold lands only over a row carrying the releaser's own token, on
/// top of the successor rule of [VersionFenced]: a chain that restarted (the row was removed and written again) makes an
/// old holder's release the immediate successor of an unrelated new holder's row, and the version alone admits it. A write
/// bound to a holder ([HolderBound]) to a [HolderGuarded] key lands only while that holder is the committed one.
///
/// Same posture as `KVStorePutFenceTest`: stub value records need no codec, batches are applied directly.
class KVStoreHolderFenceTest {
    private record LockKey(String name) implements StructuredKey {}

    /// One acquisition of the lock: `token` names it, `releases` says it gives the hold up.
    private record Hold(String token, long version, boolean releases) implements HolderFenced {
        @Override
        public long fenceVersion() {
            return version;
        }

        @Override
        public String fenceHolder() {
            return token;
        }

        @Override
        public boolean fenceReleases() {
            return releases;
        }
    }

    /// A key written on behalf of the holder of [#LOCK]; `named` false models a key that names no holder key.
    private record RecordKey(String name, boolean named) implements StructuredKey, HolderGuarded {
        @Override
        public Option<Object> holderKey() {
            return named
                   ? Option.some(LOCK)
                   : Option.none();
        }
    }

    private record StateRow(String tag, Option<String> boundHolder) implements HolderBound {}

    private static final LockKey LOCK = new LockKey("task");
    private static final RecordKey RECORD = new RecordKey("task-state", true);

    private KVStore<StructuredKey, Object> store;

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
        store = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
    }

    private void put(StructuredKey key, Object value) {
        store.process(store.createBatch(List.of(new Put<>(key, value))));
    }

    private void remove(StructuredKey key) {
        store.process(store.createBatch(List.of(new KVCommand.Remove<>(key))));
    }

    private Object stored(StructuredKey key) {
        return store.get(key).or((Object) null);
    }

    @Test
    void release_byTheHolder_asTheImmediateSuccessor_isAccepted() {
        put(LOCK, new Hold("a", 1, false));
        put(LOCK, new Hold("a", 2, true));

        assertThat(stored(LOCK)).isEqualTo(new Hold("a", 2, true));
    }

    @Test
    void release_byAnotherHolder_isRefused_evenAsTheImmediateSuccessor() {
        put(LOCK, new Hold("a", 1, false));
        put(LOCK, new Hold("b", 2, true));

        assertThat(stored(LOCK)).as("b never held the lock: its release is nothing to give up").isEqualTo(new Hold("a", 1, false));
    }

    @Test
    void release_afterTheRowWasRemovedAndWrittenAgain_doesNotWipeTheNewHoldersHold() {
        put(LOCK, new Hold("old", 1, false));
        remove(LOCK);
        put(LOCK, new Hold("new", 1, false));
        put(LOCK, new Hold("old", 2, true));

        assertThat(stored(LOCK)).as("the old release is version 2 over version 1, a successor, but not the new holder's").isEqualTo(new Hold("new", 1, false));
    }

    @Test
    void release_overAnAbsentRow_isRefused() {
        put(LOCK, new Hold("a", 1, true));

        assertThat(stored(LOCK)).isNull();
    }

    @Test
    void takeover_byAnotherHolder_asTheImmediateSuccessor_isAccepted() {
        put(LOCK, new Hold("a", 1, false));
        put(LOCK, new Hold("b", 2, false));

        assertThat(stored(LOCK)).as("a claim over a stale one is not gated on the token: staleness reads a clock the applier must not").isEqualTo(new Hold("b", 2, false));
    }

    @Test
    void boundWrite_byTheCommittedHolder_isAccepted_beforeAndAfterItsRelease() {
        put(LOCK, new Hold("a", 1, false));
        put(RECORD, new StateRow("while-held", Option.some("a")));
        put(LOCK, new Hold("a", 2, true));
        put(RECORD, new StateRow("after-release", Option.some("a")));

        assertThat(stored(RECORD)).isEqualTo(new StateRow("after-release", Option.some("a")));
    }

    @Test
    void boundWrite_bySupersededHolder_isRefused() {
        put(LOCK, new Hold("a", 1, false));
        put(LOCK, new Hold("a", 2, true));
        put(LOCK, new Hold("b", 3, false));
        put(RECORD, new StateRow("b-wrote", Option.some("b")));
        put(RECORD, new StateRow("a-late", Option.some("a")));

        assertThat(stored(RECORD)).as("a's late write must not overwrite the row of the holder that replaced it").isEqualTo(new StateRow("b-wrote", Option.some("b")));
    }

    @Test
    void boundWrite_whenNoHolderIsCommitted_isRefused() {
        put(RECORD, new StateRow("orphan", Option.some("a")));

        assertThat(stored(RECORD)).isNull();
    }

    @Test
    void unboundWrite_toAGuardedKey_isNeverGated() {
        put(LOCK, new Hold("b", 1, false));
        put(RECORD, new StateRow("observer", Option.none()));

        assertThat(stored(RECORD)).isEqualTo(new StateRow("observer", Option.none()));
    }

    @Test
    void boundWrite_toAKeyThatNamesNoHolderKey_isNeverGated() {
        var unnamed = new RecordKey("per-node-row", false);

        put(unnamed, new StateRow("per-node", Option.some("anyone")));

        assertThat(stored(unnamed)).isEqualTo(new StateRow("per-node", Option.some("anyone")));
    }
}
