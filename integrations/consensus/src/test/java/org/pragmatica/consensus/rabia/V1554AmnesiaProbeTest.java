package org.pragmatica.consensus.rabia;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestClusterNetwork;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestCommand;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestStateMachine;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestTopologyManager;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.*;
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
import java.util.function.Predicate;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// v1554 adversarial probe. Voters {v0,v1,v2}; §4 swap to {v0,v1,v3} while v2 lags at epoch 0.
/// In slot S (epoch 1) v1 and v3 decide X; v3 decides, v1 crashes (in-memory: amnesia), v3 is slow.
/// v1 restarts genesis-pending; the lagging v2 answers its announcement with the epoch-0 roster.
/// Attack: v1' then adopts v0's epoch-1 state from ONE live responder and decides Y in slot S with v0.
/// Control: v0 answers the announcement (epoch 1) instead: same-epoch arm needs a majority, v1' waits.
///
/// #1526 — why the attack is DISABLED here: this harness has no transport, so nothing refuses a process
/// restarted under v1's NodeId. The attack demonstrates the
/// [limit: amnesiac-same-id-excluded-by-boot-token]: single-responder newer-epoch adoption is safe only
/// because such a restart is refused at transport (#1528/#1545), which `EmberAmnesiacRestartTest` pins
/// on real QUIC/SWIM. Enabled, the attack diverges (v3 decides X, v0 with v1' decides Y in the same slot).
/// It is kept as the executable statement of why the transport gate is load-bearing.
class V1554AmnesiaProbeTest {
    private static final int A = 0, B = 1, C = 2, D = 3;
    private final List<Cluster> clusters = new ArrayList<>();

    @AfterEach void stopAll() { clusters.forEach(Cluster::stop); }

    @Test
    @org.junit.jupiter.api.Disabled("Demonstrates [limit: amnesiac-same-id-excluded-by-boot-token]: without the transport's "
                                    + "boot-token gate a same-id amnesiac restart diverges. Pinned on real transport by EmberAmnesiacRestartTest.")
    void attack_formedReplyFromLaggingMember_thenSingleResponderNewerEpochSync() {
        for (int seed = 0; seed < 5; seed++) {
            run(seed, true);
        }
    }

    @Test void control_formedReplyFromCurrentMember_sameEpochMajorityRuleHolds() {
        for (int seed = 0; seed < 5; seed++) {
            run(seed, false);
        }
    }

