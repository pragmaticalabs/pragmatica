package org.pragmatica.consensus.rabia;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestCommand;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.BatchCommitted;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.NewBatch;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Decision;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Propose;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// #2045 limit 2 (verifier v2597): a BatchCommitted answer carries ONLY the correlation ids the answering voter's ledger
/// holds as committed, and the receiver strips ONLY those. A batch id can be shared by a committed submission c1 and a
/// genuine second submission c2 of the same commands (limit 3); answering or stripping c2 would fail or drop a request
/// that was never decided.
class RabiaBatchCommittedSubsetTest {
    private final List<ScheduledCluster> clusters = new ArrayList<>();

    @AfterEach
    void stopClusters() {
        clusters.forEach(ScheduledCluster::stop);
    }

    @Test
    void answerToAMixedProposal_namesOnlyTheCommittedCorrelationId() {
        var cluster = new ScheduledCluster(3, 0);
        clusters.add(cluster);
        cluster.start();
        cluster.allowIdenticalCommands = true;
        var commands = List.of(new TestCommand("committed-and-resubmitted"));
        var c1 = Batch.create(cluster.machines.getFirst().serializer(), commands);
        var c2 = Batch.create(cluster.machines.getFirst().serializer(), commands);
        assertThat(c2.id()).as("CONTROL: same batch id").isEqualTo(c1.id());
        assertThat(c2.correlationIds()).as("CONTROL: different submission").isNotEqualTo(c1.correlationIds());
        var answering = cluster.engines.get(1);
        answering.processDecision(new Decision<>(cluster.members.get(2), Phase.ZERO, StateValue.V1, c1));
        cluster.settle();
        cluster.collectEmitted();
        cluster.parked.clear();
        var mixed = new Batch<>(c1.id(),
                                Stream.concat(c1.correlationIds().stream(), c2.correlationIds().stream()).toList(),
                                c1.timestamp(),
                                c1.commands());

        answering.processPropose(new Propose<>(cluster.members.getFirst(), answering.currentPhaseForTesting(), mixed));
        cluster.settle();
        cluster.collectEmitted();

        var answers = cluster.parked.stream()
                                    .filter(delivery -> delivery.message() instanceof BatchCommitted)
                                    .map(delivery -> (BatchCommitted) delivery.message())
                                    .toList();
        assertThat(answers).as("CONTROL: the committed half is answered").isNotEmpty();
        answers.forEach(answer -> assertThat(answer.correlationIds()).as("only c1 is committed")
                                                                     .containsExactlyElementsOf(c1.correlationIds()));
    }

    @Test
    void answerNamingOneSubmission_leavesTheOtherSubmissionPending() {
        var cluster = new ScheduledCluster(3, 0);
        clusters.add(cluster);
        cluster.start();
        cluster.allowIdenticalCommands = true;
        cluster.blocked = _ -> true;
        var commands = List.of(new TestCommand("half-answered"));
        var c1 = Batch.create(cluster.machines.getFirst().serializer(), commands);
        var c2 = Batch.create(cluster.machines.getFirst().serializer(), commands);
        var receiver = cluster.engines.getFirst();
        receiver.handleNewBatch(new NewBatch<>(cluster.members.get(1), c1));
        receiver.handleNewBatch(new NewBatch<>(cluster.members.get(2), c2));
        cluster.settle();
        assertThat(receiver.pendingBatchCountForTesting()).as("CONTROL: one merged batch").isEqualTo(1);

        receiver.handleBatchCommitted(new BatchCommitted(cluster.members.get(1), c1.id(), c1.correlationIds()));
        cluster.settle();

        assertThat(receiver.pendingBatchCountForTesting()).as("c2 was never committed: it stays pending").isEqualTo(1);
        assertThat(receiver.firstChoiceBatchId()).isEqualTo(org.pragmatica.lang.Option.some(c1.id().id()));
    }

    /// The first-choice probe names the batch this voter proposes, the LOWEST pending id (`nextProposal` takes
    /// `pendingBatches.firstEntry()`); with one pending batch the first and the last entry coincide and cannot tell.
    @Test
    void firstChoiceBatchId_isTheLowestPendingId_withTwoBatchesPending() {
        var cluster = new ScheduledCluster(3, 0);
        clusters.add(cluster);
        cluster.start();
        cluster.blocked = _ -> true;
        var a = Batch.create(cluster.machines.getFirst().serializer(), List.of(new TestCommand("first-choice-a")));
        var b = Batch.create(cluster.machines.getFirst().serializer(), List.of(new TestCommand("first-choice-b")));
        var engine = cluster.engines.getFirst();
        engine.handleNewBatch(new NewBatch<>(cluster.members.get(1), a));
        engine.handleNewBatch(new NewBatch<>(cluster.members.get(2), b));
        cluster.settle();
        assertThat(engine.pendingBatchCountForTesting()).as("CONTROL: two batches pending").isEqualTo(2);
        var lowest = a.id().compareTo(b.id()) < 0 ? a.id() : b.id();

        assertThat(engine.firstChoiceBatchId()).isEqualTo(org.pragmatica.lang.Option.some(lowest.id()));
    }
}
