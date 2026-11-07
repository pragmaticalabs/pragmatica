package org.pragmatica.consensus.rabia;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestClusterNetwork;
import org.pragmatica.consensus.rabia.ScheduledCluster.Delivery;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestCommand;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestStateMachine;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestTopologyManager;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.*;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.SyncRequest;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.RoundRequest;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.*;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.testlogging.LogCapture;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Executes the real engines with reproducible message reordering and duplication.
class RabiaReorderedDeliveryTest {
    private final List<ScheduledCluster> clusters = new ArrayList<>();

    @AfterEach
    void stopClusters() {
        clusters.forEach(ScheduledCluster::stop);
    }

    @org.junit.jupiter.api.RepeatedTest(20)
    void conflictingProposalsConvergeToIdenticalLogsAcrossFairSchedules() {
        for (int size : List.of(3, 5)) {
            for (int seed = 0; seed < 12; seed++) {
                runSchedule(size, seed);
            }
        }
    }

    @Test
    void proposalDeliveryRepairsDisjointPendingQueuesWithoutNewBatchRetransmission() {
        for (int seed = 0; seed < 6; seed++) {
            var cluster = new ScheduledCluster(3, seed);
            clusters.add(cluster);
            cluster.start();
            for (int index = 0; index < cluster.engines.size(); index++) {
                var batch = Batch.create(cluster.machines.get(index).serializer(), List.of(new TestCommand("isolated-" + index)));
                cluster.engines.get(index).handleNewBatch(new NewBatch<>(cluster.members.get(index), batch));
            }
            cluster.settle();
            // Only Propose/ballot/Decision traffic is delivered. No voter ever receives another
            // NewBatch; a protocol advancing empty slots forever must fail this application gate.
            cluster.pumpUntil(() -> cluster.machines.stream().allMatch(machine -> machine.getProcessedCommands().size() == 3));
            cluster.pumpUntil(() -> cluster.pending.isEmpty() && cluster.emitted.isEmpty());
            cluster.verifyPrefixes();
            cluster.machines.forEach(machine -> assertThat(machine.getProcessedCommands()).hasSize(3).doesNotHaveDuplicates());
        }
    }

    /// #2011: a voter that learns a slot's Decision before the originator's NewBatch arrives used to put the
    /// committed batch back into its queue; it was proposed and committed a second time on every voter.
    @Test
    void newBatchDeliveredAfterItsDecision_isNotCommittedASecondTime() {
        var cluster = new ScheduledCluster(3, 0);
        clusters.add(cluster);
        cluster.start();
        var batch = Batch.create(cluster.machines.getFirst().serializer(), List.of(new TestCommand("acked-once")));
        cluster.engines.forEach(engine -> engine.handleNewBatch(new NewBatch<>(cluster.members.getFirst(), batch)));
        cluster.pumpUntil(() -> cluster.machines.stream().allMatch(machine -> machine.getProcessedCommands().size() == 1));
        cluster.settle();

        cluster.engines.forEach(engine -> engine.handleNewBatch(new NewBatch<>(cluster.members.getFirst(), batch)));
        cluster.settle();
        cluster.pumpUntil(() -> cluster.pending.isEmpty() && cluster.emitted.isEmpty());
        cluster.settle();

        cluster.machines.forEach(machine -> assertThat(machine.getProcessedCommands()).as("applied exactly once")
                                                                                         .containsExactly(new TestCommand("acked-once")));
        cluster.engines.forEach(engine -> assertThat(engine.pendingBatchCountForTesting()).as("the stale delivery is not queued").isZero());
    }

    /// #2045: a slot strips only the correlation ids its Decision carries, and that must never let ONE submission apply
    /// twice. After the commit, its NewBatch reaches every voter again and its proposal is replayed to every voter in the
    /// next slot, under reordered and duplicated delivery: applied exactly once everywhere, caller answered, nothing pending.
    @Test
    @SuppressWarnings("unchecked")
    void singleSubmission_lateNewBatchAndReplayedProposal_isAppliedOnce() {
        for (int seed = 0; seed < 6; seed++) {
            var cluster = new ScheduledCluster(3, seed);
            clusters.add(cluster);
            cluster.start();
            var command = new TestCommand("single-submission-" + seed);
            var answer = cluster.engines.getFirst().apply(List.of(command));
            cluster.pumpUntil(() -> answer.isResolved()
                                    && cluster.machines.stream().allMatch(machine -> !machine.getProcessedCommands().isEmpty()));
            cluster.settle();
            var batch = cluster.sent.stream()
                                    .filter(message -> message instanceof Propose<?> propose && propose.value().isNotEmpty())
                                    .map(message -> ((Propose<TestCommand>) message).value())
                                    .findFirst()
                                    .orElseThrow();
            for (int index = 0; index < cluster.engines.size(); index++) {
                var engine = cluster.engines.get(index);
                engine.handleNewBatch(new NewBatch<>(cluster.members.getFirst(), batch));
                engine.processPropose(new Propose<>(cluster.members.get((index + 1) % 3), engine.currentPhaseForTesting(), batch));
            }
            cluster.pumpUntil(() -> cluster.pending.isEmpty() && cluster.emitted.isEmpty());
            cluster.settle();

            assertThat(answer.await(timeSpan(3).seconds()).isSuccess()).as("seed %s", seed).isTrue();
            cluster.machines.forEach(machine -> assertThat(machine.getProcessedCommands()).containsExactly(command));
            cluster.engines.forEach(engine -> assertThat(engine.pendingBatchCountForTesting()).isZero());
            cluster.stop();
        }
    }

    /// The guard must not swallow a legitimate later submission of identical commands: it has a new correlation id.
    @Test
    void identicalCommandsSubmittedAgainAfterCommit_commitAgain() {
        var cluster = new ScheduledCluster(3, 0);
        clusters.add(cluster);
        cluster.start();
        cluster.allowIdenticalCommands = true;
        var commands = List.of(new TestCommand("same-commands"));
        var first = cluster.engines.getFirst().apply(commands);
        cluster.pumpUntil(first::isResolved);
        cluster.settle();

        var second = cluster.engines.getFirst().apply(commands);
        cluster.pumpUntil(() -> second.isResolved() && cluster.machines.stream().allMatch(machine -> machine.getProcessedCommands().size() >= 2));
        cluster.settle();

        assertThat(second.await(timeSpan(3).seconds()).isSuccess()).isTrue();
        cluster.machines.forEach(machine -> assertThat(machine.getProcessedCommands()).hasSize(2));
    }

    /// The Propose path (`learnProposedBatch`) is guarded too: a proposal carrying an already-committed batch does not
    /// re-enter the queue.
    @Test
    void proposalCarryingACommittedBatch_isNotRequeued() {
        var cluster = new ScheduledCluster(3, 0);
        clusters.add(cluster);
        cluster.start();
        var batch = Batch.create(cluster.machines.getFirst().serializer(), List.of(new TestCommand("committed-then-proposed")));
        cluster.engines.forEach(engine -> engine.handleNewBatch(new NewBatch<>(cluster.members.getFirst(), batch)));
        cluster.pumpUntil(() -> cluster.machines.stream().allMatch(machine -> machine.getProcessedCommands().size() == 1));
        cluster.settle();
        var engine = cluster.engines.get(2);

        engine.processPropose(new Propose<>(cluster.members.get(1), engine.currentPhaseForTesting(), batch));
        cluster.settle();

        assertThat(engine.pendingBatchCountForTesting()).isZero();
    }

    /// The Decision's batch carries correlation ids (c-decision) the committing voter never merged, while the voter's own
    /// pending batch carries another (c-local). #2045: `commitChanges` records only c-decision, the id the applied log
    /// carries; c-local is a separate submission and stays pending as a request of its own.
    private static final class MergedVsDecision {
        final ScheduledCluster cluster = new ScheduledCluster(3, 0);
        final Batch<TestCommand> local;
        final Batch<TestCommand> decided;
        final RabiaEngine<TestCommand> voter;

        MergedVsDecision(java.util.List<ScheduledCluster> owner) {
            owner.add(cluster);
            cluster.start();
            var commands = List.of(new TestCommand("local-vs-decision"));
            local = Batch.create(cluster.machines.getFirst().serializer(), commands);
            decided = Batch.create(cluster.machines.getFirst().serializer(), commands);
            voter = cluster.engines.getFirst();
            voter.handleNewBatch(new NewBatch<>(cluster.members.get(2), local));
            voter.processDecision(new Decision<>(cluster.members.get(1), voter.currentPhaseForTesting(), StateValue.V1, decided));
            cluster.settle();
        }
    }

