package org.pragmatica.cluster.state.kvstore;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.cluster.state.kvstore.LeaderValue.leaderValue;

/// Ownership/governance epoch fence (#345 piece 1a): generalizes the H4 `LeaderKey` fence to ANY
/// [EpochBearing] value. The applier rejects a `Put` whose epoch is STRICTLY older than the
/// committed one (no value mutation, no `ValuePut`), accepts equal-or-newer, and leaves
/// non-`EpochBearing` values and the existing `LeaderKey`/`viewSequence` path untouched.
///
/// Uses a self-contained `Comparable`-epoch stub value (the real `Epoch` lives in the BSL-1.1
/// `aether/slice` module, which depends on this one — not the reverse). Driving the gate with a
/// stub epoch type also proves it is type-agnostic and depends only on `Comparable.compareTo`.
/// A no-op stub serializer is used (mirroring `KvBackedStreamRegistryTest`): the test applies
/// batches directly to the applier and never snapshots, so the stub value records need no codec.
class KVStoreEpochFenceTest {
    /// A minimal self-comparable epoch token standing in for `aether/slice` `Epoch`.
    private record StubEpoch(long term, long counter) implements Comparable<StubEpoch> {
        @Override
        public int compareTo(StubEpoch other) {
            var byTerm = Long.compare(term, other.term);
            return byTerm != 0 ? byTerm : Long.compare(counter, other.counter);
        }

        @Override
        public String toString() {
            return term + ":" + counter;
        }
    }

    /// An epoch-bearing key (distinct from `LeaderKey`) to exercise the generalized arm.
    private record OwnedKey(String domain) implements StructuredKey {}

    /// An epoch-bearing value: `tag` lets us assert which write won at the same epoch.
    private record OwnedValue(String tag, StubEpoch epoch) implements EpochBearing<StubEpoch> {
        @Override
        public StubEpoch fenceEpoch() {
            return epoch;
        }
    }

    /// A non-epoch-bearing value — must pass through the fence unchanged (LWW overwrite).
    private record PlainValue(String tag) {}

    private static final OwnedKey KEY = new OwnedKey("core");

