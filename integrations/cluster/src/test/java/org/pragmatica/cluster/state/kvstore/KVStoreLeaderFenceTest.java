package org.pragmatica.cluster.state.kvstore;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.SliceCodec;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.cluster.state.kvstore.LeaderValue.leaderValue;
import static org.pragmatica.serialization.FrameworkCodecs.frameworkCodecs;

/// H4 leader fence (cluster-topology-overhaul §Wave 8.2): `LeaderKey` writes are
/// compare-and-put inside the replicated applier — applied only when the incoming
/// `LeaderValue.viewSequence()` is strictly greater than the stored one. Exercises the applier
/// directly (deterministic — every replica decides identically), the no-notification-on-reject
/// invariant, and the codec/snapshot round-trip for the new `viewSequence` field.
class KVStoreLeaderFenceTest {
    private static final NodeId NODE_A = NodeId.nodeId("node-a").unwrap();
    private static final NodeId NODE_B = NodeId.nodeId("node-b").unwrap();

    private MessageRouter.MutableRouter router;
    private KVStore<LeaderKey, LeaderValue> store;
    private List<LeaderValue> notified;

    private static SliceCodec buildCodec() {
        return SliceCodec.sliceCodec(frameworkCodecs(), new ArrayList<>(KvstoreCodecs.CODECS));
    }

    @BeforeEach
    void setUp() {
        router = MessageRouter.mutable();
        var codec = buildCodec();
        store = new KVStore<>(router, codec, codec);
        notified = new ArrayList<>();
        router.addRoute(ValuePut.class,
                        (ValuePut<LeaderKey, LeaderValue> put) -> notified.add(put.cause().value()));
    }

    private void apply(LeaderValue value) {
        store.process(store.createBatch(List.of(new Put<>(LeaderKey.INSTANCE, value))));
    }

    private LeaderValue stored() {
        return store.get(LeaderKey.INSTANCE).unwrap();
    }

    @Nested
    class FenceSemantics {
        @Test
        void firstWrite_appliedWhenNoLeaderStored() {
            apply(leaderValue(NODE_A, 1L));

            assertThat(stored()).isEqualTo(leaderValue(NODE_A, 1L));
            assertThat(notified).containsExactly(leaderValue(NODE_A, 1L));
        }

        @Test
        void strictlyGreaterViewSequence_applied() {
            apply(leaderValue(NODE_A, 1L));
            apply(leaderValue(NODE_B, 2L));

            assertThat(stored()).isEqualTo(leaderValue(NODE_B, 2L));
        }

        @Test
        void equalViewSequence_rejected() {
            apply(leaderValue(NODE_A, 3L));
            apply(leaderValue(NODE_B, 3L));

            assertThat(stored())
                .as("an equal-sequence write is a concurrent/stale election — first commit wins")
                .isEqualTo(leaderValue(NODE_A, 3L));
        }

        @Test
        void lowerViewSequence_rejected() {
            apply(leaderValue(NODE_A, 5L));
            apply(leaderValue(NODE_B, 2L));

            assertThat(stored())
                .as("a minority-flapped node's stale election must not overwrite the leader")
                .isEqualTo(leaderValue(NODE_A, 5L));
        }

        @Test
        void rejectedWrite_emitsNoNotification() {
            apply(leaderValue(NODE_A, 5L));
            notified.clear();

            apply(leaderValue(NODE_B, 5L));
            apply(leaderValue(NODE_B, 1L));

            assertThat(notified)
                .as("a stale leader must never be observed by the election FSMs")
                .isEmpty();
        }
    }

    @Nested
    class SerializationRoundTrip {
        @Test
        void codecRoundTrip_preservesLeaderAndViewSequence() {
            var codec = buildCodec();
            var original = leaderValue(NODE_A, 42L);
            var buffer = Unpooled.buffer();

            codec.write(buffer, original);
            var decoded = (LeaderValue) codec.read(buffer);

            assertThat(decoded).isEqualTo(original);
            assertThat(decoded.viewSequence()).isEqualTo(42L);
        }

        @Test
        void snapshotRoundTrip_preservesViewSequence_andBypassesFence() {
            apply(leaderValue(NODE_A, 7L));
            var snapshot = store.makeSnapshot().unwrap();

            var freshCodec = buildCodec();
            var restoredStore = new KVStore<LeaderKey, LeaderValue>(MessageRouter.mutable(), freshCodec, freshCodec);

            restoredStore.restoreSnapshot(snapshot).unwrap();

            assertThat(restoredStore.get(LeaderKey.INSTANCE).unwrap()).isEqualTo(leaderValue(NODE_A, 7L));
        }
    }

    /// §5.8 (AMENDED) interaction with the H4 fence: a replayed `LeaderKey` notification is pure
    /// notification SYNTHESIS — it does NOT go through the apply path, so it neither mutates
    /// storage nor exercises the fence. Replay therefore cannot advance or reject the stored
    /// `viewSequence`; it only DELIVERS the already-committed value to subscribers.
    @Nested
    class ReplayInteractionWithFence {
        @Test
        void replay_isMutationFree_storageIdenticalBeforeAndAfter() {
            apply(leaderValue(NODE_A, 5L));
            var before = stored();

            store.replayNotifications();

            assertThat(stored())
                .as("replay is notification synthesis only — storage is untouched")
                .isEqualTo(before);
        }

        @Test
        void replay_doesNotExerciseFence_replayedLeaderKeyNeitherAdvancesNorRejects() {
            apply(leaderValue(NODE_A, 5L));
            notified.clear();

            // Replay fires a synthetic ValuePut for the stored LeaderKey. It must NOT pass through
            // handlePut (the fence) — so the stored sequence stays 5 and a notification IS observed
            // (delivery of world-as-of-N), distinct from a competing write which the fence would judge.
            store.replayNotifications();

            assertThat(stored().viewSequence())
                .as("replayed LeaderKey does not advance/regress the fenced sequence")
                .isEqualTo(5L);
            assertThat(notified)
                .as("replay DELIVERS the committed leader value to subscribers (world-as-of-N)")
                .containsExactly(leaderValue(NODE_A, 5L));
        }
    }
}