    @Test
    void localCorrelationIdsNotInTheDecision_stayPending_andApplyInASlotOfTheirOwn() {
        var scenario = new MergedVsDecision(clusters);

        assertThat(scenario.cluster.machines.getFirst().getProcessedCommands()).as("CONTROL: the decision was applied").hasSize(1);
        assertThat(scenario.voter.pendingBatchCountForTesting()).as("c-local is still a request").isEqualTo(1);
        scenario.voter.handleNewBatch(new NewBatch<>(scenario.cluster.members.get(2), scenario.local));
        scenario.cluster.settle();
        assertThat(scenario.voter.pendingBatchCountForTesting()).as("a late copy of c-local merges, it is not a second request")
                                                                .isEqualTo(1);
        scenario.voter.processDecision(new Decision<>(scenario.cluster.members.get(1), scenario.voter.currentPhaseForTesting(),
                                                      StateValue.V1, scenario.local));
        scenario.cluster.settle();

        assertThat(scenario.cluster.machines.getFirst().getProcessedCommands()).as("once per submission").hasSize(2);
        assertThat(scenario.voter.pendingBatchCountForTesting()).isZero();
    }

    @Test
    void committedDecisionCorrelationIds_areRecorded_soTheirLateNewBatchIsNotRequeued() {
        var scenario = new MergedVsDecision(clusters);

        assertThat(scenario.cluster.machines.getFirst().getProcessedCommands()).as("CONTROL: the decision was applied").hasSize(1);
        scenario.voter.handleNewBatch(new NewBatch<>(scenario.cluster.members.get(1), scenario.decided));
        scenario.cluster.settle();
        // #2045: c-local is still pending as its own request. Committing it empties the queue only if the late copy of
        // c-decision was refused; a re-queued c-decision would be left behind.
        scenario.voter.processDecision(new Decision<>(scenario.cluster.members.get(1), scenario.voter.currentPhaseForTesting(),
                                                      StateValue.V1, scenario.local));
        scenario.cluster.settle();

        assertThat(scenario.voter.pendingBatchCountForTesting()).as("late copy of the decision's cid").isZero();
    }

    /// #2045 limit 2: a snapshot restore covers a slot this voter never committed, so its ledger has never seen that batch
    /// and the late NewBatch is queued. The voters that committed it refuse its proposal and answer `BatchCommitted`; the
    /// recovering voter records the ids and drops the batch, so it is applied once and nothing stays pending.
    @Test
    void snapshotSkippedSlot_lateNewBatchOfCoveredBatch_isNotRequeued() {
        var scenario = snapshotSkippedSlotThenLateNewBatch();
        var cluster = scenario.cluster();

        assertThat(scenario.recovering().pendingBatchCountForTesting()).as("CONTROL: the late delivery was queued").isEqualTo(1);
        cluster.pumpUntil(() -> cluster.engines.stream().allMatch(engine -> engine.pendingBatchCountForTesting() == 0)
                                && cluster.pending.isEmpty() && cluster.emitted.isEmpty());
        cluster.settle();
        List.of(1, 2).forEach(index -> assertThat(cluster.machines.get(index).getProcessedCommands()).as("voter %s", index)
                                                                                                     .containsExactly(scenario.covered()));
        scenario.recovering().handleNewBatch(new NewBatch<>(cluster.members.get(1), scenario.coveredBatch()));
        cluster.settle();

        assertThat(scenario.recovering().pendingBatchCountForTesting()).as("the answer was recorded in the ledger").isZero();
    }

    /// The answer is the only repair: with `BatchCommitted` blocked the recovering voter proposes the covered batch forever,
    /// every slot it holds it in decides empty, and the livelock counter and first-choice probe show it. Once delivered, the
    /// answer empties the queue and the probe reads 0. (The counter's reset rules are pinned decision by decision in
    /// `RabiaEngineSlowApplyDiagnosticsTest`; here the recovering voter may also adopt slots by snapshot, which never resets it.)
    @Test
    void snapshotSkippedSlot_withoutTheCommittedAnswer_spinsAndTheProbesShowIt() {
        var scenario = snapshotSkippedSlotThenLateNewBatch();
        var cluster = scenario.cluster();
        cluster.blocked = delivery -> delivery.message() instanceof BatchCommitted;

        // The slot rate under this schedule varies with executor interleaving (about 40 to 140 slots per 100k steps seen).
        assertThat(cluster.pumpBounded(() -> scenario.recovering().consecutiveEmptySlotsWithPending() > 20, PUMP_BUDGET))
            .as("empty slots while the covered batch stays queued").isNotNegative();
        assertThat(scenario.recovering().firstChoiceBatchId()).isEqualTo(Option.some(scenario.coveredBatch().id().id()));
        List.of(1, 2).forEach(index -> assertThat(cluster.engines.get(index).firstChoiceBatchId()).as("voter %s", index).isEqualTo(Option.none()));
        List.of(1, 2).forEach(index -> assertThat(cluster.machines.get(index).getProcessedCommands()).as("voter %s", index)
                                                                                                     .containsExactly(scenario.covered()));

        var next = Batch.create(cluster.machines.getFirst().serializer(), List.of(new TestCommand("agreed-meanwhile")));
        var voterOne = cluster.engines.get(1);
        var phaseBefore = voterOne.currentPhaseForTesting().value();
        voterOne.handleNewBatch(new NewBatch<>(cluster.members.get(1), next));
        cluster.settle();
        // settle() may let voter 1 decide a slot at once from votes it already holds, now with `next` pending, so the count
        // can legitimately be 1: it is bounded by the slots decided since `next` arrived, never by the earlier empty slots
        // (carried over, it would be above 20 here).
        var slotsSinceNext = voterOne.currentPhaseForTesting().value() - phaseBefore;
        assertThat(voterOne.consecutiveEmptySlotsWithPending())
            .as("voter 1 had nothing pending through the empty slots, so only slots decided since its batch arrived count")
            .isLessThanOrEqualTo(slotsSinceNext);

        cluster.blocked = _ -> false;
        cluster.pumpUntil(() -> scenario.recovering().pendingBatchCountForTesting() == 0);
        assertThat(scenario.recovering().consecutiveEmptySlotsWithPending()).as("nothing pending").isZero();
    }

    /// A local caller whose correlation id a peer reports committed cannot get the slot's result from this voter: it fails
    /// with an unknown outcome instead of waiting for an apply that will never happen here, and the queue drops the batch.
    @Test
    void batchCommittedFromATrustedPeer_dropsTheBatch_andTellsTheLocalCallerTheOutcomeIsUnknown() {
        var cluster = new ScheduledCluster(3, 0);
        clusters.add(cluster);
        cluster.start();
        var engine = cluster.engines.getFirst();
        var commands = List.of(new TestCommand("committed-elsewhere"));
        var answer = engine.apply(commands);
        cluster.settle();
        var batch = submittedBatch(cluster, commands);

        engine.handleBatchCommitted(new BatchCommitted(cluster.members.get(1), batch.id(), batch.correlationIds()));
        cluster.settle();

        assertThat(engine.pendingBatchCountForTesting()).isZero();
        var outcome = answer.await(timeSpan(3).seconds());
        assertThat(outcome.isFailure()).isTrue();
        outcome.onFailure(cause -> assertThat(cause).isInstanceOf(org.pragmatica.consensus.ConsensusError.CommittedOutcomeUnknown.class));
    }

    @Test
    void batchCommittedFromAnUntrustedSender_isIgnored() {
        var cluster = new ScheduledCluster(3, 0);
        clusters.add(cluster);
        cluster.start();
        var engine = cluster.engines.getFirst();
        var commands = List.of(new TestCommand("claimed-by-a-stranger"));
        var answer = engine.apply(commands);
        cluster.settle();
        var batch = submittedBatch(cluster, commands);

        engine.handleBatchCommitted(new BatchCommitted(nodeId("stranger").unwrap(), batch.id(), batch.correlationIds()));
        cluster.settle();

        assertThat(engine.pendingBatchCountForTesting()).isEqualTo(1);
        assertThat(answer.isResolved()).isFalse();
    }

    private record SnapshotSkip(ScheduledCluster cluster, RabiaEngine<TestCommand> recovering, Batch<TestCommand> coveredBatch,
                                TestCommand covered) {}

    private SnapshotSkip snapshotSkippedSlotThenLateNewBatch() {
        var cluster = new ScheduledCluster(3, 0);
        clusters.add(cluster);
        cluster.start();
        var recovering = cluster.engines.getFirst();
        var command = new TestCommand("covered-by-snapshot");
        var covered = Batch.create(cluster.machines.getFirst().serializer(), List.of(command));
        // Voters 1 and 2 commit the covered batch in slot 0; voter 0 never sees that slot.
        List.of(1, 2).forEach(index -> cluster.engines.get(index)
                                                      .processDecision(new Decision<>(cluster.members.get(3 - index), Phase.ZERO,
                                                                                      StateValue.V1, covered)));
        cluster.settle();
        recovering.processPropose(new Propose<>(cluster.members.get(1), Phase.phase(101), Batch.emptyBatch()));
        cluster.settle();
        var snapshot = cluster.machines.get(1).makeSnapshot().unwrap();
        recovering.processSyncResponse(new SyncResponse<>(cluster.members.get(1),
            RabiaPersistence.SavedState.savedState(snapshot, Phase.phase(1), List.of()), ResponderState.LIVE));
        recovering.processSyncResponse(new SyncResponse<>(cluster.members.get(2),
            RabiaPersistence.SavedState.savedState(snapshot, Phase.phase(1), List.of()), ResponderState.LIVE));
        cluster.settle();
        cluster.settle();
        assertThat(recovering.currentPhaseForTesting()).isEqualTo(Phase.phase(1));
        assertThat(recovering.pendingBatchCountForTesting()).as("CONTROL: nothing pending before the late delivery").isZero();
        recovering.handleNewBatch(new NewBatch<>(cluster.members.get(1), covered));
        cluster.settle();

        return new SnapshotSkip(cluster, recovering, covered, command);
    }

