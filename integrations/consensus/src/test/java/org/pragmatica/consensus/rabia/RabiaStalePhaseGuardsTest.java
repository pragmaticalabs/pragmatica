/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.consensus.rabia;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.consensus.rabia.RabiaEngineApplyContainmentTest.PoisonStateMachine;
import org.pragmatica.consensus.rabia.RabiaEngineApplyContainmentTest.TestClusterNetwork;
import org.pragmatica.consensus.rabia.RabiaEngineApplyContainmentTest.TestCommand;
import org.pragmatica.consensus.rabia.RabiaEngineApplyContainmentTest.TestTopologyManager;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Decision;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Propose;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.SyncResponse;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.VoteRound1;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.VoteRound2;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.serialization.SliceCodec;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #3512: the stale-phase guards, one effect per test. A ballot (Propose, VoteRound1, VoteRound2) for a slot this
/// replica has completed is answered by sending its sender that slot's completed Decision, and is not processed: the
/// slot's state is the same before and after. A Decision for a completed slot is not applied twice.
///
/// Two guards stand between a duplicate Decision and a second apply: the past-slot check in `handleDecision` and
/// `PhaseData.tryMarkDecided` in `commitDecision`. The past-slot guard is pinned alone here, by a duplicate Decision for
/// a slot whose data the cleanup sweep has removed: `tryMarkDecided` then runs on fresh data and cannot refuse it.
/// `tryMarkDecided` cannot be pinned alone through the engine: `commitDecision` runs only for the current slot, which then
/// advances, and the current slot never moves back except in `shutdownAndReset`, which also clears every slot's data. So
/// the past-slot guard always runs before it. Its own pin is `PhaseDataTest`, which marks the same slot twice.
///
/// No sleeps on the assertion path: the executor is quiesced with `settleForTesting()`; the only wait is a bounded poll
/// for the cleanup sweep, which runs on a timer.
class RabiaStalePhaseGuardsTest {
    private static final SliceCodec SERIALIZER =
        TestSerializers.stringCommandSerializer(TestCommand.class, TestCommand::value, TestCommand::new);
    private static final NodeId NODE_1 = nodeId("node-1").unwrap();
    private static final NodeId NODE_2 = nodeId("node-2").unwrap();
    private static final NodeId NODE_3 = nodeId("node-3").unwrap();
    private static final Phase FIRST = Phase.ZERO;
    private static final TimeSpan SETTLE = timeSpan(5).seconds();
    private static final long CLEANUP_DEADLINE_MILLIS = 5_000;

    private final RecordingNetwork network = new RecordingNetwork();
    private final PoisonStateMachine stateMachine = new PoisonStateMachine();
    private RabiaEngine<TestCommand> engine;

    /// What a slot's data holds for the lagging peer and for the slot as a whole.
    record SlotView(Map<NodeId, Batch<TestCommand>> proposals,
                    long round,
                    boolean peerVotedRound1,
                    boolean peerVotedRound2,
                    boolean decided,
                    Option<Decision<TestCommand>> completedDecision) {
        static SlotView slotView(PhaseData<TestCommand> data) {
            return new SlotView(Map.copyOf(data.proposals()),
                                data.round(),
                                data.hasVotedRound1(NODE_3),
                                data.hasVotedRound2(NODE_3),
                                data.isDecided(),
                                data.completedDecision());
        }
    }

    /// A send to one node, as the engine addressed it.
    record Sent(NodeId recipient, ProtocolMessage message) {
        static Sent sent(NodeId recipient, ProtocolMessage message) {
            return new Sent(recipient, message);
        }
    }

    /// Records the recipient of every point-to-point send, which the shared test network drops. Cleared right before a
    /// stale ballot is delivered, so only the replies to that ballot remain.
    static final class RecordingNetwork extends TestClusterNetwork {
        final List<Sent> sends = new CopyOnWriteArrayList<>();

        @Override
        public <M extends ProtocolMessage> Promise<WriteOutcome> send(NodeId nodeId, M message) {
            sends.add(Sent.sent(nodeId, message));

            return super.send(nodeId, message);
        }

        List<Decision<?>> decisionsSentTo(NodeId recipient) {
            return sends.stream()
                        .filter(sent -> sent.recipient().equals(recipient))
                        .map(Sent::message)
                        .filter(Decision.class::isInstance)
                        .<Decision<?>>map(Decision.class::cast)
                        .toList();
        }
    }

    @AfterEach
    void tearDown() {
        engine.stop().await();
    }

    @Test
    void staleProposal_isAnsweredWithItsSlotsDecision_andLeavesTheSlotUnchanged() {
        startEngine(ProtocolConfig.testConfig());
        var decided = commit(FIRST, "first");
        var before = slotView(FIRST);

        network.sends.clear();
        engine.processPropose(new Propose<>(NODE_3, FIRST, batch("late")));
        settle();

        assertRepairedWith(decided);
        assertThat(slotView(FIRST)).as("the stale proposal must not be registered in the completed slot").isEqualTo(before);
        assertThat(before.proposals()).as("CONTROL: the slot was decided without NODE_3's proposal").doesNotContainKey(NODE_3);
    }

