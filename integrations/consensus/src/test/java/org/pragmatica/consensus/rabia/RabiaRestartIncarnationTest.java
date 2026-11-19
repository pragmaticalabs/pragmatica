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
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestClusterNetwork;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestCommand;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestStateMachine;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestTopologyManager;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.NewBatch;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.RoundRequest;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Decision;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Propose;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.VoteRound1;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.VoteRound2;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2625: a process that restarts under the same NodeId with its ballots lost re-votes in the open slot, at the engine
/// layer, with no transport to refuse it (library users of `RabiaNode` without boot tokens).
///
/// Voters {A, B, C}, one slot P. The original A and B decide X in P (A and C cannot talk, B and C cannot talk). A's ballots
/// for P toward C are still in flight when A crashes. A restarts as A' under the same id, empty, with Y queued, and talks to C,
/// which holds Y. C receives A''s proposal first and the original A's in-flight ballots after it. Without an incarnation on
/// the ballots C cannot tell the two processes apart: it counts a mix of both and decides Y in P while B decided X, so the
/// first slot disagrees. With the incarnation, C sees two processes under one id in P and stops counting either, so P does
/// not decide at C on A's say-so.
class RabiaRestartIncarnationTest {
    private static final int A = 0, B = 1, C = 2;
    private final List<Cluster> clusters = new ArrayList<>();

    @AfterEach
    void stopAll() {
        clusters.forEach(Cluster::stop);
    }

    @Test
    void sameIdRestart_revotingInAnOpenSlot_neverLetsTwoReceiversDecideDifferentValues() {
        var cluster = new Cluster();
        clusters.add(cluster);
        cluster.start();
        var x = batch(cluster, "X");
        var y = batch(cluster, "Y");

        // Stage 1: A and B decide X in P. Everything between C and the others is held; A's ballots to C stay in flight.
        cluster.deliverable = d -> isPair(d, A, B);
        cluster.engines.get(A).handleNewBatch(new NewBatch<>(cluster.members.get(A), x));
        cluster.engines.get(B).handleNewBatch(new NewBatch<>(cluster.members.get(B), x));
        cluster.engines.get(C).handleNewBatch(new NewBatch<>(cluster.members.get(C), y));
        cluster.pump();
        assertThat(cluster.machines.get(B).getProcessedCommands()).as("precondition: A and B decide X in P")
                                                               .containsExactly(new TestCommand("X"));
        assertThat(cluster.machines.get(C).getProcessedCommands()).as("precondition: C has decided nothing").isEmpty();

        // A crashes. Its proposal and votes for P toward C were sent and are still in flight; its decision to C is lost.
        var inFlightToC = cluster.takeParked(d -> d.source() == A && d.target() == C && isBallot(d.message()));
        assertThat(inFlightToC).as("precondition: A's P ballots toward C are in flight").isNotEmpty();
        cluster.crash(A);

        // A' restarts under the same id with no memory of its ballots and Y queued; B stays partitioned from everyone.
        cluster.restart(A);
        cluster.deliverable = d -> isPair(d, A, C);
        cluster.engines.get(A).handleNewBatch(new NewBatch<>(cluster.members.get(A), y));
        cluster.settle();
        var restartedProposal = cluster.takeParked(d -> d.source() == A && d.target() == C && d.message() instanceof Propose<?>);
        assertThat(restartedProposal).as("precondition: A' proposes in P").hasSize(1);

        // C hears A' first, then the original A's in-flight ballots, then the rest of A''s round.
        cluster.deliver(restartedProposal.getFirst());
        inFlightToC.forEach(cluster::deliver);
        cluster.pump();

        cluster.verifyPrefixes();
        assertThat(cluster.machines.get(C).getProcessedCommands()).as("C must not decide Y in the slot where B decided X")
                                                               .doesNotContain(new TestCommand("Y"));
    }

    private static Batch<TestCommand> batch(Cluster cluster, String value) {
        return Batch.create(cluster.machines.getFirst().serializer(), List.of(new TestCommand(value)));
    }

    private static boolean isPair(Delivery d, int one, int other) {
        return (d.source() == one && d.target() == other) || (d.source() == other && d.target() == one);
    }

    private static boolean isBallot(ProtocolMessage message) {
        return message instanceof Propose<?> || message instanceof VoteRound1 || message instanceof VoteRound2;
    }

    private record Delivery(int source, int target, ProtocolMessage message) {}