    /// #2045 limit 3 (know 32bd170946): voter-1 merges a concurrent identical submission c2 into X{c1}; the slot decides
    /// X{c1}, whose Decision carries only c1. c2 is a genuine second submission: it stays pending on voter 1, its late
    /// NewBatch reaches voters 0 and 2, and it applies once in a slot of its own. Two submissions, two applications, on
    /// every voter, and never a third.
    @Test
    void mergedIdenticalSubmission_lateNewBatch_appliesOncePerSubmission() {
        var cluster = mergedIdenticalSubmissionThenLateNewBatch();

        cluster.machines.forEach(machine -> assertThat(machine.getProcessedCommands()).as("once per submission").hasSize(2));
        cluster.engines.forEach(engine -> assertThat(engine.pendingBatchCountForTesting()).isZero());
    }

    private ScheduledCluster mergedIdenticalSubmissionThenLateNewBatch() {
        var cluster = new ScheduledCluster(3, 0);
        clusters.add(cluster);
        cluster.start();
        cluster.allowIdenticalCommands = true;
        var commands = List.of(new TestCommand("merged-on-one-voter"));
        var c1 = Batch.create(cluster.machines.getFirst().serializer(), commands);
        var c2 = Batch.create(cluster.machines.getFirst().serializer(), commands);
        assertThat(c2.id()).isEqualTo(c1.id());
        assertThat(c2.correlationIds()).isNotEqualTo(c1.correlationIds());
        cluster.engines.forEach(engine -> engine.handleNewBatch(new NewBatch<>(cluster.members.getFirst(), c1)));
        cluster.settle();
        cluster.engines.get(1).handleNewBatch(new NewBatch<>(cluster.members.get(1), c2));
        cluster.settle();
        cluster.pumpUntil(() -> cluster.machines.stream().allMatch(machine -> !machine.getProcessedCommands().isEmpty()));
        cluster.settle();
        cluster.engines.get(0).handleNewBatch(new NewBatch<>(cluster.members.get(1), c2));
        cluster.engines.get(2).handleNewBatch(new NewBatch<>(cluster.members.get(1), c2));
        cluster.settle();
        cluster.pumpUntil(() -> cluster.pending.isEmpty() && cluster.emitted.isEmpty());
        cluster.settle();

        return cluster;
    }

    /// About 50 deliveries per empty slot with four live voters: before #2045 the livelock decided about 1,900 empty slots in it.
    private static final int PUMP_BUDGET = 100_000;

    /// The #2045 empty-slot livelock (inv-flood-rootcause §4). Before the fix, voters 0 and 1 recorded the merged c2 as
    /// committed and refused X{c2}, voters 2 and 3 re-queued it and proposed it forever (it sorts first), 0 and 1 proposed
    /// H; 2-2 never reaches the quorum of 3, so every slot decided V0 and H was never applied (phase +1,867 in this budget).
    /// With the ledger a function of the applied log, all four hold X{c2} as a request of its own: it applies, then H.
    @Test
    void pendingHead_requeuedOnTwoOfFourLiveVoters_laterRequestStillCommits() {
        var scenario = pendingHeadRequeuedOnTwoOfFourLiveVoters();
        var steps = scenario.cluster.pumpBounded(scenario::laterRequestAppliedOnLiveVoters, PUMP_BUDGET);

        assertThat(steps).as("H applied on voters 0..3; phase advanced by %s", scenario.phaseAdvance()).isNotNegative();
        IntStream.range(0, 4).forEach(index -> assertThat(scenario.cluster.machines.get(index).getProcessedCommands())
            .as("voter %s: X once per submission, H once", index)
            .containsExactlyInAnyOrder(scenario.requeued(), scenario.requeued(), scenario.later()));
        IntStream.range(0, 4).forEach(index -> assertThat(scenario.cluster.engines.get(index).consecutiveEmptySlotsWithPending())
            .as("voter %s", index).isZero());
    }

    private record RequeuedHead(ScheduledCluster cluster, TestCommand requeued, TestCommand later, long startPhase) {
        boolean laterRequestAppliedOnLiveVoters() {
            return IntStream.range(0, 4).allMatch(index -> cluster.machines.get(index).getProcessedCommands().contains(later));
        }

        long phaseAdvance() {
            return cluster.engines.getFirst().currentPhaseForTesting().value() - startPhase;
        }
    }

    /// inv-flood-rootcause §4: X{c1} decided on voters 0..3; voters 0 and 1 had merged c2 before the commit, voters 2
    /// and 3 receive NewBatch X{c2} only after it. Then H, sorting above X, reaches voters 0..3. Today voters 0 and 1
    /// keep X{c2} pending too (it is not in the Decision); before #2045 they had dropped it as committed.
    private RequeuedHead pendingHeadRequeuedOnTwoOfFourLiveVoters() {
        var cluster = new ScheduledCluster(5, 0);
        clusters.add(cluster);
        cluster.start();
        cluster.allowIdenticalCommands = true;
        cluster.kill(4);
        var serializer = cluster.machines.getFirst().serializer();
        var commands = List.of(new TestCommand("requeued-head"));
        var c1 = Batch.create(serializer, commands);
        var c2 = Batch.create(serializer, commands);
        IntStream.range(0, 4).forEach(index -> cluster.engines.get(index).handleNewBatch(new NewBatch<>(cluster.members.getFirst(), c1)));
        IntStream.range(0, 2).forEach(index -> cluster.engines.get(index).handleNewBatch(new NewBatch<>(cluster.members.get(1), c2)));
        cluster.settle();
        var slot = cluster.engines.getFirst().currentPhaseForTesting();
        IntStream.range(0, 4).forEach(index -> cluster.engines.get(index)
                                                      .processDecision(new Decision<>(cluster.members.get(1), slot, StateValue.V1, c1)));
        cluster.settle();
        IntStream.range(2, 4).forEach(index -> cluster.engines.get(index).handleNewBatch(new NewBatch<>(cluster.members.get(1), c2)));
        cluster.settle();
        IntStream.range(0, 4).forEach(index -> assertThat(cluster.machines.get(index).getProcessedCommands())
            .as("CONTROL: X applied once on voter %s", index).containsExactlyElementsOf(commands));
        assertThat(IntStream.range(2, 4).map(index -> cluster.engines.get(index).pendingBatchCountForTesting()).toArray())
            .as("CONTROL: X{c2} pending where it arrived after the commit").containsExactly(1, 1);
        var later = sortingAbove(serializer, c1.id());
        var h = Batch.create(serializer, List.of(later));
        assertThat(h.id().compareTo(c1.id())).as("CONTROL: H sorts above X").isPositive();
        var startPhase = cluster.engines.getFirst().currentPhaseForTesting().value();
        IntStream.range(0, 4).forEach(index -> cluster.engines.get(index).handleNewBatch(new NewBatch<>(cluster.members.getFirst(), h)));

        return new RequeuedHead(cluster, commands.getFirst(), later, startPhase);
    }

    private static TestCommand sortingAbove(org.pragmatica.serialization.Serializer serializer, Batch.Id floor) {
        return IntStream.range(0, 1_000)
                        .mapToObj(index -> new TestCommand("later-" + index))
                        .filter(command -> Batch.create(serializer, List.of(command)).id().compareTo(floor) > 0)
                        .findFirst()
                        .orElseThrow();
    }

    /// The batch an `apply` broadcast, with its caller's correlation id: #2045 completes a caller only through a Decision
    /// that carries its id, so a Decision built from a fresh `Batch.create` (a new id) would answer nobody.
    @SuppressWarnings("unchecked")
    private static Batch<TestCommand> submittedBatch(ScheduledCluster cluster, List<TestCommand> commands) {
        return java.util.stream.Stream.of(cluster.emitted.stream(), cluster.parked.stream(), cluster.pending.stream())
                                      .flatMap(deliveries -> deliveries)
                                      .map(Delivery::message)
                                      .filter(message -> message instanceof NewBatch<?> newBatch && newBatch.batch().commands().equals(commands))
                                      .map(message -> ((NewBatch<TestCommand>) message).batch())
                                      .findFirst()
                                      .orElseThrow();
    }

