package org.pragmatica.consensus.leader;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KvstoreCodecs;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderManager.FsmBackedLeaderManager;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.SliceCodec;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.serialization.FrameworkCodecs.frameworkCodecs;

/// H4 fence (cluster-topology-overhaul §Wave 8.2) — election-side baseline semantics:
/// `nextViewSequence()` is baseline-anchored (one above the highest COMMITTED sequence
/// observed, NOT a per-attempt counter) and the baseline advances only via observation
/// (max-merge). Retries therefore cannot inflate a flapped node's sequence past what the
/// majority committed, and peers proposing off the same observed commit produce identical
/// `LeaderValue` commands (BatchId dedup preserved).
class LeaderViewSequenceFenceTest {
    private final NodeId self = NodeId.nodeId("node-b").unwrap();

    private FsmBackedLeaderManager leaderManager;

    @BeforeEach
    void setUp() {
        leaderManager = (FsmBackedLeaderManager) LeaderManager.leaderManager(self, MessageRouter.mutable());
    }

    @Test
    void nextViewSequence_startsAtOne_andDoesNotIncrementPerCall() {
        assertThat(leaderManager.context().nextViewSequence()).isEqualTo(1L);
        assertThat(leaderManager.context().nextViewSequence())
            .as("retry attempts must NOT inflate the proposal sequence")
            .isEqualTo(1L);
    }

    @Test
    void observeViewSequence_advancesBaseline_nextProposalIsStrictlyAbove() {
        leaderManager.context().observeViewSequence(5L);

        assertThat(leaderManager.context().nextViewSequence()).isEqualTo(6L);
    }

    @Test
    void observeViewSequence_maxMerge_ignoresLowerOrDuplicateObservations() {
        leaderManager.context().observeViewSequence(5L);
        leaderManager.context().observeViewSequence(3L);
        leaderManager.context().observeViewSequence(5L);

        assertThat(leaderManager.context().nextViewSequence())
            .as("out-of-order or duplicate observations never regress the baseline")
            .isEqualTo(6L);
    }

    @Test
    void onLeaderCommitted_withViewSequence_seedsBaselineBeforeDispatch() {
        leaderManager.onLeaderCommitted(self, 9L);

        assertThat(leaderManager.context().nextViewSequence()).isEqualTo(10L);
    }

    /// §5.8 (AMENDED) subsume: the LeaderKey snapshot-replay re-fire special case is GONE. A
    /// node that synced a committed LeaderKey receives it as a REPLAYED `ValuePut` on the normal
    /// route; the route calls `onLeaderCommitted(leader, viewSequence)`, which seeds the H4
    /// baseline exactly as a live commit would. This test drives that end-to-end: a KVStore
    /// holding a committed LeaderKey, replayed → routed → baseline seeded.
    @Test
    void replayedLeaderKeyPut_seedsViewSequenceBaseline_viaNormalRoute() {
        var codec = SliceCodec.sliceCodec(frameworkCodecs(), new ArrayList<>(KvstoreCodecs.CODECS));
        var router = MessageRouter.mutable();
        var kvStore = new KVStore<LeaderKey, LeaderValue>(router, codec, codec);
        // The normal LeaderKey route (mirrors AetherNode.handleLeaderCommit) seeds the baseline.
        router.addRoute(ValuePut.class, (ValuePut<LeaderKey, LeaderValue> put) -> seedFromLeaderPut(put));

        // A committed leader at viewSequence=11 is in the synced store.
        kvStore.process(kvStore.createBatch(List.of(new KVCommand.Put<>(LeaderKey.INSTANCE,
                                                                        LeaderValue.leaderValue(self, 11L)))));
        // Replay delivers it as a synthetic put on the normal route.
        kvStore.replayNotifications();

        assertThat(leaderManager.context().nextViewSequence())
            .as("the replayed LeaderKey put seeds the H4 baseline through the normal onLeaderCommitted route")
            .isEqualTo(12L);
    }

    private void seedFromLeaderPut(ValuePut<LeaderKey, LeaderValue> put) {
        var value = put.cause().value();
        leaderManager.onLeaderCommitted(value.leader(), value.viewSequence());
    }
}