    private void run(int seed, boolean lagging) {
        var cluster = new Cluster(seed);
        clusters.add(cluster);
        cluster.start();
        // Stage 1: swap v2 -> v3 while v2 hears nothing (it stays at epoch 0).
        cluster.held = d -> d.target() == C;
        var target = new ClusterConfig(List.of(cluster.members.get(A), cluster.members.get(B), cluster.members.get(D)));
        var change = cluster.engines.get(A).reconfigure(target);
        cluster.pumpUntil(() -> change.isResolved() && cluster.pending.isEmpty() && cluster.emitted.isEmpty());
        assertThat(change.await().isSuccess()).isTrue();
        cluster.pumpUntil(() -> List.of(A, B, D).stream().allMatch(i -> cluster.engines.get(i).voterConfiguration().unwrap().epoch() == 1)
                                && cluster.pending.isEmpty());
        assertThat(cluster.engines.get(C).voterConfiguration().unwrap().epoch()).as("v2 lags at epoch 0").isZero();
        var slotS = cluster.engines.get(A).currentPhaseForTesting();

        // Stage 2: v1 and v3 decide X in slot S; everything touching v0 waits (v0 slow), v2 still held.
        cluster.held = d -> d.target() == C || d.source() == A || d.target() == A;
        var x = Batch.create(cluster.machines.getFirst().serializer(), List.of(new TestCommand("X-" + seed)));
        var y = Batch.create(cluster.machines.getFirst().serializer(), List.of(new TestCommand("Y-" + seed)));
        cluster.engines.get(B).handleNewBatch(new NewBatch<>(cluster.members.get(B), x));
        cluster.engines.get(D).handleNewBatch(new NewBatch<>(cluster.members.get(B), x));
        cluster.engines.get(A).handleNewBatch(new NewBatch<>(cluster.members.get(A), y));
        cluster.pumpUntil(() -> cluster.machines.get(D).getProcessedCommands().contains(new TestCommand("X-" + seed)));
        var dLog = List.copyOf(cluster.machines.get(D).getProcessedCommands());

        // v1 crashes: its in-flight messages are lost; in-memory cores forget their ballots.
        cluster.crash(B);
        // v3 is now slow: nothing to or from it is delivered (it is correct, just partitioned).
        cluster.held = d -> d.source() == D || d.target() == D
                            || (d.target() == C && !(d.message() instanceof GenesisAnnouncement) && !(d.message() instanceof SyncRequest));
        // Which member's `formed` answer reaches the restarted v1 first.
        var silenced = lagging ? A : C;
        cluster.blocked = d -> d.source() == silenced && d.target() == B && d.message() instanceof GenesisAnnouncement;
        cluster.restartPendingGenesis(B);
        // A formed answer is installed at the pending node's next genesis round (newest seen wins).
        for (int round = 0; round < 3 && cluster.engines.get(B).isGenesisPending(); round++) {
            cluster.engines.get(B).runGenesisRoundForTesting();
            cluster.drain();
        }
        cluster.pumpUntil(() -> !cluster.engines.get(B).isGenesisPending());
        System.out.printf("seed %d lagging=%s: v1' installed %s%n", seed, lagging, cluster.engines.get(B).voterConfiguration());

        if (lagging) {
            // Divergence is detected by verifyPrefixes on every pump step (and asserted here).
            cluster.pumpUntil(() -> cluster.machines.get(A).getProcessedCommands().size() >= dLog.size());
            System.out.printf("seed %d: v3 log %s | v0 log %s | v1' log %s | v1' active %s epoch %s%n", seed, dLog,
                              cluster.machines.get(A).getProcessedCommands(), cluster.machines.get(B).getProcessedCommands(),
                              cluster.engines.get(B).isActive(), cluster.engines.get(B).voterConfiguration());
        } else {
            cluster.drain();
            System.out.printf("seed %d control: v1' active %s, v0 log %s, v3 log %s%n", seed, cluster.engines.get(B).isActive(),
                              cluster.machines.get(A).getProcessedCommands(), dLog);
            assertThat(cluster.engines.get(B).isActive()).as("same-epoch arm must wait for a majority").isFalse();
            cluster.held = _ -> false;
            cluster.blocked = _ -> false;
            cluster.pumpUntil(() -> cluster.machines.get(A).getProcessedCommands().size() >= dLog.size());
        }
        assertThat(cluster.machines.get(A).getProcessedCommands()).as("seed %s: slot S agreement", seed)
                                                               .containsExactlyElementsOf(dLog);
        cluster.stop();
    }

    private record Delivery(int source, int target, ProtocolMessage message) {}

    private static final class Cluster {
        final List<RabiaEngine<TestCommand>> engines = new ArrayList<>();
        final List<SnapshotMachine> machines = new ArrayList<>();
        final List<NodeId> members;
        final ConcurrentLinkedQueue<Delivery> emitted = new ConcurrentLinkedQueue<>();
        final List<Delivery> pending = new ArrayList<>();
        final List<Delivery> parked = new ArrayList<>();
        final Set<Integer> dead = new java.util.HashSet<>();
        Predicate<Delivery> blocked = _ -> false;
        Predicate<Delivery> held = _ -> false;
        final Random random;

        Cluster(int seed) {
            random = new Random(seed);
            members = IntStream.range(0, 4).mapToObj(i -> nodeId("voter-" + i).unwrap()).toList();
            for (int i = 0; i < 4; i++) {
                machines.add(new SnapshotMachine());
                engines.add(null);
                var engine = create(i);
                assertThat(engine.initializeVoters(new VoterConfiguration(0, new ClusterConfig(members.subList(0, 3)))).isSuccess()).isTrue();
                if (i >= 3) { engine.authorizeObservation(); }
                engines.set(i, engine);
            }
        }

        RabiaEngine<TestCommand> create(int index) {
            var sender = index;
            var topology = new TestTopologyManager(members.get(index), 4) {
                @Override public Set<NodeId> coreNodes() { return Set.copyOf(members); }
                @Override public boolean isConsensusMember(NodeId id) { return members.contains(id); }
                @Override public List<NodeId> topology() { return members; }
                @Override public int clusterSize() { return members.size(); }
            };
            var network = new TestClusterNetwork() {
                @Override public Set<NodeId> connectedPeers() { return Set.copyOf(members); }
                @Override public <M extends ProtocolMessage> Unit broadcast(M message) {
                    for (int t = 0; t < members.size(); t++) {
                        if (t != sender) { emitted.add(new Delivery(sender, t, message)); }
                    }
                    return Unit.unit();
                }
                @Override public <M extends ProtocolMessage> Unit send(NodeId id, M message) {
                    emitted.add(new Delivery(sender, members.indexOf(id), message));
                    return Unit.unit();
                }
            };
            return new RabiaEngine<>(topology, network, machines.get(index),
                                     ProtocolConfig.consensusConfig(timeSpan(60).seconds(), timeSpan(60).seconds()));
        }