    @Test
    void advancingSnapshotDoesNotReproposeCoveredRequestsAndReportsUnknownOutcome() {
        var cluster = new ScheduledCluster(3, 0);
        clusters.add(cluster);
        cluster.start();
        var recovering = cluster.engines.getFirst();
        var covered = List.of(new TestCommand("covered-by-snapshot"));
        var uncertain = recovering.apply(covered);
        var retained = List.of(new TestCommand("still-pending"));
        var retainedAnswer = recovering.apply(retained);
        cluster.settle();
        var committed = Batch.create(cluster.machines.getFirst().serializer(), covered);
        var pending = submittedBatch(cluster, retained);
        cluster.machines.get(1).process(committed);
        recovering.processPropose(new Propose<>(cluster.members.get(1), Phase.phase(101), Batch.emptyBatch()));
        cluster.settle();
        var snapshot = cluster.machines.get(1).makeSnapshot().unwrap();
        recovering.processSyncResponse(new SyncResponse<>(cluster.members.get(1),
            RabiaPersistence.SavedState.savedState(snapshot, Phase.phase(1), List.of(pending)), ResponderState.LIVE));
        recovering.processSyncResponse(new SyncResponse<>(cluster.members.get(2),
            RabiaPersistence.SavedState.savedState(snapshot, Phase.phase(1), List.of(pending)), ResponderState.LIVE));
        cluster.settle();
        cluster.settle();
        assertThat(recovering.currentPhaseForTesting()).isEqualTo(Phase.phase(1));
        assertThat(recovering.pendingBatchCountForTesting()).isEqualTo(1);
        var outcome = uncertain.await(timeSpan(3).seconds());
        assertThat(outcome.isFailure()).isTrue();
        outcome.onFailure(cause -> assertThat(cause)
            .isInstanceOf(org.pragmatica.consensus.ConsensusError.SnapshotOutcomeUnknown.class));
        assertThat(retainedAnswer.isResolved()).isFalse();
        recovering.processDecision(new Decision<>(cluster.members.get(1), Phase.phase(1), StateValue.V1, pending));
        cluster.settle();
        assertThat(retainedAnswer.await(timeSpan(3).seconds()).isSuccess()).isTrue();
        assertThat(cluster.machines.getFirst().getProcessedCommands()).containsExactlyElementsOf(
            java.util.stream.Stream.concat(covered.stream(), retained.stream()).toList());
        assertThat(recovering.pendingBatchCountForTesting()).isZero();
    }

    @Test
    void sameFrontierSnapshotPreservesLocalRequestsMissingFromPeerQueue() {
        var cluster = new ScheduledCluster(3, 0);
        clusters.add(cluster);
        cluster.start();
        var recovering = cluster.engines.getFirst();
        var commands = List.of(new TestCommand("local-only"));
        var answer = recovering.apply(commands);
        cluster.settle();
        recovering.processPropose(new Propose<>(cluster.members.get(1), Phase.phase(101), Batch.emptyBatch()));
        cluster.settle();
        recovering.processSyncResponse(new SyncResponse<>(cluster.members.get(1),
            RabiaPersistence.SavedState.savedState(new byte[0], Phase.ZERO, List.of()), ResponderState.LIVE));
        recovering.processSyncResponse(new SyncResponse<>(cluster.members.get(2),
            RabiaPersistence.SavedState.savedState(new byte[0], Phase.ZERO, List.of()), ResponderState.LIVE));
        cluster.settle();
        cluster.settle();
        assertThat(recovering.pendingBatchCountForTesting()).isEqualTo(1);
        assertThat(answer.isResolved()).isFalse();
        var batch = submittedBatch(cluster, commands);
        recovering.processDecision(new Decision<>(cluster.members.get(1), Phase.ZERO, StateValue.V1, batch));
        cluster.settle();
        assertThat(answer.await(timeSpan(3).seconds()).isSuccess()).isTrue();
        assertThat(cluster.machines.getFirst().getProcessedCommands()).containsExactlyElementsOf(commands);
    }

    /// #1526 acceptance 1 — the wedge #1390's certified handoff had once cores are in-memory.
    /// Voters {A,B,C}; C is dead; D replaces C; B dies once A has applied the change's slot R, and
    /// nothing B sent ever reaches D. Under §4, {A,B,D} governs from R+1, so A and D are a quorum and
    /// keep deciding; D learns R from A through slot repair. Red at S1's head, where D needs B's
    /// handoff transfer to certify and A is frozen awaiting it.
    @Test
    void replacementKeepsDecidingAfterTheLastOldPartnerDies() {
        for (int seed = 0; seed < 6; seed++) {
            var cluster = new ScheduledCluster(4, seed, 3);
            clusters.add(cluster);
            cluster.start();
            cluster.kill(2);
            cluster.blocked = delivery -> delivery.source() == 1 && delivery.target() == 3;
            var boundary = cluster.engines.getFirst().currentPhaseForTesting();
            var change = cluster.engines.getFirst().reconfigure(new ClusterConfig(List.of(cluster.members.get(0),
                                                                                         cluster.members.get(1),
                                                                                         cluster.members.get(3))));
            cluster.pumpUntil(() -> cluster.engines.getFirst().currentPhaseForTesting().compareTo(boundary) > 0);
            cluster.kill(1);
            var after = Batch.create(cluster.machines.getFirst().serializer(), List.of(new TestCommand("after-B-died")));
            cluster.engines.get(0).handleNewBatch(new NewBatch<>(cluster.members.get(0), after));
            cluster.engines.get(3).handleNewBatch(new NewBatch<>(cluster.members.get(0), after));
            cluster.pumpUntil(() -> List.of(0, 3).stream()
                                         .allMatch(index -> cluster.machines.get(index).getProcessedCommands()
                                                                   .contains(new TestCommand("after-B-died"))));
            assertThat(change.await(timeSpan(3).seconds()).isSuccess()).as("seed %s", seed).isTrue();
            assertThat(cluster.machines.get(0).getProcessedCommands()).containsExactlyElementsOf(cluster.machines.get(3).getProcessedCommands());
            cluster.stop();
        }
    }

    /// #1526 acceptance 2 — a change agreed at R governs from R+1 on EVERY replica, including one that
    /// applies R late, under reordered and duplicated delivery. Every ballot and decision any replica
    /// emits for a slot at or before R carries epoch 0 and every one after R carries epoch 1; logs stay
    /// prefix-identical throughout (checked on every pump step).
    ///
    /// A replica reaches epoch 1 one of two ways: it applies R itself (its status then reports R+1 as
    /// the effective slot), or a decision past a gap sends it to sync and it adopts a snapshot at or
    /// after R+1 (no effective slot of its own). Which way a randomized release takes depends on how the
    /// engines' executors interleave with the pump, so it is not a function of the seed (#1669). Even
    /// seeds therefore release the late replica's held traffic in emission order: a decider broadcasts
    /// R's Decision before it opens R+1, so the late replica meets R before any slot past it and must
    /// apply R itself. Odd seeds release it randomly, where either way is correct.
    @Test
    void agreedChangeGovernsFromTheNextSlotOnEveryReplicaIncludingALateOne() {
        for (int seed = 0; seed < 8; seed++) {
            var cluster = new ScheduledCluster(4, seed, 3);
            clusters.add(cluster);
            cluster.start();
            var before = Batch.create(cluster.machines.getFirst().serializer(), List.of(new TestCommand("before")));
            cluster.engines.subList(0, 3).forEach(engine -> engine.handleNewBatch(new NewBatch<>(cluster.members.getFirst(), before)));
            cluster.pumpUntil(() -> cluster.machines.stream().allMatch(machine -> machine.getProcessedCommands().size() == 1)
                                    && cluster.pending.isEmpty() && cluster.emitted.isEmpty());
            var boundary = cluster.engines.getFirst().currentPhaseForTesting();
            cluster.sent.clear();
            // v1 stays in the roster but applies R late: nothing reaches it until the new roster is deciding.
            cluster.held = delivery -> delivery.target() == 1;
            var target = new ClusterConfig(List.of(cluster.members.get(0), cluster.members.get(1), cluster.members.get(3)));
            var change = cluster.engines.getFirst().reconfigure(target);
            cluster.pumpUntil(change::isResolved);
            assertThat(change.await().isSuccess()).as("seed %s", seed).isTrue();
            var after = Batch.create(cluster.machines.getFirst().serializer(), List.of(new TestCommand("after")));
            cluster.engines.get(0).handleNewBatch(new NewBatch<>(cluster.members.getFirst(), after));
            cluster.engines.get(3).handleNewBatch(new NewBatch<>(cluster.members.getFirst(), after));
            cluster.pumpUntil(() -> List.of(0, 3).stream()
                                         .allMatch(index -> cluster.machines.get(index).getProcessedCommands().size() == 2));
            assertThat(cluster.engines.get(1).voterConfiguration().unwrap().epoch()).as("v1 is still late").isZero();
            var releasedInOrder = seed % 2 == 0;
            if (releasedInOrder) {
                cluster.releaseHeldInOrder();
            } else {
                cluster.held = _ -> false;
            }
            cluster.pumpUntil(() -> cluster.machines.stream().allMatch(machine -> machine.getProcessedCommands().size() == 2));
            cluster.pumpUntil(() -> cluster.engines.stream().allMatch(engine -> engine.voterConfiguration().unwrap().epoch() == 1));

            var effective = boundary.successor().value();
            for (var engine : cluster.engines) {
                assertThat(engine.voterConfiguration().unwrap()).isEqualTo(new VoterConfiguration(1, target));
                assertThat(engine.voterReconfigurationStatus().effectiveSlot().map(slot -> slot == effective).or(true))
                    .as("seed %s", seed).isTrue();
            }
            assertThat(cluster.engines.getFirst().voterReconfigurationStatus().effectiveSlot().unwrap()).isEqualTo(effective);
            if (releasedInOrder) {
                assertThat(cluster.engines.get(1).voterReconfigurationStatus().effectiveSlot())
                    .as("seed %s: the late replica applied R itself", seed).isEqualTo(Option.some(effective));
            }
            assertThat(cluster.engines.get(2).isActive()).as("the removed voter no longer votes").isFalse();
            assertThat(cluster.engines.get(2).isObserving()).isTrue();
            assertThat(cluster.sent).isNotEmpty();
            for (var message : cluster.sent) {
                assertThat(epochOf(message)).as("seed %s: %s", seed, message)
                                            .isEqualTo(phaseOf(message).compareTo(boundary) <= 0 ? 0L : 1L);
            }
            for (var machine : cluster.machines) {
                assertThat(machine.getProcessedCommands()).extracting(TestCommand::value).containsExactly("before", "after");
            }
            cluster.stop();
        }
    }