    @Test
    void staleRound1Vote_isAnsweredWithItsSlotsDecision_andLeavesTheSlotUnchanged() {
        startEngine(ProtocolConfig.testConfig());
        var decided = commit(FIRST, "first");
        var before = slotView(FIRST);

        network.sends.clear();
        engine.processVoteRound1(new VoteRound1(NODE_3, FIRST, StateValue.V0));
        settle();

        assertRepairedWith(decided);
        assertThat(slotView(FIRST)).as("the stale round-1 vote must not be registered in the completed slot").isEqualTo(before);
        assertThat(before.peerVotedRound1()).as("CONTROL: NODE_3 had not voted round 1 in the slot").isFalse();
    }

    @Test
    void staleRound2Vote_isAnsweredWithItsSlotsDecision_andLeavesTheSlotUnchanged() {
        startEngine(ProtocolConfig.testConfig());
        var decided = commit(FIRST, "first");
        var before = slotView(FIRST);

        network.sends.clear();
        engine.processVoteRound2(new VoteRound2(NODE_3, FIRST, StateValue.V0));
        settle();

        assertRepairedWith(decided);
        assertThat(slotView(FIRST)).as("the stale round-2 vote must not be registered in the completed slot").isEqualTo(before);
        assertThat(before.peerVotedRound2()).as("CONTROL: NODE_3 had not voted round 2 in the slot").isFalse();
    }

    @Test
    void duplicateDecisionOfACleanedSlot_isNotAppliedTwice() {
        startEngine(ProtocolConfig.protocolConfig(timeSpan(50).millis(),
                                                  timeSpan(100).millis(),
                                                  1,
                                                  ProtocolConfig.DEFAULT_MAX_PENDING_BATCHES)
                                  .unwrap());
        var duplicate = commit(FIRST, "first");

        commit(new Phase(1), "second");
        commit(new Phase(2), "third");
        awaitSlotCleaned(FIRST);
        var applied = List.copyOf(stateMachine.appliedCommands);
        var current = engine.currentPhaseForTesting();

        engine.processDecision(duplicate);
        settle();

        assertThat(stateMachine.appliedCommands).as("the duplicate Decision must not apply its batch again")
                                                .containsExactlyElementsOf(applied);
        assertThat(applied).as("CONTROL: every slot applied once")
                           .extracting(TestCommand::value)
                           .containsExactly("first", "second", "third");
        assertThat(engine.phaseDataForTesting(FIRST)).as("the cleaned slot must not be re-created").isEmpty();
        assertThat(engine.currentPhaseForTesting()).isEqualTo(current);
    }

    private void startEngine(ProtocolConfig config) {
        engine = new RabiaEngine<>(new TestTopologyManager(NODE_1, 3), network, stateMachine, config);
        engine.clusterState(ClusterStateNotification.active());
        settle();
        engine.processSyncResponse(new SyncResponse<>(NODE_2, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
        engine.processSyncResponse(new SyncResponse<>(NODE_3, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
        settle();
    }

    /// Commits `value` in `phase` by a peer's Decision, so the slot holds no proposal or vote of NODE_3.
    private Decision<TestCommand> commit(Phase phase, String value) {
        var decision = new Decision<>(NODE_2, phase, StateValue.V1, batch(value));

        engine.processDecision(decision);
        settle();
        assertThat(engine.currentPhaseForTesting()).as("CONTROL: slot %s applied", phase).isEqualTo(phase.successor());

        return decision;
    }

    private void assertRepairedWith(Decision<TestCommand> decided) {
        assertThat(network.decisionsSentTo(NODE_3)).as("exactly the completed slot's Decision, sent to the stale sender")
                                                   .extracting(Decision::phase, Decision::stateValue, Decision::value)
                                                   .containsExactly(tuple(decided.phase(), decided.stateValue(), decided.value()));
    }

    private SlotView slotView(Phase phase) {
        return engine.phaseDataForTesting(phase)
                     .map(SlotView::slotView)
                     .unwrap();
    }

    private void awaitSlotCleaned(Phase phase) {
        var deadline = System.currentTimeMillis() + CLEANUP_DEADLINE_MILLIS;

        while (engine.phaseDataForTesting(phase).isPresent() && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(engine.phaseDataForTesting(phase)).as("CONTROL: the cleanup sweep removed slot %s", phase).isEmpty();
    }

    private void settle() {
        engine.settleForTesting().await(SETTLE).unwrap();
    }

    private static Batch<TestCommand> batch(String value) {
        return Batch.create(SERIALIZER, List.of(new TestCommand(value)));
    }
}
