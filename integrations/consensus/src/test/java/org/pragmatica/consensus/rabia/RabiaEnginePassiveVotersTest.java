// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.consensus.rabia;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestClusterNetwork;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestCommand;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestStateMachine;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestTopologyManager;
import org.pragmatica.consensus.rabia.RabiaPersistence.SavedState;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Decision;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Propose;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.SyncResponse;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.VoteRound1;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.VoteRound2;
import org.pragmatica.consensus.topology.ClusterStateNotification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;

/// #2543: a passive client (a worker) applies no §4 command and does no sync, so its roster stays at genesis unless a core reports the current
/// one. `installPassiveVoters` is the single door: passive-only, epoch-monotone, and it never touches phases, ballots or the observer flag.
class RabiaEnginePassiveVotersTest {
    private static final NodeId WORKER = nodeId("worker-1").unwrap();
    private static final NodeId CORE_1 = nodeId("core-1").unwrap();
    private static final NodeId CORE_2 = nodeId("core-2").unwrap();
    private static final NodeId CORE_3 = nodeId("core-3").unwrap();
    private static final NodeId CORE_4 = nodeId("core-4").unwrap();
    private static final NodeId CORE_5 = nodeId("core-5").unwrap();
    private static final Set<NodeId> GENESIS = Set.of(CORE_1, CORE_2, CORE_3);

    private final List<RabiaEngine<TestCommand>> engines = new CopyOnWriteArrayList<>();
    private final TestClusterNetwork network = new TestClusterNetwork();
    private final TestStateMachine machine = new TestStateMachine();

    @AfterEach
    void stopEngines() {
        engines.forEach(engine -> engine.stop().await());
    }

    private RabiaEngine<TestCommand> engine(NodeId self, boolean passive) {
        return engine(self, passive, true);
    }

    private RabiaEngine<TestCommand> engine(NodeId self, boolean passive, boolean gated) {
        var topology = new TestTopologyManager(self, 3) {
            @Override
            public Set<NodeId> coreNodes() {
                return GENESIS;
            }

            @Override
            public boolean isConsensusMember(NodeId id) {
                return GENESIS.contains(id);
            }
        };
        var created = new RabiaEngine<>(topology,
                                        network,
                                        machine,
                                        ProtocolConfig.consensusConfig(org.pragmatica.lang.io.TimeSpan.timeSpan(60).seconds(),
                                                                       org.pragmatica.lang.io.TimeSpan.timeSpan(60).seconds()),
                                        ConsensusMetrics.noop(),
                                        gated);

        engines.add(created);
        if (passive) {
            assertThat(created.configurePassiveClient().isSuccess()).as("precondition: the engine is a passive client").isTrue();
        }

        return created;
    }

    private static VoterConfiguration voters(long epoch, NodeId... members) {
        return VoterConfiguration.voterConfiguration(epoch, List.of(members)).unwrap();
    }

    private static long epochOf(RabiaEngine<?> engine) {
        return engine.voterConfiguration().map(VoterConfiguration::epoch).or(-1L);
    }

    @Test
    void installPassiveVoters_newerEpoch_installsAndNotifies() {
        var passive = engine(WORKER, true);
        var heard = new CopyOnWriteArrayList<VoterConfiguration>();
        var replaced = voters(2, CORE_1, CORE_4, CORE_5);

        passive.onVoterConfiguration(heard::add);
        heard.clear();

        assertThat(passive.installPassiveVoters(replaced).isSuccess()).isTrue();
        assertThat(passive.voterConfiguration().unwrap()).as("the reported electorate is installed").isEqualTo(replaced);
        assertThat(heard).as("the voter listeners (the quorum-loss detector's among them) hear it once").containsExactly(replaced);
    }

    @Test
    void installPassiveVoters_olderEpoch_ignored() {
        var passive = engine(WORKER, true);
        var heard = new CopyOnWriteArrayList<VoterConfiguration>();
        var newer = voters(3, CORE_1, CORE_4, CORE_5);

        passive.installPassiveVoters(newer);
        passive.onVoterConfiguration(heard::add);
        heard.clear();

        assertThat(passive.installPassiveVoters(voters(1, CORE_1, CORE_2, CORE_4)).isSuccess()).as("a lagging report is not an error").isTrue();
        assertThat(passive.voterConfiguration().unwrap()).as("the electorate never regresses").isEqualTo(newer);
        assertThat(heard).as("nothing was installed, nothing is announced").isEmpty();
    }

    /// A governor relays the leader's directory to its members, and a relayed directory can be strictly newer as a DIRECTORY (a higher
    /// generation, e.g. a changed node list) while carrying an electorate older than the one the worker already holds. The directory's
    /// version decides nothing here: only the voter configuration's own epoch does.
    @Test
    void installPassiveVoters_olderConfigInsideANewerRelayedDirectory_ignored() {
        var passive = engine(WORKER, true);
        var held = voters(3, CORE_1, CORE_4, CORE_5);
        var relayedOlder = voters(2, CORE_1, CORE_2, CORE_4);

        passive.installPassiveVoters(held);

        assertThat(passive.installPassiveVoters(relayedOlder).isSuccess()).isTrue();
        assertThat(passive.voterConfiguration().unwrap()).as("the relayed older electorate did not replace the held one").isEqualTo(held);
    }

