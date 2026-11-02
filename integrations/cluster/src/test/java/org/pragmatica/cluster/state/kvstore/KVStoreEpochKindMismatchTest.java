package org.pragmatica.cluster.state.kvstore;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVCommand.Remove;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Two epoch-bearing values whose epochs are of DIFFERENT classes under one key. Until the leader fence moved
/// onto the generic epoch fence, the applier cast one epoch to the other's class and threw a
/// `ClassCastException` out of the consensus applier. The defined outcome is the one a `LeaderValue` paired
/// with any other kind always had: a mismatched kind never fences a `Put` and never authorizes a `Remove`.
class KVStoreEpochKindMismatchTest {
    private record StubEpoch(long term) implements Comparable<StubEpoch> {
        @Override
        public int compareTo(StubEpoch other) {
            return Long.compare(term, other.term);
        }
    }

    private record OtherEpoch(long value) implements Comparable<OtherEpoch> {
        @Override
        public int compareTo(OtherEpoch other) {
            return Long.compare(value, other.value);
        }
    }

    private record OwnedKey(String domain) implements StructuredKey {}

    private record OwnedValue(String tag, StubEpoch epoch) implements EpochBearing<StubEpoch> {
        @Override
        public StubEpoch fenceEpoch() {
            return epoch;
        }
    }

    private record OtherValue(String tag, OtherEpoch epoch) implements EpochBearing<OtherEpoch> {
        @Override
        public OtherEpoch fenceEpoch() {
            return epoch;
        }
    }

    private record OwnerValue(String owner, StubEpoch epoch) implements OwnerFenced<StubEpoch, String> {
        @Override
        public StubEpoch fenceEpoch() {
            return epoch;
        }

        @Override
        public String fenceOwner() {
            return owner;
        }
    }

    private record OtherOwnerValue(String owner, OtherEpoch epoch) implements OwnerFenced<OtherEpoch, String> {
        @Override
        public OtherEpoch fenceEpoch() {
            return epoch;
        }

        @Override
        public String fenceOwner() {
            return owner;
        }
    }

    private static final OwnedKey KEY = new OwnedKey("core");
    private static final OwnedValue COMMITTED = new OwnedValue("owner", new StubEpoch(5));
    private static final OwnerValue COMMITTED_OWNER = new OwnerValue("owner", new StubEpoch(5));

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

    private void apply(KVCommand<StructuredKey> command) {
        store.process(store.createBatch(List.of(command)));
    }

    @Test
    void otherKindPut_overEpochBearing_applied() {
        apply(new Put<>(KEY, COMMITTED));

        apply(new Put<>(KEY, new OtherValue("other", new OtherEpoch(1))));

        assertThat(store.get(KEY)).isEqualTo(Option.some(new OtherValue("other", new OtherEpoch(1))));
        assertThat(store.staleEpochRefusals()).isZero();
    }

    @Test
    void otherKindWitness_onEpochBearing_refused() {
        apply(new Put<>(KEY, COMMITTED));

        apply(new Remove<>(KEY, Option.some(new OtherValue("other", new OtherEpoch(9)))));

        assertThat(store.get(KEY)).isEqualTo(Option.some(COMMITTED));
    }

    @Test
    void otherKindOwnerWitness_onOwnerFenced_refused() {
        apply(new Put<>(KEY, COMMITTED_OWNER));

        apply(new Remove<>(KEY, Option.some(new OtherOwnerValue("other", new OtherEpoch(9)))));

        assertThat(store.get(KEY)).isEqualTo(Option.some(COMMITTED_OWNER));
    }
}