    /// #1526 genesis view agreement on real engines: a late core holds everyone (its absence keeps the
    /// view below the configured count), and the three form the cluster and decide once it appears.
    @Test
    void genesisWaitsForTheLateCoreAndFormsTheClusterWhenItArrives() {
        for (int seed = 0; seed < 4; seed++) {
            var cluster = new ScheduledCluster(3, seed, 3, timeSpan(100).millis());
            clusters.add(cluster);
            var visible = new java.util.concurrent.atomic.AtomicReference<>(Set.copyOf(cluster.members.subList(0, 2)));
            for (int index = 0; index < 3; index++) {
                var engine = cluster.engines.get(index);
                var own = cluster.members.get(index);
                assertThat(engine.deferGenesis(() -> index(visible.get(), own), 3, Option.none(), Set.copyOf(cluster.members)).isSuccess()).isTrue();
                engine.clusterState(ClusterStateNotification.active());
            }
            cluster.genesisRounds(List.of(0, 1), 6);
            assertThat(cluster.engines).as("the late core is outside every view: nobody forms").allMatch(RabiaEngine::isGenesisPending);
            assertThat(cluster.sent).as("no ballot while genesis is pending").isEmpty();

            visible.set(Set.copyOf(cluster.members));
            cluster.genesisRounds(List.of(0, 1, 2), 6);
            cluster.pumpUntil(() -> cluster.engines.stream().allMatch(RabiaEngine::isActive));
            var formed = Batch.create(cluster.machines.getFirst().serializer(), List.of(new TestCommand("after-late-core")));
            cluster.engines.forEach(engine -> engine.handleNewBatch(new NewBatch<>(cluster.members.getFirst(), formed)));
            cluster.pumpUntil(() -> cluster.machines.stream().allMatch(machine -> machine.getProcessedCommands().size() == 1));
            for (var engine : cluster.engines) {
                assertThat(engine.voterConfiguration().unwrap()).isEqualTo(new VoterConfiguration(0, new ClusterConfig(cluster.members)));
            }
            cluster.stop();
        }
    }

    /// #1526 genesis safety on real engines. Five cores, three configured, discovery split {A,B,C} and
    /// {C,D,E}: C's view merges to five, over the count, so it never starts, and without C neither side
    /// can agree a view of three. Nobody forms; every pending node reports EXCEEDS or keeps waiting.
    @Test
    void overlappingPartialViewsCannotFormTwoGeneses() {
        for (int seed = 0; seed < 4; seed++) {
            var cluster = new ScheduledCluster(5, seed, 5);
            clusters.add(cluster);
            var members = cluster.members;
            var left = Set.of(members.get(0), members.get(1), members.get(2));
            var right = Set.of(members.get(2), members.get(3), members.get(4));
            var views = List.of(left, left, Set.copyOf(members), right, right);
            for (int index = 0; index < 5; index++) {
                var view = views.get(index);
                assertThat(cluster.engines.get(index).deferGenesis(() -> view, 3, Option.none(), Set.of()).isSuccess()).isTrue();
            }
            cluster.genesisRounds(List.of(0, 1, 2, 3, 4), 10);

            assertThat(cluster.engines).as("seed %s: no epoch-0 configuration may form", seed).allMatch(RabiaEngine::isGenesisPending);
            cluster.stop();
        }
    }

    private static Set<NodeId> index(Set<NodeId> visible, NodeId own) {
        return visible.contains(own) ? visible : Set.of(own);
    }

    /// #1526 — after a decided reconfiguration the requester opens slot R+1 with an empty proposal, so
    /// the added member's first ballot past R arrives and the retirement gate clears with no other
    /// traffic at all.
    @Test
    void quietClusterClearsTheRetirementGateAfterAReplacement() {
        for (int seed = 0; seed < 6; seed++) {
            var cluster = new ScheduledCluster(4, seed, 3);
            clusters.add(cluster);
            cluster.start();
            var target = new ClusterConfig(List.of(cluster.members.get(0), cluster.members.get(1), cluster.members.get(3)));
            var change = cluster.engines.getFirst().reconfigure(target);
            cluster.pumpUntil(change::isResolved);
            assertThat(change.await().isSuccess()).isTrue();
            cluster.pumpUntil(() -> cluster.engines.getFirst().retirementSafeVoters().isPresent());
            assertThat(cluster.engines.getFirst().retirementSafeVoters().unwrap()).isEqualTo(new VoterConfiguration(1, target));
            assertThat(cluster.machines).allMatch(machine -> machine.getProcessedCommands().isEmpty());
            cluster.stop();
        }
    }

    @Test
    void growThenShrinkInstallsTwoEpochsAndGatesRetirementOnCatchUp() {
        for (int seed = 0; seed < 4; seed++) {
            var cluster = new ScheduledCluster(6, seed, 3);
            clusters.add(cluster);
            cluster.start();
            var grow = cluster.engines.getFirst().reconfigure(new ClusterConfig(cluster.members.subList(0, 5)));
            cluster.pumpUntil(grow::isResolved);
            assertThat(grow.await().isSuccess()).isTrue();
            cluster.pumpUntil(() -> cluster.engines.stream().allMatch(engine -> engine.voterConfiguration().unwrap().epoch() == 1));
            // Catch-up evidence is an added member's ballot past R, so it arrives with traffic: keep
            // committing until both added members have been seen voting in the new epoch.
            var committed = 0;
            while (cluster.engines.get(2).retirementSafeVoters().isEmpty()) {
                assertThat(committed).as("seed %s: added members never observed voting past R", seed).isLessThan(10);
                var next = Batch.create(cluster.machines.getFirst().serializer(), List.of(new TestCommand("after-grow-" + committed)));
                cluster.engines.subList(0, 5).forEach(engine -> engine.handleNewBatch(new NewBatch<>(cluster.members.getFirst(), next)));
                var expected = ++committed;
                cluster.pumpUntil(() -> cluster.machines.stream().allMatch(machine -> machine.getProcessedCommands().size() == expected)
                                        && cluster.pending.isEmpty() && cluster.emitted.isEmpty());
            }
            assertThat(cluster.engines.get(2).voterReconfigurationStatus().awaitingCatchUp()).isEmpty();
            var shrink = cluster.engines.get(2).reconfigure(new ClusterConfig(cluster.members.subList(2, 5)));
            cluster.pumpUntil(shrink::isResolved);
            assertThat(shrink.await().isSuccess()).as("shrink result %s; epoch %s; active %s", shrink.await(), cluster.engines.get(2).voterConfiguration(), cluster.engines.get(2).isActive()).isTrue();
            cluster.pumpUntil(() -> cluster.engines.stream().allMatch(engine -> engine.voterConfiguration().unwrap().epoch() == 2));
            assertThat(cluster.engines.get(2).retirementSafeVoters().unwrap().epoch()).isEqualTo(2);
            assertThat(cluster.engines.get(2).genesisVoters().unwrap().members()).containsExactlyElementsOf(cluster.members.subList(0, 3));
            var batch = Batch.create(cluster.machines.getFirst().serializer(), List.of(new TestCommand("after-two-epochs")));
            cluster.engines.subList(2, 5).forEach(engine -> engine.handleNewBatch(new NewBatch<>(cluster.members.get(2), batch)));
            var total = committed + 1;
            cluster.pumpUntil(() -> cluster.machines.stream().allMatch(machine -> machine.getProcessedCommands().size() == total));
            cluster.stop();
        }
    }