    /// Deterministic three-voter harness: FIFO delivery of what `deliverable` admits; everything else is parked and kept.
    private static final class Cluster {
        final List<RabiaEngine<TestCommand>> engines = new ArrayList<>();
        final List<TestStateMachine> machines = new ArrayList<>();
        final List<NodeId> members = IntStream.range(0, 3).mapToObj(i -> nodeId("voter-" + i).unwrap()).toList();
        final List<Delivery> parked = new ArrayList<>();
        final Set<Integer> dead = new HashSet<>();
        Predicate<Delivery> deliverable = _ -> true;

        Cluster() {
            for (int i = 0; i < 3; i++) {
                machines.add(new TestStateMachine());
                engines.add(create(i));
            }
        }

        RabiaEngine<TestCommand> create(int index) {
            var topology = new TestTopologyManager(members.get(index), 3) {
                @Override public Set<NodeId> coreNodes() { return Set.copyOf(members); }
                @Override public boolean isConsensusMember(NodeId id) { return members.contains(id); }
                @Override public List<NodeId> topology() { return members; }
                @Override public int clusterSize() { return members.size(); }
            };
            var network = new TestClusterNetwork() {
                @Override public Set<NodeId> connectedPeers() { return Set.copyOf(members); }
                @Override public <M extends ProtocolMessage> Unit broadcast(M message) {
                    for (int t = 0; t < members.size(); t++) {
                        if (t != index) { record(index, t, message); }
                    }
                    return Unit.unit();
                }
                @Override public <M extends ProtocolMessage> Promise<WriteOutcome> send(NodeId id, M message) {
                    record(index, members.indexOf(id), message);
                    return Promise.success(new WriteOutcome.Sent(id));
                }
                @Override public <M extends ProtocolMessage> Promise<WriteOutcome> send(NodeId id, M message, TimeSpan offlineTtl) {
                    return send(id, message);
                }
            };
            var engine = new RabiaEngine<>(topology, network, machines.get(index),
                                           ProtocolConfig.consensusConfig(timeSpan(60).seconds(), timeSpan(60).seconds()));
            assertThat(engine.initializeVoters(new VoterConfiguration(0, new ClusterConfig(members))).isSuccess()).isTrue();
            return engine;
        }

        private synchronized void record(int source, int target, ProtocolMessage message) {
            parked.add(new Delivery(source, target, message));
        }

        void start() {
            engines.forEach(e -> e.clusterState(ClusterStateNotification.active()));
            pump();
            assertThat(engines.stream().allMatch(RabiaEngine::isActive)).as("precondition: all voters active").isTrue();
        }

        void crash(int index) {
            engines.get(index).stop().await();
            dead.add(index);
            takeParked(d -> d.source() == index || d.target() == index);
        }

        /// A fresh in-memory engine under the same NodeId: nothing it balloted before the crash survives.
        void restart(int index) {
            machines.set(index, new TestStateMachine());
            engines.set(index, create(index));
            dead.remove(index);
            engines.get(index).clusterState(ClusterStateNotification.active());
            settle();
        }

        synchronized List<Delivery> takeParked(Predicate<Delivery> selector) {
            var taken = parked.stream().filter(selector).toList();
            parked.removeAll(taken);
            return taken;
        }

        void pump() {
            for (int step = 0; step < 10_000; step++) {
                settle();
                verifyPrefixes();
                var next = takeFirst();
                if (next.isEmpty()) { return; }
                deliver(next.getFirst());
            }
            throw new AssertionError("schedule did not quiesce");
        }

        private synchronized List<Delivery> takeFirst() {
            for (var d : parked) {
                if (!dead.contains(d.source()) && !dead.contains(d.target()) && deliverable.test(d)) {
                    parked.remove(d);
                    return List.of(d);
                }
            }
            return List.of();
        }

        void settle() {
            for (int i = 0; i < engines.size(); i++) {
                if (!dead.contains(i)) { engines.get(i).settleForTesting().await(); }
            }
        }

        void verifyPrefixes() {
            settle();
            var logs = IntStream.range(0, machines.size())
                                .filter(i -> !dead.contains(i))
                                .mapToObj(i -> List.copyOf(machines.get(i).getProcessedCommands()))
                                .toList();
            var longest = logs.stream().max(java.util.Comparator.comparingInt(List::size)).orElse(List.of());
            for (var log : logs) {
                assertThat(log).as("every live replica's log is a prefix of the longest; all logs %s", logs)
                               .containsExactlyElementsOf(longest.subList(0, log.size()));
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
                case RoundRequest m -> engine.handleRoundRequest(m);
                default -> {}
            }
            settle();
        }

        void stop() {
            for (int i = 0; i < engines.size(); i++) {
                if (!dead.contains(i)) { engines.get(i).stop().await(); }
            }
        }
    }
}
