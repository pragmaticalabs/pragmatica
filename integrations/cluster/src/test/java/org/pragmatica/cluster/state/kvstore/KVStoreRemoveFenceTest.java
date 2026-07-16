package org.pragmatica.cluster.state.kvstore;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVCommand.Remove;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.serialization.SliceCodec;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.cluster.state.kvstore.LeaderValue.leaderValue;
import static org.pragmatica.serialization.FrameworkCodecs.frameworkCodecs;

/// Delete-side committed-state fence (#379): the epoch/leader fence that guards `Put` now also guards
/// `Remove`, STRICTER than `Put` by design. A `Remove` of a key whose COMMITTED value is fenced (an
/// [EpochBearing] value, or the `LeaderKey`'s [LeaderValue]) is rejected (no mutation, NO `ValueRemove`)
/// UNLESS it carries a `witness` that is present, of the matching kind, and current (equal-or-newer
/// epoch / strictly-greater `viewSequence`). A missing, wrong-typed, or stale witness fails — so a
/// deposed owner cannot delete a fenced key even with a bare `Remove(key)`. A non-fenced committed
/// value, or an absent key, deletes freely (witness ignored), preserving every existing unfenced
/// remover. The decision reads only committed storage and the command, so every replica decides
/// identically in the applier.
///
/// The epoch cases use a self-contained `Comparable`-epoch stub value (the real `Epoch` lives in the
/// BSL-1.1 `aether/slice` module, which depends on this one) with a no-op stub serializer — the test
/// applies batches directly and never snapshots, so the stub value records need no codec. The codec
/// round-trip class uses the real `KvstoreCodecs` with a `LeaderValue` witness (which has a codec).
class KVStoreRemoveFenceTest {
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

    /// An epoch-bearing value: `tag` lets us assert which value is committed.
    private record OwnedValue(String tag, StubEpoch epoch) implements EpochBearing<StubEpoch> {
        @Override
        public StubEpoch fenceEpoch() {
            return epoch;
        }
    }

    /// A non-epoch-bearing value — a committed one must delete freely (fence inert).
    private record PlainValue(String tag) {}

    private static final OwnedKey KEY = new OwnedKey("core");