    /// #1683 path 1 — a replica that missed every message of slot P (it never proposes, votes or learns
    /// the decision) is repaired by the NEXT decision anywhere: Decision(P+1) is past a gap, so it is
    /// buffered and, once `decisionGapTimeout` (100 ms here) expires with P still missing, the replica resyncs.
    /// Mutation that reddens it: make `armDecisionGapTimer` return immediately — the replica stays at P and
    /// the schedule stalls.
    @Test
    void replicaThatMissedASlotCatchesUpFromTheNextDecision() {
        for (int seed = 0; seed < 6; seed++) {
            var cluster = new ScheduledCluster(3, seed, timeSpan(60).seconds(), timeSpan(100).millis());
            clusters.add(cluster);
            cluster.start();
            var slot = cluster.engines.getFirst().currentPhaseForTesting();
            cluster.held = delivery -> delivery.target() == 2 && isSlotBallot(delivery.message(), slot);
            commitOnFirstTwo(cluster, "first", 1);
            assertThat(cluster.engines.get(2).currentPhaseForTesting()).as("seed %s: the replica missed slot %s", seed, slot).isEqualTo(slot);
            assertThat(cluster.parked).as("seed %s: control — slot traffic to the replica really is withheld", seed)
                                      .anyMatch(delivery -> delivery.target() == 2 && delivery.message() instanceof Decision<?>);
            commitOnFirstTwo(cluster, "second", 2);
            cluster.pumpWithTimersUntil(() -> cluster.machines.get(2).getProcessedCommands().size() == 2
                                              && cluster.engines.get(2).isActive(),
                                        5_000);
            assertThat(cluster.machines.get(2).getProcessedCommands()).as("seed %s", seed)
                                                                       .containsExactlyElementsOf(cluster.machines.getFirst().getProcessedCommands());
            // The commands-applied predicate holds inside the task that also advances the phase; settle waits for it.
            cluster.settle();
            assertThat(cluster.engines.get(2).currentPhaseForTesting().compareTo(slot.successor())).as("seed %s", seed).isPositive();
            cluster.stop();
        }
    }

    /// #1683 quiet gap — the narrower defect the investigation found. A replica misses a whole slot and
    /// the cluster then goes QUIET: no later decision exists, the replica is Idle (no stall detector), and
    /// nobody sends it anything, so it stayed stale indefinitely while `isPendingCatchUp()` reported
    /// false. The idle slot probe asks the voters about its slot every `syncRetryInterval`, and a peer past
    /// it replays the decision. Mutation that reddens it: make `probeQuietSlot` return immediately (or do
    /// not arm it).
    @Test
    void quietClusterRepairsAReplicaThatMissedAWholeSlot() {
        for (int seed = 0; seed < 4; seed++) {
            var cluster = new ScheduledCluster(3, seed, 3, timeSpan(100).millis());
            clusters.add(cluster);
            cluster.start();
            var slot = cluster.engines.getFirst().currentPhaseForTesting();
            cluster.blocked = delivery -> delivery.target() == 2 && isSlotBallot(delivery.message(), slot);
            commitOnFirstTwo(cluster, "only", 1);
            var lagging = cluster.engines.get(2);
            assertThat(lagging.currentPhaseForTesting()).as("seed %s: the replica missed slot %s", seed, slot).isEqualTo(slot);
            assertThat(lagging.isPendingCatchUp()).as("seed %s: the gap the ticket found — stale, yet it reports caught up", seed).isFalse();
            cluster.blocked = _ -> false;
            cluster.pumpWithTimersUntil(() -> cluster.machines.get(2).getProcessedCommands().size() == 1
                                              && lagging.currentPhaseForTesting().equals(cluster.engines.getFirst().currentPhaseForTesting()),
                                        5_000);
            assertThat(cluster.machines.get(2).getProcessedCommands()).as("seed %s", seed)
                                                                       .containsExactlyElementsOf(cluster.machines.getFirst().getProcessedCommands());
            assertThat(lagging.isPendingCatchUp()).as("seed %s", seed).isFalse();
            cluster.stop();
        }
    }

    /// #1683 — a replica several slots behind in a quiet cluster. Answering its probe for P with P alone
    /// repairs one slot per request, and when P is already cleaned the SyncResponse fallback is ignored by
    /// an ACTIVE requester. A peer past P therefore also replays its own frontier decision, which the
    /// requester applies or resyncs from. The probe timer is inert here (60 s), so the one request below
    /// is the only repair traffic. Mutation that reddens it: drop `replayFrontierDecision` from
    /// `replayCompletedSlot` — only Decision(P) is replayed and the replica stops at P+1.
    @Test
    void pastSlotRepairAlsoReplaysTheFrontierDecision() {
        for (int seed = 0; seed < 4; seed++) {
            var cluster = new ScheduledCluster(3, seed, timeSpan(60).seconds(), timeSpan(100).millis());
            clusters.add(cluster);
            cluster.start();
            var slot = cluster.engines.getFirst().currentPhaseForTesting();
            var frontier = Phase.phase(slot.value() + 2);
            cluster.blocked = delivery -> delivery.target() == 2
                                          && isSlotBallot(delivery.message(), null)
                                          && phaseOf(delivery.message()).compareTo(frontier) <= 0;
            commitOnFirstTwo(cluster, "one", 1);
            commitOnFirstTwo(cluster, "two", 2);
            commitOnFirstTwo(cluster, "three", 3);
            cluster.pumpUntil(() -> cluster.pending.isEmpty() && cluster.emitted.isEmpty() && cluster.parked.isEmpty());
            assertThat(cluster.engines.get(2).currentPhaseForTesting()).as("seed %s", seed).isEqualTo(slot);
            cluster.blocked = _ -> false;

            var lagging = cluster.members.get(2);
            cluster.engines.getFirst().handleRoundRequest(new RoundRequest(lagging, 0, slot, 0));
            cluster.settle();
            cluster.collectEmitted();
            assertThat(cluster.parked.stream()
                                     .filter(delivery -> delivery.source() == 0 && delivery.target() == 2)
                                     .map(Delivery::message)
                                     .filter(Decision.class::isInstance)
                                     .map(message -> ((Decision<?>) message).phase())
                                     .toList())
                .as("seed %s: the repair replays the requested slot AND the peer's frontier", seed)
                .containsExactlyInAnyOrder(slot, frontier);
            cluster.pumpWithTimersUntil(() -> cluster.machines.get(2).getProcessedCommands().size() == 3 && cluster.engines.get(2).isActive(),
                                        5_000);
            assertThat(cluster.machines.get(2).getProcessedCommands()).as("seed %s", seed)
                                                                       .containsExactlyElementsOf(cluster.machines.getFirst().getProcessedCommands());
            cluster.stop();
        }
    }

    /// M4 — ordinary reordering: Decision(P+1) reaches a replica that is still at P, and Decision(P)
    /// arrives after it. #1390 answered ANY Decision past the current slot with a snapshot resync, which
    /// deposed the replica (`ConsensusPassive`, every leader-bound component cycled) for what is a
    /// routine reorder. The later Decision must wait buffered and apply, in order, the moment P does.
    /// The gap timeout is 60 s so only the in-order release can explain the outcome. Mutation that reddens
    /// it: replace `awaitMissingSlot(decision)` in the `comparison > 0` branch of `handleDecision` with
    /// `triggerResync()` — the replica leaves Active at once and emits `ConsensusPassive`.
    @Test
    void decisionPastAMissingSlotAppliesInOrderWithoutResync() {
        var cluster = new ScheduledCluster(3, 0, timeSpan(60).seconds(), timeSpan(60).seconds());
        clusters.add(cluster);
        cluster.start();
        var slot = cluster.engines.getFirst().currentPhaseForTesting();
        var decisions = withholdTwoSlotsFromTheThirdReplica(cluster, slot);
        var lagging = cluster.engines.get(2);

        lagging.processDecision(decisions.get(1));
        lagging.settleForTesting().await();
        assertThat(lagging.currentPhaseForTesting()).as("nothing applies across the missing slot").isEqualTo(slot);
        assertThat(lagging.isActive()).as("a reordered Decision is not a reason to leave Active").isTrue();

        lagging.processDecision(decisions.getFirst());
        cluster.pumpWithTimersUntil(() -> cluster.machines.get(2).getProcessedCommands().size() == 2, 5_000);
        assertThat(cluster.machines.get(2).getProcessedCommands()).containsExactlyElementsOf(cluster.machines.getFirst().getProcessedCommands());
        // The commands-applied predicate holds inside the task that also advances the phase; settle waits for it.
        cluster.settle();
        assertThat(lagging.currentPhaseForTesting()).isEqualTo(Phase.phase(slot.value() + 2));
        assertThat(lagging.isActive()).isTrue();
        assertThat(cluster.events.get(2)).as("the replica never published a passive edge")
                                         .noneMatch(ConsensusEvent.ConsensusPassive.class::isInstance);
    }

