package org.pragmatica.cluster.state.kvstore;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVCommand.Remove;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.cluster.state.kvstore.LeaderValue.leaderValue;

/// Accept/reject matrix of the `LeaderValue` fence, written to hold IDENTICALLY before and after the leader
/// fence moved onto the generic [EpochBearing] fence (`LeaderValue` mints its `viewSequence` epoch). Every
/// row names the committed value, the command, and its outcome: APPLIED (a notification is emitted),
/// REFUSED (nothing changes, no notification) or THROWS (the applier let an exception escape). The rows
/// cover `Put` and `Remove` at a lower, equal and higher `viewSequence`, the witness cases, and every
/// cross-kind combination of a `LeaderValue` with another `EpochBearing` or a plain value. A refused leader
/// write is never counted as a stale-epoch refusal. The two deliberate changes are pinned separately: two
/// non-leader epoch kinds under one key ([KVStoreEpochKindMismatchTest]) and a `LeaderValue` under a key other
/// than the `LeaderKey` ([KVStoreLeaderValueOffKeyTest]).
class KVStoreLeaderFenceMatrixTest {
    enum Outcome {
        APPLIED,
        REFUSED,
        THROWS
    }

    private record StubEpoch(long term) implements Comparable<StubEpoch> {
        @Override
        public int compareTo(StubEpoch other) {
            return Long.compare(term, other.term);
        }
    }

    private record OwnedKey(String domain) implements StructuredKey {}

    private record OwnedValue(String tag, StubEpoch epoch) implements EpochBearing<StubEpoch> {
        @Override
        public StubEpoch fenceEpoch() {
            return epoch;
        }
    }

    private record PlainValue(String tag) {}

    private static final NodeId NODE_A = NodeId.nodeId("node-a").unwrap();
    private static final NodeId NODE_B = NodeId.nodeId("node-b").unwrap();
    private static final LeaderKey LEADER = LeaderKey.INSTANCE;
    private static final OwnedKey OWNED = new OwnedKey("core");
    private static final LeaderValue COMMITTED_LEADER = leaderValue(NODE_A, 5L);
    private static final OwnedValue COMMITTED_OWNED = new OwnedValue("owner", new StubEpoch(5));

    private KVStore<StructuredKey, Object> store;
    private List<Object> notified;

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
        var router = MessageRouter.mutable();
        store = new KVStore<>(router, stubSerializer(), stubDeserializer());
        notified = new ArrayList<>();
        router.addRoute(ValuePut.class, (ValuePut<StructuredKey, Object> event) -> notified.add(event));
        router.addRoute(ValueRemove.class, (ValueRemove<StructuredKey, Object> event) -> notified.add(event));
    }

    private void seed(StructuredKey key, Object value) {
        store.process(store.createBatch(List.of(new Put<>(key, value))));
        notified.clear();
    }

    private Outcome put(StructuredKey key, Object value) {
        return outcome(key, new Put<>(key, value), Option.some(value));
    }

    private Outcome remove(StructuredKey key, Option<Object> witness) {
        return outcome(key, new Remove<>(key, witness), Option.none());
    }

    private Outcome outcome(StructuredKey key, KVCommand<StructuredKey> command, Option<Object> afterApply) {
        var before = store.get(key);

        try {
            store.process(store.createBatch(List.of(command)));
        } catch (RuntimeException e) {
            return Outcome.THROWS;
        }

        var after = store.get(key);

        if (notified.isEmpty()) {
            assertThat(after).as("a refused command changes nothing").isEqualTo(before);
            return Outcome.REFUSED;
        }

        assertThat(after).as("an applied command stores its result").isEqualTo(afterApply);
        return Outcome.APPLIED;
    }

    @Nested
    class LeaderPut {
        @Test
        void firstWrite_applied() {
            assertThat(put(LEADER, COMMITTED_LEADER)).isEqualTo(Outcome.APPLIED);
        }

        @Test
        void lowerViewSequence_refused_uncounted() {
            seed(LEADER, COMMITTED_LEADER);

            assertThat(put(LEADER, leaderValue(NODE_B, 4L))).isEqualTo(Outcome.REFUSED);
            assertThat(store.staleEpochRefusals()).as("a refused leader write is not a stale-epoch refusal").isZero();
        }

        @Test
        void equalViewSequence_refused_uncounted() {
            seed(LEADER, COMMITTED_LEADER);

            assertThat(put(LEADER, leaderValue(NODE_B, 5L))).isEqualTo(Outcome.REFUSED);
            assertThat(store.staleEpochRefusals()).as("a refused leader write is not a stale-epoch refusal").isZero();
        }

        @Test
        void higherViewSequence_applied() {
            seed(LEADER, COMMITTED_LEADER);

            assertThat(put(LEADER, leaderValue(NODE_B, 6L))).isEqualTo(Outcome.APPLIED);
        }
    }

    @Nested
    class LeaderRemove {
        @Test
        void noWitness_refused() {
            seed(LEADER, COMMITTED_LEADER);

            assertThat(remove(LEADER, Option.none())).isEqualTo(Outcome.REFUSED);
        }

        @Test
        void lowerWitness_refused() {
            seed(LEADER, COMMITTED_LEADER);

            assertThat(remove(LEADER, Option.some(leaderValue(NODE_B, 4L)))).isEqualTo(Outcome.REFUSED);
        }

        @Test
        void equalWitness_refused() {
            seed(LEADER, COMMITTED_LEADER);

            assertThat(remove(LEADER, Option.some(leaderValue(NODE_B, 5L)))).isEqualTo(Outcome.REFUSED);
        }

        @Test
        void higherWitness_applied() {
            seed(LEADER, COMMITTED_LEADER);

            assertThat(remove(LEADER, Option.some(leaderValue(NODE_B, 6L)))).isEqualTo(Outcome.APPLIED);
        }

        @Test
        void epochBearingWitness_refused() {
            seed(LEADER, COMMITTED_LEADER);

            assertThat(remove(LEADER, Option.some(new OwnedValue("bogus", new StubEpoch(9))))).isEqualTo(Outcome.REFUSED);
        }

        @Test
        void plainWitness_refused() {
            seed(LEADER, COMMITTED_LEADER);

            assertThat(remove(LEADER, Option.some(new PlainValue("bogus")))).isEqualTo(Outcome.REFUSED);
        }

        @Test
        void absentKey_applied() {
            assertThat(remove(LEADER, Option.none())).isEqualTo(Outcome.APPLIED);
        }
    }

    @Nested
    class CrossKind {
        @Test
        void epochBearingPut_overLeaderValue_applied() {
            seed(LEADER, COMMITTED_LEADER);

            assertThat(put(LEADER, new OwnedValue("other", new StubEpoch(1)))).isEqualTo(Outcome.APPLIED);
        }

        @Test
        void plainPut_overLeaderValue_applied() {
            seed(LEADER, COMMITTED_LEADER);

            assertThat(put(LEADER, new PlainValue("other"))).isEqualTo(Outcome.APPLIED);
        }

        @Test
        void leaderValuePut_overEpochBearing_applied() {
            seed(OWNED, COMMITTED_OWNED);

            assertThat(put(OWNED, leaderValue(NODE_B, 1L))).isEqualTo(Outcome.APPLIED);
        }

        @Test
        void leaderValueWitness_onEpochBearing_refused() {
            seed(OWNED, COMMITTED_OWNED);

            assertThat(remove(OWNED, Option.some(leaderValue(NODE_B, 9L)))).isEqualTo(Outcome.REFUSED);
        }
    }
}
