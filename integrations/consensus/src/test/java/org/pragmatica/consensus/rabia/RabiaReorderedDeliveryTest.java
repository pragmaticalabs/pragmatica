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
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
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
    /// after R+1 (no effective slot of its own). The late replica must take the first way in at least
    /// one schedule, so the late-apply path is exercised rather than always bypassed.
    @Test
    void agreedChangeGovernsFromTheNextSlotOnEveryReplicaIncludingALateOne() {
        var lateReplicaAppliedR = 0;
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
            cluster.held = _ -> false;
            cluster.pumpUntil(() -> cluster.machines.stream().allMatch(machine -> machine.getProcessedCommands().size() == 2));
            cluster.pumpUntil(() -> cluster.engines.stream().allMatch(engine -> engine.voterConfiguration().unwrap().epoch() == 1));

            var effective = boundary.successor().value();
            for (var engine : cluster.engines) {
                assertThat(engine.voterConfiguration().unwrap()).isEqualTo(new VoterConfiguration(1, target));
                assertThat(engine.voterReconfigurationStatus().effectiveSlot().map(slot -> slot == effective).or(true))
                    .as("seed %s", seed).isTrue();
            }
            assertThat(cluster.engines.getFirst().voterReconfigurationStatus().effectiveSlot().unwrap()).isEqualTo(effective);
            if (cluster.engines.get(1).voterReconfigurationStatus().effectiveSlot().isPresent()) {
                lateReplicaAppliedR++;
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
        assertThat(lateReplicaAppliedR).as("the late replica applied R itself in at least one schedule").isPositive();
    }

    /// #1526 genesis by agreement. Epoch 0 forms only once every member of the roster has announced
    /// the identical roster: a late core holds everyone, and the cluster forms and decides when it
    /// arrives. Sync retries run on a short real-time interval here, as they would in production.
    @Test
    void genesisWaitsForTheLateCoreAndFormsTheClusterWhenItArrives() {
        for (int seed = 0; seed < 4; seed++) {
            var cluster = new ScheduledCluster(3, seed, 3, timeSpan(100).millis());
            clusters.add(cluster);
            var roster = new ClusterConfig(cluster.members);
            cluster.engines.forEach(engine -> assertThat(engine.deferGenesis().isSuccess()).isTrue());
            cluster.engines.forEach(engine -> engine.clusterState(ClusterStateNotification.active()));
            cluster.engines.subList(0, 2).forEach(engine -> assertThat(engine.proposeGenesis(roster).isSuccess()).isTrue());
            cluster.pumpUntil(() -> cluster.pending.isEmpty() && cluster.emitted.isEmpty());
            assertThat(cluster.engines).as("the late core has not announced: nobody forms").allMatch(RabiaEngine::isGenesisPending);
            assertThat(cluster.engines).noneMatch(RabiaEngine::isActive);
            assertThat(cluster.sent).as("no ballot while genesis is pending").isEmpty();

            assertThat(cluster.engines.get(2).proposeGenesis(roster).isSuccess()).isTrue();
            cluster.pumpUntil(() -> cluster.engines.stream().allMatch(RabiaEngine::isActive));
            var formed = Batch.create(cluster.machines.getFirst().serializer(), List.of(new TestCommand("after-late-core")));
            cluster.engines.forEach(engine -> engine.handleNewBatch(new NewBatch<>(cluster.members.getFirst(), formed)));
            cluster.pumpUntil(() -> cluster.machines.stream().allMatch(machine -> machine.getProcessedCommands().size() == 1));
            for (var engine : cluster.engines) {
                assertThat(engine.voterConfiguration().unwrap()).isEqualTo(new VoterConfiguration(0, roster));
            }
            cluster.stop();
        }
    }

    /// #1526 genesis safety. Five cores, three configured: A and B offer {A,B,C}, D and E offer {C,D,E},
    /// and the shared core C confirms exactly one of them (which one varies by schedule). At most one of
    /// the two epoch-0 configurations may ever exist; D and E, announcing again, join the formed
    /// electorate as observers instead of starting a second one.
    @Test
    void conflictingGenesisRostersCannotBothForm() {
        for (int seed = 0; seed < 8; seed++) {
            var cluster = new ScheduledCluster(5, seed, 5);
            clusters.add(cluster);
            var members = cluster.members;
            var left = new ClusterConfig(List.of(members.get(0), members.get(1), members.get(2)));
            var right = new ClusterConfig(List.of(members.get(2), members.get(3), members.get(4)));
            var chosen = seed % 2 == 0 ? left : right;
            var offers = List.of(left, left, chosen, right, right);
            cluster.engines.forEach(engine -> assertThat(engine.deferGenesis().isSuccess()).isTrue());
            for (int round = 0; round < 2; round++) {
                // The second round is the genesis timer announcing again.
                for (int index = 0; index < offers.size(); index++) {
                    cluster.engines.get(index).proposeGenesis(offers.get(index));
                }
                cluster.pumpUntil(() -> cluster.pending.isEmpty() && cluster.emitted.isEmpty());
            }

            var formedLeft = cluster.engines.stream().filter(engine -> configuredAs(engine, left)).count();
            var formedRight = cluster.engines.stream().filter(engine -> configuredAs(engine, right)).count();
            assertThat(formedLeft == 0 || formedRight == 0)
                .as("seed %s: both {A,B,C} (%s nodes) and {C,D,E} (%s nodes) formed", seed, formedLeft, formedRight)
                .isTrue();
            assertThat(cluster.engines).as("seed %s: the roster C confirmed forms and everyone joins it", seed)
                                       .allMatch(engine -> configuredAs(engine, chosen));
            cluster.stop();
        }
    }

    private static boolean configuredAs(RabiaEngine<TestCommand> engine, ClusterConfig roster) {
        return engine.voterConfiguration()
                     .filter(configuration -> configuration.epoch() == 0 && configuration.roster().sameMembership(roster))
                     .isPresent();
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

        ScheduledCluster(int size, int seed, int initialVoters, org.pragmatica.lang.io.TimeSpan syncRetryInterval) {
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
                var engine = new RabiaEngine<>(topology, network, machine,
                                              ProtocolConfig.consensusConfig(timeSpan(60).seconds(), syncRetryInterval));
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
                settle();
                verifyPrefixes();
                for (var delivery = emitted.poll(); delivery != null; delivery = emitted.poll()) {
                    if (!dead.contains(delivery.source()) && !dead.contains(delivery.target()) && !blocked.test(delivery)) {
                        parked.add(delivery);
                    }
                }
                for (var iterator = parked.iterator(); iterator.hasNext(); ) {
                    var delivery = iterator.next();
                    if (!held.test(delivery)) {
                        pending.add(delivery);
                        iterator.remove();
                    }
                }
                if (completed.getAsBoolean()) {
                    return;
                }
                if (pending.isEmpty()) {
                    continue;
                }
                var delivery = pending.remove(random.nextInt(pending.size()));
                deliver(delivery);
                if (random.nextInt(8) == 0) {
                    deliver(delivery);
                }
            }
            settle();
            assertThat(completed.getAsBoolean()).as("schedule must make progress; pending=%s", pending.size()).isTrue();
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
