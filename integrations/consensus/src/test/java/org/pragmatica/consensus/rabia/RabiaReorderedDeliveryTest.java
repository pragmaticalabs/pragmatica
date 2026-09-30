package org.pragmatica.consensus.rabia;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestClusterNetwork;
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
        var pending = Batch.create(cluster.machines.getFirst().serializer(), retained);
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
        var batch = Batch.create(cluster.machines.getFirst().serializer(), commands);
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
        assertThat(engineWarnings).noneMatch(message -> message.contains(STILL_MISSING));
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
        assertThat(engineWarnings).noneMatch(message -> message.contains(STILL_MISSING));
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
        assertThat(engineWarnings).as("control: the far gap was reported").anyMatch(message -> message.contains("resyncing"));
        assertThat(engineWarnings).noneMatch(message -> message.contains(STILL_MISSING));
    }

    private static Decision<TestCommand> readdressed(Decision<TestCommand> decision, long phase) {
        return new Decision<>(decision.sender(), decision.epoch(), Phase.phase(phase), decision.stateValue(), decision.value(), decision.reconfiguration());
    }

    private static final String STILL_MISSING = "still missing slot";
    private final List<String> engineWarnings = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final org.apache.logging.log4j.core.appender.AbstractAppender warningAppender =
        new org.apache.logging.log4j.core.appender.AbstractAppender("decision-gap-warnings", (org.apache.logging.log4j.core.Filter) null, null, true,
                                                                    org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY) {
            @Override
            public void append(org.apache.logging.log4j.core.LogEvent event) {
                if (event.getLevel() == org.apache.logging.log4j.Level.WARN) {
                    engineWarnings.add(event.getMessage().getFormattedMessage());
                }
            }
        };
    private org.apache.logging.log4j.core.config.LoggerConfig warningLogger;
    private org.apache.logging.log4j.Level warningOriginalLevel;

    private void captureEngineWarnings() {
        warningAppender.start();
        var context = (org.apache.logging.log4j.core.LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
        var configuration = context.getConfiguration();
        var name = RabiaEngine.class.getName();
        var existing = configuration.getLoggerConfig(name);
        if (!name.equals(existing.getName())) {
            existing = new org.apache.logging.log4j.core.config.LoggerConfig(name, org.apache.logging.log4j.Level.WARN, false);
            configuration.addLogger(name, existing);
        }
        warningLogger = existing;
        warningOriginalLevel = existing.getLevel();
        warningLogger.addAppender(warningAppender, org.apache.logging.log4j.Level.WARN, null);
        warningLogger.setLevel(org.apache.logging.log4j.Level.WARN);
        context.updateLoggers();
    }

    @AfterEach
    void releaseEngineWarnings() {
        if (warningLogger != null) {
            warningLogger.removeAppender(warningAppender.getName());
            warningLogger.setLevel(warningOriginalLevel);
            ((org.apache.logging.log4j.core.LoggerContext) org.apache.logging.log4j.LogManager.getContext(false)).updateLoggers();
        }
        warningAppender.stop();
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

    private record Delivery(int source, int target, ProtocolMessage message) {}

    private static final class ScheduledCluster {
        private final List<RabiaEngine<TestCommand>> engines = new ArrayList<>();
        private final List<SnapshotMachine> machines = new ArrayList<>();
        /// The `ConsensusActive` / `ConsensusPassive` edges each engine has published.
        private final List<List<ConsensusEvent>> events = new ArrayList<>();
        private final List<NodeId> members;
        private final ConcurrentLinkedQueue<Delivery> emitted = new ConcurrentLinkedQueue<>();
        private final List<Delivery> pending = new ArrayList<>();
        /// Every Propose, ballot and Decision any engine emitted since the last clear.
        private final List<ProtocolMessage> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final java.util.Set<Integer> dead = new java.util.HashSet<>();
        private final List<Delivery> parked = new ArrayList<>();
        /// Deliveries that are lost outright.
        private java.util.function.Predicate<Delivery> blocked = _ -> false;
        /// Deliveries that wait, in emission order, until the predicate releases them.
        private java.util.function.Predicate<Delivery> held = _ -> false;
        private final Random random;
        private volatile boolean splitHealth;

        ScheduledCluster(int size, int seed) { this(size, seed, size); }

        ScheduledCluster(int size, int seed, int initialVoters) { this(size, seed, initialVoters, timeSpan(60).seconds()); }

        ScheduledCluster(int size, int seed, org.pragmatica.lang.io.TimeSpan syncRetryInterval, org.pragmatica.lang.io.TimeSpan decisionGapTimeout) {
            this(size, seed, size, syncRetryInterval, decisionGapTimeout);
        }

        ScheduledCluster(int size, int seed, int initialVoters, org.pragmatica.lang.io.TimeSpan syncRetryInterval) {
            this(size, seed, initialVoters, syncRetryInterval, ProtocolConfig.DEFAULT_DECISION_GAP_TIMEOUT);
        }

        ScheduledCluster(int size, int seed, int initialVoters, org.pragmatica.lang.io.TimeSpan syncRetryInterval,
                         org.pragmatica.lang.io.TimeSpan decisionGapTimeout) {
            random = new Random(seed);
            members = IntStream.range(0, size).mapToObj(index -> nodeId("voter-" + index).unwrap()).toList();
            for (int index = 0; index < size; index++) {
                var sender = index;
                var machine = new SnapshotMachine();
                var topology = new TestTopologyManager(members.get(index), size) {
                    @Override
                    public java.util.Set<NodeId> coreNodes() { return java.util.Set.copyOf(members); }
                    @Override
                    public boolean isConsensusMember(NodeId id) { return members.contains(id); }
                    @Override
                    public List<NodeId> topology() {
                        return members.stream().filter(id -> !splitHealth || members.indexOf(id) % 2 == sender % 2).toList();
                    }
                    @Override
                    public int clusterSize() { return topology().size(); }
                };
                var network = new TestClusterNetwork() {
                    @Override public java.util.Set<NodeId> connectedPeers() { return java.util.Set.copyOf(members); }
                    @Override
                    public <M extends ProtocolMessage> Unit broadcast(M message) {
                        for (int target = 0; target < members.size(); target++) {
                            if (target != sender) {
                                emit(new Delivery(sender, target, message));
                            }
                        }
                        return Unit.unit();
                    }

                    @Override
                    public <M extends ProtocolMessage> Unit send(NodeId id, M message) {
                        emit(new Delivery(sender, members.indexOf(id), message));
                        return Unit.unit();
                    }
                };
                machines.add(machine);
                var consensusEvents = new java.util.concurrent.CopyOnWriteArrayList<ConsensusEvent>();
                events.add(consensusEvents);
                var config = ProtocolConfig.consensusConfig(timeSpan(60).seconds(), syncRetryInterval)
                                           .withDecisionGapTimeout(decisionGapTimeout);
                var engine = new RabiaEngine<>(topology, network, machine, config, ConsensusMetrics.noop(), false,
                                              RabiaPersistence.inMemory(), RabiaEngine.DEFAULT_PHASE_STALL_CHECK, consensusEvents::add);
                assertThat(engine.initializeVoters(new VoterConfiguration(0, new ClusterConfig(members.subList(0, initialVoters)))).isSuccess()).isTrue();
                if (index >= initialVoters) { engine.authorizeObservation(); }
                engines.add(engine);
            }
        }

        void emit(Delivery delivery) {
            if (delivery.message() instanceof Propose<?> || delivery.message() instanceof VoteRound1
                || delivery.message() instanceof VoteRound2 || delivery.message() instanceof Decision<?>) {
                sent.add(delivery.message());
            }
            emitted.add(delivery);
        }

        /// Crash-stops a replica: nothing is delivered to or from it again.
        void kill(int index) {
            dead.add(index);
            pending.removeIf(delivery -> delivery.source() == index || delivery.target() == index);
            engines.get(index).stop().await();
        }

        void start() {
            engines.forEach(engine -> engine.clusterState(ClusterStateNotification.active()));
            pumpUntil(() -> engines.stream().allMatch(engine -> engine.isActive() || engine.isObserving()));
        }

        void submitConflictingBatches() {
            var batches = IntStream.range(0, engines.size())
                                   .mapToObj(index -> Batch.create(machines.getFirst().serializer(), List.of(new TestCommand("command-" + index))))
                                   .toList();
            // Each voter initially selects a different immutable proposal for slot zero.
            for (int index = 0; index < engines.size(); index++) {
                engines.get(index).handleNewBatch(new NewBatch<>(members.get(index), batches.get(index)));
            }
            settle();
            // All requests become available before delivering ballots; proposal order still differs.
            for (var engine : engines) {
                batches.forEach(batch -> engine.handleNewBatch(new NewBatch<>(members.getFirst(), batch)));
            }
            settle();
        }

        void pumpUntil(BooleanSupplier completed) {
            for (int step = 0; step < 30_000; step++) {
                if (pumpStep(completed)) {
                    return;
                }
            }
            settle();
            assertThat(completed.getAsBoolean()).as("schedule must make progress; pending=%s", pending.size()).isTrue();
        }

        /// Pumps against a wall-clock budget instead of a step count, for schedules whose progress comes
        /// from an engine TIMER (a quiet cluster emits nothing until one fires), which a step budget can
        /// exhaust in less time than one timer period.
        void pumpWithTimersUntil(BooleanSupplier completed, long budgetMillis) {
            var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(budgetMillis);
            while (System.nanoTime() < deadline) {
                if (pumpStep(completed)) {
                    return;
                }
            }
            settle();
            assertThat(completed.getAsBoolean()).as("schedule must make progress within %sms; pending=%s", budgetMillis, pending.size()).isTrue();
        }

        /// One settle-collect-deliver step; true once `completed` holds.
        private boolean pumpStep(BooleanSupplier completed) {
            settle();
            verifyPrefixes();
            collectEmitted();
            for (var iterator = parked.iterator(); iterator.hasNext(); ) {
                var delivery = iterator.next();
                if (!held.test(delivery)) {
                    pending.add(delivery);
                    iterator.remove();
                }
            }
            if (completed.getAsBoolean()) {
                return true;
            }
            if (pending.isEmpty()) {
                return false;
            }
            var delivery = pending.remove(random.nextInt(pending.size()));
            deliver(delivery);
            if (random.nextInt(8) == 0) {
                deliver(delivery);
            }
            return false;
        }

        void collectEmitted() {
            for (var delivery = emitted.poll(); delivery != null; delivery = emitted.poll()) {
                if (!dead.contains(delivery.source()) && !dead.contains(delivery.target()) && !blocked.test(delivery)) {
                    parked.add(delivery);
                }
            }
        }

        /// Stops holding and delivers everything held so far, once each and in emission order, settling
        /// after every delivery. Traffic the release provokes joins the ordinary randomized schedule.
        void releaseHeldInOrder() {
            var releasing = held;
            held = _ -> false;
            settle();
            collectEmitted();
            var released = new ArrayList<Delivery>();
            for (var iterator = parked.iterator(); iterator.hasNext(); ) {
                var delivery = iterator.next();
                if (releasing.test(delivery)) {
                    released.add(delivery);
                    iterator.remove();
                }
            }
            for (var delivery : released) {
                deliver(delivery);
                settle();
                verifyPrefixes();
            }
        }

        /// Runs `rounds` genesis rounds on the given engines, delivering all traffic between rounds.
        void genesisRounds(List<Integer> indices, int rounds) {
            for (int round = 0; round < rounds; round++) {
                indices.forEach(index -> engines.get(index).runGenesisRoundForTesting());
                pumpUntil(() -> pending.isEmpty() && emitted.isEmpty());
            }
        }

        void settle() {
            for (int index = 0; index < engines.size(); index++) {
                if (!dead.contains(index)) {
                    engines.get(index).settleForTesting().await();
                }
            }
        }

        void verifyPrefixes() {
            var logs = machines.stream().map(machine -> List.copyOf(machine.getProcessedCommands())).toList();
            var longest = logs.stream().max(java.util.Comparator.comparingInt(List::size)).orElse(List.of());
            for (var log : logs) {
                assertThat(log).containsExactlyElementsOf(longest.subList(0, log.size()));
                assertThat(log).doesNotHaveDuplicates();
            }
        }


        @SuppressWarnings("unchecked")
        void deliver(Delivery delivery) {
            var engine = engines.get(delivery.target());
            switch (delivery.message()) {
                case Propose<?> message -> engine.processPropose((Propose<TestCommand>) message);
                case VoteRound1 message -> engine.processVoteRound1(message);
                case VoteRound2 message -> engine.processVoteRound2(message);
                case Decision<?> message -> engine.processDecision((Decision<TestCommand>) message);
                case SyncRequest message -> engine.handleSyncRequest(message);
                case RoundRequest message -> engine.handleRoundRequest(message);
                case ReconfigurationRequest message -> engine.reconfigurationRequest(message);
                case GenesisAnnouncement message -> engine.genesisAnnouncement(message);
                case SyncResponse<?> message -> engine.processSyncResponse((SyncResponse<TestCommand>) message);
                default -> {}
            }
        }

        void stop() {
            engines.forEach(engine -> engine.stop().await());
        }
    }

    private static final class SnapshotMachine extends TestStateMachine {
        @Override
        public Result<byte[]> makeSnapshot() {
            return Result.success(String.join("\n", getProcessedCommands().stream().map(TestCommand::value).toList())
                                        .getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public Result<Unit> restoreSnapshot(byte[] snapshot) {
            reset();
            if (snapshot.length > 0) {
                process(Batch.create(serializer(), new String(snapshot, StandardCharsets.UTF_8).lines().map(TestCommand::new).toList()));
            }
            return Result.success(Unit.unit());
        }
    }
}