    private MessageRouter.MutableRouter router;
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
        router = MessageRouter.mutable();
        store = new KVStore<>(router, stubSerializer(), stubDeserializer());
        notified = new ArrayList<>();
        router.addRoute(ValuePut.class, (ValuePut<StructuredKey, Object> put) -> notified.add(put.cause().value()));
    }

    private void apply(StructuredKey key, Object value) {
        store.process(store.createBatch(List.of(new Put<>(key, value))));
    }

    private Object stored(StructuredKey key) {
        return store.get(key).or((Object) null);
    }

    @Nested
    class EpochFenceSemantics {
        @Test
        void firstWrite_appliedWhenNoValueStored() {
            apply(KEY, new OwnedValue("a", new StubEpoch(1, 0)));

            assertThat(stored(KEY)).isEqualTo(new OwnedValue("a", new StubEpoch(1, 0)));
            assertThat(notified).containsExactly(new OwnedValue("a", new StubEpoch(1, 0)));
        }

        @Test
        void strictlyNewerEpoch_applied() {
            apply(KEY, new OwnedValue("a", new StubEpoch(1, 0)));
            apply(KEY, new OwnedValue("b", new StubEpoch(2, 0)));

            assertThat(stored(KEY)).isEqualTo(new OwnedValue("b", new StubEpoch(2, 0)));
        }

        @Test
        void newerByCounterSameTerm_applied() {
            apply(KEY, new OwnedValue("a", new StubEpoch(5, 3)));
            apply(KEY, new OwnedValue("b", new StubEpoch(5, 4)));

            assertThat(stored(KEY))
                .as("lexicographic (term, counter): same term, higher counter is newer")
                .isEqualTo(new OwnedValue("b", new StubEpoch(5, 4)));
        }

        @Test
        void equalEpoch_applied_legitimateSameEpochRewrite() {
            apply(KEY, new OwnedValue("a", new StubEpoch(3, 0)));
            apply(KEY, new OwnedValue("b", new StubEpoch(3, 0)));

            assertThat(stored(KEY))
                .as("governor reannounce/dissolve and stale-owner takeover legitimately rewrite at the same epoch")
                .isEqualTo(new OwnedValue("b", new StubEpoch(3, 0)));
        }

        @Test
        void strictlyOlderEpoch_rejected() {
            apply(KEY, new OwnedValue("a", new StubEpoch(5, 0)));
            apply(KEY, new OwnedValue("b", new StubEpoch(2, 0)));

            assertThat(stored(KEY))
                .as("a deposed owner/governor must not commit an OLD epoch over a newer one")
                .isEqualTo(new OwnedValue("a", new StubEpoch(5, 0)));
        }

        @Test
        void olderByCounterSameTerm_rejected() {
            apply(KEY, new OwnedValue("a", new StubEpoch(5, 7)));
            apply(KEY, new OwnedValue("b", new StubEpoch(5, 2)));

            assertThat(stored(KEY)).isEqualTo(new OwnedValue("a", new StubEpoch(5, 7)));
        }

        @Test
        void rejectedWrite_emitsNoNotification() {
            apply(KEY, new OwnedValue("a", new StubEpoch(5, 0)));
            notified.clear();

            apply(KEY, new OwnedValue("b", new StubEpoch(2, 0)));

            assertThat(notified)
                .as("a stale-epoch write mutates nothing and must not be observed by subscribers")
                .isEmpty();
        }

        @Test
        void acceptedWrite_emitsNotification() {
            apply(KEY, new OwnedValue("a", new StubEpoch(1, 0)));
            notified.clear();

            apply(KEY, new OwnedValue("b", new StubEpoch(2, 0)));

            assertThat(notified).containsExactly(new OwnedValue("b", new StubEpoch(2, 0)));
        }
    }

    @Nested
    class NonEpochBearingUnaffected {
        @Test
        void plainValue_overwritesFreely_fenceInert() {
            apply(KEY, new PlainValue("first"));
            apply(KEY, new PlainValue("second"));

            assertThat(stored(KEY))
                .as("a non-EpochBearing value is not fenced — last write wins")
                .isEqualTo(new PlainValue("second"));
        }

        @Test
        void epochBearingOverPlain_notFenced_firstWriteHasNoStoredEpoch() {
            apply(KEY, new PlainValue("plain"));
            apply(KEY, new OwnedValue("owned", new StubEpoch(1, 0)));

            assertThat(stored(KEY))
                .as("fence only engages when BOTH committed and incoming are EpochBearing")
                .isEqualTo(new OwnedValue("owned", new StubEpoch(1, 0)));
        }

        @Test
        void plainOverEpochBearing_notFenced_incomingHasNoEpoch() {
            apply(KEY, new OwnedValue("owned", new StubEpoch(9, 0)));
            apply(KEY, new PlainValue("plain"));

            assertThat(stored(KEY))
                .as("an incoming non-EpochBearing value bypasses the fence entirely")
                .isEqualTo(new PlainValue("plain"));
        }
    }

    @Nested
    class Determinism {
        @Test
        void sameInputs_sameDecision_noHiddenState() {
            var storeA = new KVStore<StructuredKey, Object>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
            var storeB = new KVStore<StructuredKey, Object>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());

            var commands = List.<Object>of(new OwnedValue("e1", new StubEpoch(1, 0)),
                                           new OwnedValue("e3", new StubEpoch(3, 0)),
                                           new OwnedValue("stale", new StubEpoch(2, 0)),
                                           new OwnedValue("e3b", new StubEpoch(3, 0)));

            commands.forEach(v -> {
                storeA.process(storeA.createBatch(List.of(new Put<>(KEY, v))));
                storeB.process(storeB.createBatch(List.of(new Put<>(KEY, v))));
            });

            assertThat(storeA.get(KEY).or((Object) null))
                .as("two independent replicas applying the same command sequence reach identical state")
                .isEqualTo(storeB.get(KEY).or((Object) null))
                .isEqualTo(new OwnedValue("e3b", new StubEpoch(3, 0)));
        }
    }

    /// The pre-existing H4 `LeaderKey`/`viewSequence` fence MUST be unchanged by the generalization:
    /// `LeaderValue` is NOT `EpochBearing`, so it still goes through `staleLeaderWrite` with its
    /// stale-OR-EQUAL (`<=`) rejection — distinct from the strictly-older epoch rule.
    @Nested
    class LeaderFenceRegression {
        private static final NodeId NODE_A = NodeId.nodeId("node-a").unwrap();
        private static final NodeId NODE_B = NodeId.nodeId("node-b").unwrap();

        @Test
        void strictlyGreaterViewSequence_applied() {
            apply(LeaderKey.INSTANCE, leaderValue(NODE_A, 1L));
            apply(LeaderKey.INSTANCE, leaderValue(NODE_B, 2L));

            assertThat(stored(LeaderKey.INSTANCE)).isEqualTo(leaderValue(NODE_B, 2L));
        }

        @Test
        void equalViewSequence_rejected_unlikeEpochFence() {
            apply(LeaderKey.INSTANCE, leaderValue(NODE_A, 3L));
            apply(LeaderKey.INSTANCE, leaderValue(NODE_B, 3L));

            assertThat(stored(LeaderKey.INSTANCE))
                .as("LeaderValue keeps stale-or-equal rejection — first commit wins on equal sequence")
                .isEqualTo(leaderValue(NODE_A, 3L));
        }

        @Test
        void lowerViewSequence_rejected() {
            apply(LeaderKey.INSTANCE, leaderValue(NODE_A, 5L));
            apply(LeaderKey.INSTANCE, leaderValue(NODE_B, 2L));

            assertThat(stored(LeaderKey.INSTANCE)).isEqualTo(leaderValue(NODE_A, 5L));
        }
    }
}