    /// M4 — the bound: the hazard #1390 fixed is a slot that is lost outright (#1683), so the wait for it
    /// must end. The missing Decision(P) is never delivered; the buffered Decision(P+1) waits out
    /// `decisionGapTimeout`, then the replica resyncs from a snapshot and converges. Mutation that
    /// reddens it: make `armDecisionGapTimer` return immediately — nothing ever ends the wait and the
    /// replica stays at P.
    @Test
    void missingSlotThatNeverArrivesEndsInResyncAfterTheGapTimeout() {
        var cluster = new ScheduledCluster(3, 0, timeSpan(60).seconds(), timeSpan(1).seconds());
        clusters.add(cluster);
        cluster.start();
        var slot = cluster.engines.getFirst().currentPhaseForTesting();
        var decisions = withholdTwoSlotsFromTheThirdReplica(cluster, slot);
        var lagging = cluster.engines.get(2);

        lagging.processDecision(decisions.get(1));
        lagging.settleForTesting().await();
        assertThat(lagging.isActive()).as("control: inside the timeout the replica keeps waiting").isTrue();
        assertThat(cluster.events.get(2)).noneMatch(ConsensusEvent.ConsensusPassive.class::isInstance);

        cluster.pumpWithTimersUntil(() -> cluster.machines.get(2).getProcessedCommands().size() == 2 && lagging.isActive(), 10_000);
        assertThat(cluster.machines.get(2).getProcessedCommands()).containsExactlyElementsOf(cluster.machines.getFirst().getProcessedCommands());
        assertThat(cluster.events.get(2)).as("the way back was a resync: Active left and re-entered")
                                         .anyMatch(ConsensusEvent.ConsensusPassive.class::isInstance);
    }

    /// M4 r1 — continuous reordering: every slot's Decision arrives after the next one, the gap always
    /// closes well inside the timeout, and the run outlasts several timeout periods. The gap timer fires
    /// mid-run with a Decision buffered and the applied prefix ahead of the slot it was armed for: that is
    /// progress, not a lost slot. Mutation that reddens it: delete the `!current.equals(missing)` progress
    /// branch of `decisionGapExpired` — the first expiry resyncs and emits `ConsensusPassive`.
    @Test
    void continuousReorderingNeverResyncsAcrossSeveralTimeoutPeriods() throws InterruptedException {
        var cluster = new ScheduledCluster(3, 0, timeSpan(60).seconds(), timeSpan(300).millis());
        clusters.add(cluster);
        cluster.start();
        var slot = cluster.engines.getFirst().currentPhaseForTesting();
        var decisions = withholdSlotsFromTheThirdReplica(cluster, slot, 50);
        var lagging = cluster.engines.get(2);
        var started = System.nanoTime();

        for (int index = 0; index < decisions.size(); index += 2) {
            lagging.processDecision(decisions.get(index + 1));
            Thread.sleep(25);
            lagging.processDecision(decisions.get(index));
        }
        cluster.pumpWithTimersUntil(() -> cluster.machines.get(2).getProcessedCommands().size() == 50, 5_000);
        assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
            .as("control: the run outlasted at least two timeout periods").isGreaterThan(600);
        assertThat(cluster.machines.get(2).getProcessedCommands()).containsExactlyElementsOf(cluster.machines.getFirst().getProcessedCommands());
        assertThat(lagging.isActive()).isTrue();
        assertThat(cluster.events.get(2)).noneMatch(ConsensusEvent.ConsensusPassive.class::isInstance);
    }

    /// M4 r1 — the re-arm: the timer armed for slot P sees P applied (progress) but a LATER slot P+2 is
    /// lost. Progress must arm a new wait for P+2, which then ends in resync. Mutation that reddens it:
    /// remove the `armDecisionGapTimer()` call in the progress branch of `decisionGapExpired` — nothing
    /// watches P+2 and the replica stalls there.
    @Test
    void slotLostAfterProgressIsWatchedByARearmedTimer() throws InterruptedException {
        var cluster = new ScheduledCluster(3, 0, timeSpan(60).seconds(), timeSpan(400).millis());
        clusters.add(cluster);
        cluster.start();
        var slot = cluster.engines.getFirst().currentPhaseForTesting();
        var decisions = withholdSlotsFromTheThirdReplica(cluster, slot, 4);
        var lagging = cluster.engines.get(2);

        lagging.processDecision(decisions.get(1));
        Thread.sleep(100);
        lagging.processDecision(decisions.getFirst());
        Thread.sleep(50);
        lagging.processDecision(decisions.get(3));
        cluster.pumpWithTimersUntil(() -> cluster.machines.get(2).getProcessedCommands().size() == 4 && lagging.isActive(), 5_000);
        assertThat(cluster.machines.get(2).getProcessedCommands()).containsExactlyElementsOf(cluster.machines.getFirst().getProcessedCommands());
        assertThat(cluster.events.get(2)).as("the way back was a resync").anyMatch(ConsensusEvent.ConsensusPassive.class::isInstance);
    }

    /// M4 r1 — the far-gap bound is exact: a Decision MAX_PHASE_AHEAD (100) slots ahead still waits, one
    /// slot further resyncs at once. The Decisions are real ones re-addressed to a far slot. Mutations:
    /// `gap > MAX_PHASE_AHEAD` -> `gap >= MAX_PHASE_AHEAD` reddens the first, `-> gap > 100_000` the second.
    @Test
    void decisionExactlyAtTheFarGapBoundWaits() {
        var cluster = new ScheduledCluster(3, 0, timeSpan(60).seconds(), timeSpan(60).seconds());
        clusters.add(cluster);
        cluster.start();
        var slot = cluster.engines.getFirst().currentPhaseForTesting();
        var lagging = cluster.engines.get(2);

        lagging.processDecision(readdressed(withholdTwoSlotsFromTheThirdReplica(cluster, slot).get(1), slot.value() + 100));
        lagging.settleForTesting().await();
        assertThat(lagging.isActive()).as("a gap of exactly 100 waits for the slot").isTrue();
    }

    @Test
    void decisionBeyondTheFarGapBoundResyncsAtOnce() {
        var cluster = new ScheduledCluster(3, 0, timeSpan(60).seconds(), timeSpan(60).seconds());
        clusters.add(cluster);
        cluster.start();
        var slot = cluster.engines.getFirst().currentPhaseForTesting();
        var lagging = cluster.engines.get(2);

        lagging.processDecision(readdressed(withholdTwoSlotsFromTheThirdReplica(cluster, slot).get(1), slot.value() + 101));
        lagging.settleForTesting().await();
        assertThat(lagging.isActive()).as("a gap of 101 resyncs without waiting for the timeout").isFalse();
        assertThat(cluster.events.get(2)).anyMatch(ConsensusEvent.ConsensusPassive.class::isInstance);
    }

    /// M4 r1 — a Decision buffered before a resync is not applied after it: the restored snapshot already
    /// contains its slot, so replay must drop it (`comparison < 0`) and leave the buffer empty. Mutations:
    /// delete the `comparison < 0` early return of `handleDecision` (the stale slot applies twice), or the
    /// `bufferedDecisions.clear()` in `drainBufferedDecisions` (the buffer keeps it).
    @Test
    void decisionBufferedBeforeAResyncIsNotReappliedAfterIt() {
        var cluster = new ScheduledCluster(3, 0, timeSpan(60).seconds(), timeSpan(300).millis());
        clusters.add(cluster);
        cluster.start();
        var slot = cluster.engines.getFirst().currentPhaseForTesting();
        var decisions = withholdTwoSlotsFromTheThirdReplica(cluster, slot);
        var lagging = cluster.engines.get(2);

        lagging.processDecision(decisions.get(1));
        cluster.pumpWithTimersUntil(() -> cluster.machines.get(2).getProcessedCommands().size() == 2 && lagging.isActive(), 5_000);
        cluster.settle();
        assertThat(cluster.events.get(2)).as("control: the catch-up was a resync").anyMatch(ConsensusEvent.ConsensusPassive.class::isInstance);
        assertThat(cluster.machines.get(2).getProcessedCommands()).containsExactlyElementsOf(cluster.machines.getFirst().getProcessedCommands())
                                                                   .doesNotHaveDuplicates();
        assertThat(lagging.bufferedDecisionCountForTesting()).isZero();
        assertThat(lagging.currentPhaseForTesting()).isEqualTo(cluster.engines.getFirst().currentPhaseForTesting());
    }

