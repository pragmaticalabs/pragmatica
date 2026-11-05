package org.pragmatica.cluster.state.kvstore;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVCommand.Remove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.cluster.state.kvstore.LeaderValue.leaderValue;

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

    private record VersionedOwned(long fenceVersion, StubEpoch epoch) implements VersionFenced, EpochBearing<StubEpoch> {
        @Override
        public StubEpoch fenceEpoch() {
            return epoch;
        }
    }

    private record VersionedOther(long fenceVersion, OtherEpoch epoch) implements VersionFenced, EpochBearing<OtherEpoch> {
        @Override
        public OtherEpoch fenceEpoch() {
            return epoch;
        }
    }

    /// Not a [LeaderValue], yet its epoch is a `Long` like the leader's `viewSequence`.
    private record LongStub(long epoch) implements EpochBearing<Long> {
        @Override
        public Long fenceEpoch() {
            return epoch;
        }
    }

    private static final OwnedKey KEY = new OwnedKey("core");
    private static final OwnedValue COMMITTED = new OwnedValue("owner", new StubEpoch(5));
    private static final OwnerValue COMMITTED_OWNER = new OwnerValue("owner", new StubEpoch(5));

    private static final LeaderKey LEADER = LeaderKey.INSTANCE;
    private static final LeaderValue COMMITTED_LEADER = leaderValue(NodeId.nodeId("node-a").unwrap(), 5L);

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

    /// A write refused by ANOTHER arm (here the version chain) is still passed to the stale-epoch refusal
    /// reporter, which must apply the same epoch-kind check as the fence: no cast across kinds, no exception.
    @Test
    void otherKindPut_refusedByVersionChain_reportsNothingAndDoesNotThrow() {
        apply(new Put<>(KEY, new VersionedOwned(1, new StubEpoch(5))));

        apply(new Put<>(KEY, new VersionedOther(9, new OtherEpoch(1))));

        assertThat(store.get(KEY)).isEqualTo(Option.some(new VersionedOwned(1, new StubEpoch(5))));
        assertThat(store.staleEpochRefusals()).isZero();
    }

    /// A different value class whose epoch is also a `Long` is not a `LeaderValue`: it never fences a `Put`
    /// under the `LeaderKey`, even at an epoch below the committed `viewSequence`.
    @Test
    void longEpochStubPut_overLeaderValue_applied() {
        apply(new Put<>(LEADER, COMMITTED_LEADER));

        apply(new Put<>(LEADER, new LongStub(3)));

        assertThat(store.get(LEADER)).isEqualTo(Option.some(new LongStub(3)));
        assertThat(store.staleEpochRefusals()).isZero();
    }

    @Test
    void leaderValuePut_overLongEpochStub_applied() {
        apply(new Put<>(LEADER, new LongStub(9)));

        apply(new Put<>(LEADER, leaderValue(NodeId.nodeId("node-b").unwrap(), 1L)));

        assertThat(store.get(LEADER)).isEqualTo(Option.some(leaderValue(NodeId.nodeId("node-b").unwrap(), 1L)));
    }

    /// The witness of a `Remove` under the `LeaderKey` must be a `LeaderValue`: a `Long`-epoch stub never
    /// authorizes it, whether its epoch is above (9) or below (2) the committed `viewSequence`.
    @Test
    void longEpochStubWitness_aboveCommitted_onLeaderKey_refused() {
        apply(new Put<>(LEADER, COMMITTED_LEADER));

        apply(new Remove<>(LEADER, Option.some(new LongStub(9))));

        assertThat(store.get(LEADER)).isEqualTo(Option.some(COMMITTED_LEADER));
    }

    @Test
    void longEpochStubWitness_belowCommitted_onLeaderKey_refused() {
        apply(new Put<>(LEADER, COMMITTED_LEADER));

        apply(new Remove<>(LEADER, Option.some(new LongStub(2))));

        assertThat(store.get(LEADER)).isEqualTo(Option.some(COMMITTED_LEADER));
    }

    @Test
    void leaderValueWitness_onLongEpochStub_refused() {
        apply(new Put<>(LEADER, new LongStub(5)));

        apply(new Remove<>(LEADER, Option.some(leaderValue(NodeId.nodeId("node-b").unwrap(), 9L))));

        assertThat(store.get(LEADER)).isEqualTo(Option.some(new LongStub(5)));
    }
}