    @Test
    void installPassiveVoters_equalEpochSameRoster_noop() {
        var passive = engine(WORKER, true);
        var heard = new CopyOnWriteArrayList<VoterConfiguration>();
        var same = voters(2, CORE_1, CORE_4, CORE_5);

        passive.installPassiveVoters(same);
        passive.onVoterConfiguration(heard::add);
        heard.clear();

        assertThat(passive.installPassiveVoters(same).isSuccess()).isTrue();
        assertThat(heard).as("the repeat of a directory that did not change the electorate announces nothing").isEmpty();
    }

    @Test
    void installPassiveVoters_equalEpochDifferentRoster_refused() {
        var passive = engine(WORKER, true);
        var held = voters(2, CORE_1, CORE_4, CORE_5);

        passive.installPassiveVoters(held);

        passive.installPassiveVoters(voters(2, CORE_1, CORE_2, CORE_4))
               .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("two rosters at one epoch must not both be accepted"))
               .onFailure(cause -> assertThat(cause).isEqualTo(ReconfigurationError.INCOMPATIBLE_EPOCH));
        assertThat(passive.voterConfiguration().unwrap()).as("the held electorate is kept").isEqualTo(held);
    }

    @Test
    void installPassiveVoters_nonPassiveEngine_refused() {
        var core = engine(CORE_1, false);
        var before = core.voterConfiguration().unwrap();

        core.installPassiveVoters(voters(5, CORE_1, CORE_4, CORE_5))
            .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("only a passive client takes a roster from a report"))
            .onFailure(cause -> assertThat(cause).isEqualTo(ReconfigurationError.NOT_PASSIVE_CLIENT));
        assertThat(core.voterConfiguration().unwrap()).as("a voting engine learns rosters from its log only").isEqualTo(before);
        assertThat(epochOf(core)).isZero();
    }

    @Test
    void installPassiveVoters_touchesNeitherTheObserverFlagNorTheEngineState() {
        var passive = engine(WORKER, true);
        var observingBefore = passive.isObserving();
        var activeBefore = passive.isActive();

        passive.installPassiveVoters(voters(4, CORE_1, CORE_4, CORE_5));

        assertThat(passive.isObserving()).isEqualTo(observingBefore);
        assertThat(passive.isActive()).isEqualTo(activeBefore).isFalse();
    }

    /// #2543 passivity, the main risk of the fix: an installed electorate must not turn a worker into a participant. The worker is driven
    /// with everything that moves a core engine (cluster ACTIVE, activation, a sync quorum, proposals, both ballot rounds and a decision from
    /// the members of its NEW electorate); it must send nothing, apply nothing, and stay neither active nor observing. Ungated, so that the
    /// only thing between these stimuli and participation is the passive-client fence. Mutation that goes red: `passiveClient = false` in
    /// `installReportedVoters`. The fence is layered: deleting any ONE of the `passiveClient` returns in `safeExecute`, `clusterState` or
    /// `authorizeActivation` leaves this green, because the others still stop these stimuli.
    @Test
    void installPassiveVoters_thenEveryCoreStimulus_theWorkerNeverParticipates() {
        var worker = engine(WORKER, true, false);
        var batch = Batch.create(machine.serializer(), List.of(new TestCommand("core-only")));

        assertThat(worker.installPassiveVoters(voters(2, CORE_1, CORE_4, CORE_5)).isSuccess()).as("precondition: the roster is installed").isTrue();
        worker.clusterState(ClusterStateNotification.active());
        worker.authorizeActivation();
        List.of(CORE_1, CORE_4, CORE_5).forEach(core -> {
            worker.processSyncResponse(new SyncResponse<>(core, SavedState.savedState(new byte[0], Phase.ZERO, List.of()), ResponderState.COLD));
            worker.processPropose(new Propose<>(core, Phase.ZERO, batch));
            worker.processVoteRound1(new VoteRound1(core, Phase.ZERO, StateValue.V1));
            worker.processVoteRound2(new VoteRound2(core, Phase.ZERO, StateValue.V1));
        });
        worker.processDecision(new Decision<>(CORE_4, Phase.ZERO, StateValue.V1, batch));
        worker.settleForTesting().await();
        worker.settleForTesting().await();

        assertThat(network.getMessages()).as("a worker never asks to sync, proposes, votes or relays a decision").isEmpty();
        assertThat(machine.getProcessedCommands()).as("a worker applies no decision itself; its state comes from the replica feed").isEmpty();
        assertThat(worker.isActive()).isFalse();
        assertThat(worker.isObserving()).isFalse();
        assertThat(worker.isPassiveClientReady()).as("the install does not mark the worker ready either").isFalse();
    }
}