    /// M4 r1 — stop discards what was buffered and the pending wait: nothing is replayed into a restarted
    /// engine and no resync is attempted for a stopped one. Mutation: delete `bufferedDecisions.clear()` in
    /// `shutdownAndReset`.
    @Test
    void stopDiscardsBufferedDecisionsAndTheGapWait() throws InterruptedException {
        captureEngineWarnings();
        var cluster = new ScheduledCluster(3, 0, timeSpan(60).seconds(), timeSpan(200).millis());
        clusters.add(cluster);
        cluster.start();
        var slot = cluster.engines.getFirst().currentPhaseForTesting();
        var lagging = cluster.engines.get(2);

        lagging.processDecision(withholdTwoSlotsFromTheThirdReplica(cluster, slot).get(1));
        lagging.settleForTesting().await();
        assertThat(lagging.bufferedDecisionCountForTesting()).as("control: the Decision is buffered").isEqualTo(1);
        lagging.stop().await();
        Thread.sleep(500);
        assertThat(lagging.bufferedDecisionCountForTesting()).isZero();
        assertThat(engineWarnings()).noneMatch(message -> message.contains(STILL_MISSING));
    }

    /// M4 r1 — the expiry guard: a timer that fires while the engine is Paused (quorum lost; a sync round
    /// cannot succeed) must not resync. Mutation: drop `state.isPaused() ||` from the guard in
    /// `decisionGapExpired`.
    @Test
    void gapTimerFiringWhilePausedDoesNothing() throws InterruptedException {
        captureEngineWarnings();
        var cluster = new ScheduledCluster(3, 0, timeSpan(60).seconds(), timeSpan(300).millis());
        clusters.add(cluster);
        cluster.start();
        var slot = cluster.engines.getFirst().currentPhaseForTesting();
        var lagging = cluster.engines.get(2);

        lagging.processDecision(withholdTwoSlotsFromTheThirdReplica(cluster, slot).get(1));
        lagging.settleForTesting().await();
        assertThat(lagging.bufferedDecisionCountForTesting()).as("control: the gap wait is armed").isEqualTo(1);
        lagging.clusterState(org.pragmatica.consensus.topology.ClusterStateNotification.passive());
        lagging.settleForTesting().await();
        assertThat(lagging.isPaused()).as("control: paused before the timer fires").isTrue();
        Thread.sleep(700);
        assertThat(lagging.isPaused()).as("the timer left the paused engine alone").isTrue();
        assertThat(engineWarnings()).noneMatch(message -> message.contains(STILL_MISSING));
    }

    /// M4 r1 — the same guard for Syncing: the timer fires during a sync round that is already under way
    /// and must not report a second resync. Mutation: drop `state instanceof EngineState.Syncing` from the
    /// guard in `decisionGapExpired` (the extra `triggerResync()` is a no-op, the WARN is the tell).
    @Test
    void gapTimerFiringWhileSyncingDoesNothing() throws InterruptedException {
        captureEngineWarnings();
        var cluster = new ScheduledCluster(3, 0, timeSpan(60).seconds(), timeSpan(300).millis());
        clusters.add(cluster);
        cluster.start();
        var slot = cluster.engines.getFirst().currentPhaseForTesting();
        var decisions = withholdTwoSlotsFromTheThirdReplica(cluster, slot);
        var lagging = cluster.engines.get(2);

        lagging.processDecision(decisions.get(1));
        lagging.processDecision(readdressed(decisions.get(1), slot.value() + 101));
        lagging.settleForTesting().await();
        assertThat(lagging.isActive() || lagging.isPaused()).as("control: a sync round is under way").isFalse();
        Thread.sleep(700);
        assertThat(engineWarnings()).as("control: the far gap was reported").anyMatch(message -> message.contains("resyncing"));
        assertThat(engineWarnings()).noneMatch(message -> message.contains(STILL_MISSING));
    }

    private static Decision<TestCommand> readdressed(Decision<TestCommand> decision, long phase) {
        return new Decision<>(decision.sender(), decision.epoch(), Phase.phase(phase), decision.stateValue(), decision.value(), decision.reconfiguration());
    }

    private static final String STILL_MISSING = "still missing slot";
    private LogCapture engineWarningCapture;

    private void captureEngineWarnings() {
        engineWarningCapture = LogCapture.capture(RabiaEngine.class, org.apache.logging.log4j.Level.WARN, false);
    }

    @AfterEach
    void releaseEngineWarnings() {
        if (engineWarningCapture != null) {
            engineWarningCapture.close();
        }
    }

    private List<String> engineWarnings() {
        return engineWarningCapture.messages(org.apache.logging.log4j.Level.WARN);
    }

    /// Commits slots P and P+1 on the first two voters while everything the third would see of any slot
    /// is withheld, then returns the Decisions for P and P+1 that were addressed to it.
    private List<Decision<TestCommand>> withholdTwoSlotsFromTheThirdReplica(ScheduledCluster cluster, Phase slot) {
        return withholdSlotsFromTheThirdReplica(cluster, slot, 2);
    }

    /// As above for `count` consecutive slots from `slot`; element i is the Decision for slot + i.
    private List<Decision<TestCommand>> withholdSlotsFromTheThirdReplica(ScheduledCluster cluster, Phase slot, int count) {
        cluster.held = delivery -> delivery.target() == 2 && isSlotBallot(delivery.message(), null);
        for (int index = 1; index <= count; index++) {
            commitOnFirstTwo(cluster, "command-" + index, index);
        }
        cluster.settle();
        cluster.collectEmitted();
        assertThat(cluster.engines.get(2).currentPhaseForTesting()).as("control: the replica saw nothing").isEqualTo(slot);
        var decisions = IntStream.range(0, count).mapToObj(index -> decisionFor(cluster, Phase.phase(slot.value() + index))).toList();
        cluster.parked.removeIf(delivery -> delivery.target() == 2 && delivery.message() instanceof Decision<?>);
        return decisions;
    }

    @SuppressWarnings("unchecked")
    private static Decision<TestCommand> decisionFor(ScheduledCluster cluster, Phase phase) {
        return cluster.parked.stream()
                             .filter(delivery -> delivery.target() == 2 && delivery.message() instanceof Decision<?> decision && decision.phase().equals(phase))
                             .map(delivery -> (Decision<TestCommand>) delivery.message())
                             .findFirst()
                             .orElseThrow(() -> new AssertionError("control: no Decision for " + phase + " was addressed to the third replica"));
    }

    /// Submits one command to the first two voters only and pumps until both applied it.
    private static void commitOnFirstTwo(ScheduledCluster cluster, String value, int expected) {
        var batch = Batch.create(cluster.machines.getFirst().serializer(), List.of(new TestCommand(value)));
        cluster.engines.subList(0, 2).forEach(engine -> engine.handleNewBatch(new NewBatch<>(cluster.members.getFirst(), batch)));
        cluster.pumpUntil(() -> cluster.machines.subList(0, 2).stream().allMatch(machine -> machine.getProcessedCommands().size() == expected));
    }

    /// A Propose, ballot or Decision — for `slot` when it is given, for any slot otherwise.
    private static boolean isSlotBallot(ProtocolMessage message, Phase slot) {
        var ballot = message instanceof Propose<?> || message instanceof VoteRound1 || message instanceof VoteRound2
                     || message instanceof Decision<?>;
        return ballot && (slot == null || phaseOf(message).equals(slot));
    }

    private static long epochOf(ProtocolMessage message) {
        return switch (message) {
            case Propose<?> propose -> propose.epoch();
            case VoteRound1 vote -> vote.epoch();
            case VoteRound2 vote -> vote.epoch();
            case Decision<?> decision -> decision.epoch();
            default -> throw new IllegalArgumentException("not a slot ballot: " + message);
        };
    }

    private static Phase phaseOf(ProtocolMessage message) {
        return switch (message) {
            case Propose<?> propose -> propose.phase();
            case VoteRound1 vote -> vote.phase();
            case VoteRound2 vote -> vote.phase();
            case Decision<?> decision -> decision.phase();
            default -> throw new IllegalArgumentException("not a slot ballot: " + message);
        };
    }

    private void runSchedule(int size, int seed) {
        var cluster = new ScheduledCluster(size, seed);
        clusters.add(cluster);
        cluster.start();
        cluster.splitHealth = true;
        cluster.submitConflictingBatches();
        cluster.pumpUntil(() -> cluster.machines.stream().allMatch(machine -> machine.getProcessedCommands().size() >= size));
        cluster.pumpUntil(() -> cluster.pending.isEmpty() && cluster.emitted.isEmpty());
        var expected = cluster.machines.getFirst().getProcessedCommands();
        for (var machine : cluster.machines) {
            assertThat(machine.getProcessedCommands()).as("size=%s seed=%s", size, seed).containsExactlyElementsOf(expected);
            assertThat(machine.getProcessedCommands()).doesNotHaveDuplicates().hasSize(size);
        }
        cluster.stop();
    }
}