        void crash(int index) {
            engines.get(index).stop().await();
            dead.add(index);
            pending.removeIf(d -> d.source() == index);
            parked.removeIf(d -> d.source() == index);
            emitted.removeIf(d -> d.source() == index);
        }

        /// Restart as AetherNode does for a core: fresh in-memory engine, genesis deferred to view agreement.
        void restartPendingGenesis(int index) {
            machines.set(index, new SnapshotMachine());
            var engine = create(index);
            assertThat(engine.deferGenesis(() -> Set.copyOf(members), 3, Option.none(), Set.copyOf(members.subList(0, 3))).isSuccess()).isTrue();
            engines.set(index, engine);
            dead.remove(index);
            engine.clusterState(ClusterStateNotification.active());
        }

        void start() {
            engines.forEach(e -> e.clusterState(ClusterStateNotification.active()));
            pumpUntil(() -> engines.stream().allMatch(e -> e.isActive() || e.isObserving()));
        }

        void drain() {
            for (int step = 0; step < 30_000; step++) {
                shuffleIn();
                if (pending.isEmpty()) { return; }
                deliver(pending.remove(random.nextInt(pending.size())));
            }
        }

        private void shuffleIn() {
            settle();
            verifyPrefixes();
            for (var d = emitted.poll(); d != null; d = emitted.poll()) {
                if (!dead.contains(d.source()) && !dead.contains(d.target()) && !blocked.test(d)) { parked.add(d); }
            }
            for (var it = parked.iterator(); it.hasNext(); ) {
                var d = it.next();
                if (dead.contains(d.target())) { it.remove(); continue; }
                if (!held.test(d)) { pending.add(d); it.remove(); }
            }
        }

        void pumpUntil(BooleanSupplier completed) {
            for (int step = 0; step < 30_000; step++) {
                shuffleIn();
                if (completed.getAsBoolean()) { return; }
                if (pending.isEmpty()) { continue; }
                deliver(pending.remove(random.nextInt(pending.size())));
            }
            settle();
            assertThat(completed.getAsBoolean()).as("schedule must make progress; pending=%s parked=%s", pending.size(), parked.size()).isTrue();
        }

        void settle() {
            for (int i = 0; i < engines.size(); i++) {
                if (!dead.contains(i)) { engines.get(i).settleForTesting().await(); }
            }
        }

        void verifyPrefixes() {
            var logs = machines.stream().map(m -> List.copyOf(m.getProcessedCommands())).toList();
            var longest = logs.stream().max(java.util.Comparator.comparingInt(List::size)).orElse(List.of());
            for (int i = 0; i < logs.size(); i++) {
                assertThat(logs.get(i)).as("replica %s log vs longest; all logs %s", i, logs)
                                       .containsExactlyElementsOf(longest.subList(0, logs.get(i).size()));
            }
        }

        @SuppressWarnings("unchecked")
        void deliver(Delivery d) {
            var engine = engines.get(d.target());
            switch (d.message()) {
                case Propose<?> m -> engine.processPropose((Propose<TestCommand>) m);
                case VoteRound1 m -> engine.processVoteRound1(m);
                case VoteRound2 m -> engine.processVoteRound2(m);
                case Decision<?> m -> engine.processDecision((Decision<TestCommand>) m);
                case SyncRequest m -> engine.handleSyncRequest(m);
                case RoundRequest m -> engine.handleRoundRequest(m);
                case ReconfigurationRequest m -> engine.reconfigurationRequest(m);
                case GenesisAnnouncement m -> engine.genesisAnnouncement(m);
                case SyncResponse<?> m -> engine.processSyncResponse((SyncResponse<TestCommand>) m);
                default -> {}
            }
        }

        void stop() {
            for (int i = 0; i < engines.size(); i++) {
                if (!dead.contains(i)) { engines.get(i).stop().await(); }
            }
        }
    }

    private static final class SnapshotMachine extends TestStateMachine {
        @Override public Result<byte[]> makeSnapshot() {
            return Result.success(String.join("\n", getProcessedCommands().stream().map(TestCommand::value).toList())
                                        .getBytes(StandardCharsets.UTF_8));
        }

        @Override public Result<Unit> restoreSnapshot(byte[] snapshot) {
            reset();
            if (snapshot.length > 0) {
                process(Batch.create(serializer(), new String(snapshot, StandardCharsets.UTF_8).lines().map(TestCommand::new).toList()));
            }
            return Result.success(Unit.unit());
        }
    }
}