    private MessageRouter.MutableRouter router;
    private KVStore<StructuredKey, Object> store;
    private List<Object> removed;

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
        removed = new ArrayList<>();
        router.addRoute(ValueRemove.class, (ValueRemove<StructuredKey, Object> event) -> removed.add(event.value()));
    }

    private void put(StructuredKey key, Object value) {
        store.process(store.createBatch(List.of(new Put<>(key, value))));
    }

    private void removeWithWitness(StructuredKey key, Object witness) {
        store.process(store.createBatch(List.of(new Remove<>(key, Option.some(witness)))));
    }

    private void removeUnfenced(StructuredKey key) {
        store.process(store.createBatch(List.of(new Remove<>(key))));
    }

    private Object stored(StructuredKey key) {
        return store.get(key).or((Object) null);
    }

    @Nested
    class EpochRemoveFence {
        @Test
        void staleEpochWitness_removeRejected_keySurvives() {
            put(KEY, new OwnedValue("owner", new StubEpoch(5, 0)));

            removeWithWitness(KEY, new OwnedValue("deposed", new StubEpoch(2, 0)));

            assertThat(stored(KEY))
                .as("a deposed owner's strictly-older-epoch witness must not delete a fenced key")
                .isEqualTo(new OwnedValue("owner", new StubEpoch(5, 0)));
        }

        @Test
        void olderByCounterSameTerm_removeRejected() {
            put(KEY, new OwnedValue("owner", new StubEpoch(5, 7)));

            removeWithWitness(KEY, new OwnedValue("deposed", new StubEpoch(5, 2)));

            assertThat(stored(KEY))
                .as("lexicographic (term, counter): same term, lower counter is older — rejected")
                .isEqualTo(new OwnedValue("owner", new StubEpoch(5, 7)));
        }

        @Test
        void equalEpochWitness_removeApplied_currentOwnerDeletes() {
            put(KEY, new OwnedValue("owner", new StubEpoch(3, 0)));

            removeWithWitness(KEY, new OwnedValue("owner", new StubEpoch(3, 0)));

            assertThat(stored(KEY))
                .as("the current owner (equal epoch) legitimately deletes/relinquishes its key")
                .isNull();
        }

        @Test
        void newerEpochWitness_removeApplied() {
            put(KEY, new OwnedValue("owner", new StubEpoch(3, 0)));

            removeWithWitness(KEY, new OwnedValue("successor", new StubEpoch(5, 0)));

            assertThat(stored(KEY))
                .as("a newer-epoch owner may delete")
                .isNull();
        }

        @Test
        void newerByCounterSameTerm_removeApplied() {
            put(KEY, new OwnedValue("owner", new StubEpoch(5, 3)));

            removeWithWitness(KEY, new OwnedValue("owner", new StubEpoch(5, 4)));

            assertThat(stored(KEY)).isNull();
        }

        @Test
        void removeOfAbsentKey_appliedRegardlessOfWitness() {
            removeWithWitness(KEY, new OwnedValue("whoever", new StubEpoch(1, 0)));

            assertThat(stored(KEY))
                .as("no committed value means nothing to fence against — the remove is a no-op delete")
                .isNull();
        }

        @Test
        void rejectedRemove_emitsNoValueRemove() {
            put(KEY, new OwnedValue("owner", new StubEpoch(5, 0)));
            removed.clear();

            removeWithWitness(KEY, new OwnedValue("deposed", new StubEpoch(2, 0)));

            assertThat(removed)
                .as("a stale-epoch remove mutates nothing and must not be observed by subscribers")
                .isEmpty();
        }

        @Test
        void acceptedRemove_emitsValueRemoveWithOldValue() {
            put(KEY, new OwnedValue("owner", new StubEpoch(3, 0)));
            removed.clear();

            removeWithWitness(KEY, new OwnedValue("owner", new StubEpoch(3, 0)));

            assertThat(removed).containsExactly(Option.some(new OwnedValue("owner", new StubEpoch(3, 0))));
        }
    }

    /// Design B (#379, hardened): a `Remove` of a key whose COMMITTED value is fenced is admitted
    /// ONLY by a present, matching-kind, current witness — a witnessless or wrong-typed delete of a
    /// fenced key is REJECTED, closing the bare-`Remove(key)` bypass. Non-fenced keys still delete
    /// freely with no witness, preserving the 40+ existing unfenced removers (none target a fenced key).
    @Nested
    class WitnessRequiredForFencedKeys {
        @Test
        void witnesslessRemove_ofFencedKey_rejected() {
            put(KEY, new OwnedValue("owner", new StubEpoch(5, 0)));
            removed.clear();

            removeUnfenced(KEY);

            assertThat(stored(KEY))
                .as("a fenced key cannot be deleted by a bare Remove(key) — no witness, no authority")
                .isEqualTo(new OwnedValue("owner", new StubEpoch(5, 0)));
            assertThat(removed)
                .as("a rejected witnessless delete mutates nothing and emits no ValueRemove")
                .isEmpty();
        }

        @Test
        void wrongTypeWitness_onEpochBearingKey_rejected() {
            put(KEY, new OwnedValue("owner", new StubEpoch(5, 0)));

            removeWithWitness(KEY, leaderValue(NodeId.nodeId("x").unwrap(), 9L));

            assertThat(stored(KEY))
                .as("a LeaderValue witness is the wrong kind for an EpochBearing-valued key — rejected")
                .isEqualTo(new OwnedValue("owner", new StubEpoch(5, 0)));
        }

        @Test
        void nonFencedCommitted_witnesslessRemove_applied() {
            put(KEY, new PlainValue("lock"));

            removeUnfenced(KEY);

            assertThat(stored(KEY))
                .as("a non-fenced committed value deletes freely with no witness — the common case")
                .isNull();
        }

        @Test
        void nonFencedCommitted_removeApplied_witnessInert() {
            put(KEY, new PlainValue("lock"));

            removeWithWitness(KEY, new OwnedValue("someone", new StubEpoch(9, 0)));

            assertThat(stored(KEY))
                .as("a non-EpochBearing committed value is not fenced — any/no witness deletes it")
                .isNull();
        }
    }

    /// The H4 leader fence generalizes to `Remove` under Design B: the `LeaderKey` deletes only with a
    /// present `LeaderValue` witness whose `viewSequence` is strictly greater than committed; a stale,
    /// equal, witnessless, or wrong-typed delete is rejected.
    @Nested
    class LeaderRemoveFence {
        private static final NodeId NODE_A = NodeId.nodeId("node-a").unwrap();
        private static final NodeId NODE_B = NodeId.nodeId("node-b").unwrap();

        @Test
        void staleLeaderWitness_removeRejected() {
            put(LeaderKey.INSTANCE, leaderValue(NODE_A, 5L));

            removeWithWitness(LeaderKey.INSTANCE, leaderValue(NODE_B, 2L));

            assertThat(stored(LeaderKey.INSTANCE)).isEqualTo(leaderValue(NODE_A, 5L));
        }

        @Test
        void equalLeaderWitness_removeRejected_mirrorsPutStaleOrEqual() {
            put(LeaderKey.INSTANCE, leaderValue(NODE_A, 3L));

            removeWithWitness(LeaderKey.INSTANCE, leaderValue(NODE_B, 3L));

            assertThat(stored(LeaderKey.INSTANCE))
                .as("LeaderValue keeps stale-or-equal rejection on delete too")
                .isEqualTo(leaderValue(NODE_A, 3L));
        }

        @Test
        void strictlyGreaterLeaderWitness_removeApplied() {
            put(LeaderKey.INSTANCE, leaderValue(NODE_A, 3L));

            removeWithWitness(LeaderKey.INSTANCE, leaderValue(NODE_B, 4L));

            assertThat(stored(LeaderKey.INSTANCE)).isNull();
        }

        @Test
        void witnesslessLeaderRemove_rejected() {
            put(LeaderKey.INSTANCE, leaderValue(NODE_A, 5L));

            removeUnfenced(LeaderKey.INSTANCE);

            assertThat(stored(LeaderKey.INSTANCE))
                .as("the LeaderKey cannot be deleted by a bare Remove(key)")
                .isEqualTo(leaderValue(NODE_A, 5L));
        }

        @Test
        void wrongTypeWitness_onLeaderKey_rejected() {
            put(LeaderKey.INSTANCE, leaderValue(NODE_A, 5L));

            removeWithWitness(LeaderKey.INSTANCE, new OwnedValue("bogus", new StubEpoch(9, 0)));

            assertThat(stored(LeaderKey.INSTANCE))
                .as("an EpochBearing witness is the wrong kind for the LeaderKey — rejected")
                .isEqualTo(leaderValue(NODE_A, 5L));
        }
    }

    @Nested
    class Determinism {
        @Test
        void sameInputs_sameDecision_twoIndependentReplicas() {
            var storeA = new KVStore<StructuredKey, Object>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
            var storeB = new KVStore<StructuredKey, Object>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());

            var script = List.<KVCommand<StructuredKey>>of(
                new Put<>(KEY, new OwnedValue("owner", new StubEpoch(5, 0))),
                new Remove<>(KEY, Option.<Object>some(new OwnedValue("deposed", new StubEpoch(2, 0)))),
                new Remove<>(KEY, Option.<Object>some(new OwnedValue("owner", new StubEpoch(5, 0)))));

            script.forEach(command -> applyToBoth(storeA, storeB, command));

            assertThat(storeA.get(KEY).or((Object) null))
                .as("two replicas applying the same command sequence reach identical state: reject-then-accept")
                .isEqualTo(storeB.get(KEY).or((Object) null))
                .isNull();
        }

        private static void applyToBoth(KVStore<StructuredKey, Object> a,
                                        KVStore<StructuredKey, Object> b,
                                        KVCommand<StructuredKey> command) {
            a.process(a.createBatch(List.of(command)));
            b.process(b.createBatch(List.of(command)));
        }
    }

    /// A `Remove` carrying a witness (and one without) must survive the codec round-trip so replicas
    /// agree on the delete after transport. Uses `LeaderValue` (codec-registered) as the witness.
    @Nested
    class CodecRoundTrip {
        private static final NodeId NODE_A = NodeId.nodeId("node-a").unwrap();

        private static SliceCodec buildCodec() {
            return SliceCodec.sliceCodec(frameworkCodecs(), new ArrayList<>(KvstoreCodecs.CODECS));
        }

        @Test
        void removeWithWitness_roundTrip_preservesKeyAndWitness() {
            var codec = buildCodec();
            var original = new Remove<>(LeaderKey.INSTANCE, Option.<Object>some(leaderValue(NODE_A, 7L)));
            var buffer = Unpooled.buffer();

            codec.write(buffer, original);
            var decoded = (Remove<?>) codec.read(buffer);

            assertThat(decoded.key()).isEqualTo(LeaderKey.INSTANCE);
            assertThat(decoded.witness()).isEqualTo(Option.some(leaderValue(NODE_A, 7L)));
        }

        @Test
        void unfencedRemove_roundTrip_preservesEmptyWitness() {
            var codec = buildCodec();
            var original = new Remove<>(LeaderKey.INSTANCE);
            var buffer = Unpooled.buffer();

            codec.write(buffer, original);
            var decoded = (Remove<?>) codec.read(buffer);

            assertThat(decoded.key()).isEqualTo(LeaderKey.INSTANCE);
            assertThat(decoded.witness()).isEqualTo(Option.none());
        }
    }
}
