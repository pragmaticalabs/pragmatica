package org.pragmatica.consensus.rabia;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestClusterNetwork;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestCommand;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestStateMachine;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestTopologyManager;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.GenesisAnnouncement;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.SyncResponse;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1554 — how a genesis-pending node joins a formed electorate (adapted from v1554's round-3 arms).
///
/// B is genesis-pending (configured count 3) with two other pending candidates X and Y.
class GenesisJoinRuleTest {
    private static final NodeId A = new NodeId("node-1");
    private static final NodeId B = new NodeId("node-2");
    private static final NodeId C = new NodeId("node-3");
    private static final NodeId X = new NodeId("node-4");
    private static final NodeId Y = new NodeId("node-5");
    private static final ClusterConfig PENDING_VIEW = new ClusterConfig(List.of(B, X, Y));
    private static final VoterConfiguration FORMED = new VoterConfiguration(1, new ClusterConfig(List.of(A, B, C)));
    private static final VoterConfiguration OLDER = new VoterConfiguration(0, new ClusterConfig(List.of(A, B, C)));

    private final TestClusterNetwork network = new TestClusterNetwork() {
        @Override
        public Set<NodeId> connectedPeers() {
            return Set.of(A, B, C, X, Y);
        }
    };
    private RabiaEngine<TestCommand> engine;

    @AfterEach
    void stop() {
        if (engine != null) {
            engine.stop().await();
        }
    }

    /// R1: a formed member answers B, then X's and Y's announcements complete the view agreement BEFORE
    /// B's next genesis round. A node that has seen a formed electorate joins it; it never forms a second.
    @Test
    void completeGenesis_afterAFormedElectorateWasSeen_joinsItInsteadOfFormingEpochZero() {
        engine = pendingB(Set.of(B, X, Y));
        engine.runGenesisRoundForTesting();
        engine.runGenesisRoundForTesting();
        settle();
        engine.genesisAnnouncement(new GenesisAnnouncement(A, 0, Option.some(FORMED.roster()), Option.some(FORMED)));
        settle();
        announceStably(X, Y);
        settle();

        assertThat(engine.voterConfiguration()).as("B saw a formed electorate; it must not form a second one")
                                               .isEqualTo(Option.some(FORMED));
    }

    /// Control for the arm above: with no formed answer the same schedule completes the agreement, so a
    /// red above is the join rule, not a harness that cannot reach agreement.
    @Test
    void completeGenesis_withNoFormedElectorateSeen_formsTheAgreedView() {
        engine = pendingB(Set.of(B, X, Y));
        engine.runGenesisRoundForTesting();
        engine.runGenesisRoundForTesting();
        settle();
        announceStably(X, Y);
        settle();

        assertThat(engine.voterConfiguration()).isEqualTo(Option.some(new VoterConfiguration(0, PENDING_VIEW)));
    }

    /// J2 at unit level: a LIVE sync response carrying the newer configuration outranks a lagging member's
    /// older `formed` answer arriving after it in the same round.
    @Test
    void genesisRound_liveSyncResponseWithNewerConfiguration_beatsALaterOlderFormedAnswer() {
        engine = pendingB(Set.of(B));
        engine.processSyncResponse(new SyncResponse<>(A,
                                                      new RabiaPersistence.SavedState<>(new byte[0],
                                                                                        Phase.phase(7),
                                                                                        List.of(),
                                                                                        Option.some(FORMED)),
                                                      ResponderState.LIVE));
        engine.genesisAnnouncement(new GenesisAnnouncement(C, 0, Option.some(OLDER.roster()), Option.some(OLDER)));
        settle();
        engine.runGenesisRoundForTesting();
        settle();

        assertThat(engine.voterConfiguration()).isEqualTo(Option.some(FORMED));
    }

    /// R2: two different rosters at one epoch (only two epoch-0 electorates, the documented residual). Every
    /// observer picks the same one — the lexicographically lowest member list — whatever the arrival order.
    @Test
    void genesisRound_equalEpochDifferentRosters_picksTheLowestMemberListInEitherArrivalOrder() {
        var lower = new VoterConfiguration(0, new ClusterConfig(List.of(A, B, C)));
        var higher = new VoterConfiguration(0, new ClusterConfig(List.of(B, X, Y)));

        for (var lowerFirst : List.of(true, false)) {
            engine = pendingB(Set.of(B));
            var lowerAnswer = new GenesisAnnouncement(A, 0, Option.some(lower.roster()), Option.some(lower));
            var higherAnswer = new GenesisAnnouncement(X, 0, Option.some(higher.roster()), Option.some(higher));

            engine.genesisAnnouncement(lowerFirst ? lowerAnswer : higherAnswer);
            engine.genesisAnnouncement(lowerFirst ? higherAnswer : lowerAnswer);
            settle();
            engine.runGenesisRoundForTesting();
            settle();

            assertThat(engine.voterConfiguration()).as("lowerFirst=%s", lowerFirst).isEqualTo(Option.some(lower));
            engine.stop().await();
            engine = null;
        }
    }

    private void announceStably(NodeId... senders) {
        for (long round = 1; round <= 2; round++) {
            for (var sender : senders) {
                engine.genesisAnnouncement(new GenesisAnnouncement(sender, round, Option.some(PENDING_VIEW), Option.none()));
            }
        }
    }

    private RabiaEngine<TestCommand> pendingB(Set<NodeId> discovered) {
        var created = new RabiaEngine<>(new TestTopologyManager(B, 5),
                                        network,
                                        new TestStateMachine(),
                                        ProtocolConfig.consensusConfig(timeSpan(60).seconds(), timeSpan(60).seconds()),
                                        ConsensusMetrics.noop(),
                                        false,
                                        RabiaPersistence.<TestCommand>inMemory());

        assertThat(created.deferGenesis(() -> discovered, 3, Option.none(), Set.of(A, B, C)).isSuccess()).isTrue();
        created.clusterState(ClusterStateNotification.active());

        return created;
    }

    private void settle() {
        engine.settleForTesting().await();
        engine.settleForTesting().await();
    }
}
