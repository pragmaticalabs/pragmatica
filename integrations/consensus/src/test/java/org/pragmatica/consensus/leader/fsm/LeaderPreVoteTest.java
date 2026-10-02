/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */

package org.pragmatica.consensus.leader.fsm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.consensus.leader.LeaderManager.FsmBackedLeaderManager;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.ConsensusReady;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderCommitted;
import org.pragmatica.consensus.net.NetworkMessage.LeaderPreVoteRequest;
import org.pragmatica.consensus.net.NetworkMessage.LeaderPreVoteResponse;
import org.pragmatica.consensus.net.NetworkServiceMessage.Send;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.FsmTestHarness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/// #1748: a follower that loses ONLY its own view of a healthy leader must not depose it. The tests wire
/// five real [`LeaderManager`]s (real FSM, real context, real pre-vote) over an in-memory network that can
/// cut single links, so what is pinned is the decision a cluster takes, not a stub of it: a "deposition" is
/// the observable the rc4 cloud run produced — the follower entering `ReElecting` and proposing itself.
///
/// The network delivers on one thread with a small delay, as a real one does; nothing here is synchronous
/// re-entry into the FSM.
class LeaderPreVoteTest {
    private static final NodeId N1 = new NodeId("node-1");
    private static final NodeId N2 = new NodeId("node-2");
    private static final NodeId N3 = new NodeId("node-3");
    private static final NodeId N4 = new NodeId("node-4");
    private static final NodeId N5 = new NodeId("node-5");
    private static final List<NodeId> ALL = List.of(N1, N2, N3, N4, N5);

    private static final TimeSpan LONG = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan RETRY = TimeSpan.timeSpan(50).millis();
    private static final TimeSpan ROUND_TIMEOUT = TimeSpan.timeSpan(200).millis();
    /// Short election delays: if a follower DOES decide to elect, its proposal shows up within a few hundred ms.
    private static final TimeSpan BASE_ELECTION = TimeSpan.timeSpan(100).millis();
    private static final TimeSpan PER_RANK = TimeSpan.timeSpan(100).millis();
    private static final TimeSpan LEASE_INTERVAL = TimeSpan.timeSpan(30).millis();
    private static final long SETTLE_MS = 2_000L;

    private Cluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop();
        }
    }

    /// Five followers-and-a-leader over an in-memory network. Each node owns its FSM/context/manager; the
    /// network routes `Send` of the two pre-vote messages to the target's manager unless the link is cut.
    private static final class Cluster {
        final Map<NodeId, Node> nodes = new ConcurrentHashMap<>();
        final Set<String> cutLinks = ConcurrentHashMap.newKeySet();
        final Set<NodeId> dead = ConcurrentHashMap.newKeySet();
        final List<LeaderPreVoteRequest> requests = new CopyOnWriteArrayList<>();
        final ExecutorService network = Executors.newSingleThreadExecutor();
        private final Map<NodeId, NodeId> believes;

        record Node(NodeId id,
                    FsmTestHarness<LeaderElectionState, ClusterFsmEvent> harness,
                    LeaderElectionContext ctx,
                    FsmBackedLeaderManager manager,
                    AtomicInteger proposals) {
            LeaderElectionState state() {
                return harness.state();
            }

            boolean isReElecting() {
                return state() instanceof LeaderElectionState.ReElecting;
            }

            boolean isLedBy(NodeId leader) {
                return state() instanceof LeaderElectionState.Led led && led.leader().equals(leader);
            }
        }

        static String link(NodeId a, NodeId b) {
            return a.compareTo(b) < 0
                   ? a + "|" + b
                   : b + "|" + a;
        }

        Cluster(boolean preVote, Map<NodeId, Predicate<NodeId>> pingFresh) {
            this(preVote, pingFresh, Map.of());
        }

        /// `believes` names the leader a node starts out following (default N1) — the crossed-pointer wedge
        /// has every node following a different one.
        Cluster(boolean preVote, Map<NodeId, Predicate<NodeId>> pingFresh, Map<NodeId, NodeId> believes) {
            this.believes = believes;
            ALL.forEach(id -> nodes.put(id, buildNode(id, preVote, pingFresh.getOrDefault(id, _ -> true))));
            nodes.values().forEach(this::wire);
        }

        private Node buildNode(NodeId id, boolean preVote, Predicate<NodeId> pingFresh) {
            var router = MessageRouter.mutable();
            var proposals = new AtomicInteger();
            LeaderManager.LeaderProposalHandler handler = (candidate, viewSequence) -> {
                proposals.incrementAndGet();
                return Promise.success(Unit.unit());
            };
            var holder = new java.util.concurrent.atomic.AtomicReference<LeaderElectionContext>();
            var harness = FsmTestHarness.<LeaderElectionState, ClusterFsmEvent>harness("pre-vote-" + id, fsm -> {
                var ctx = new LeaderElectionContext(fsm,
                                                    id,
                                                    Option.some(handler),
                                                    ALL,
                                                    router,
                                                    RETRY,
                                                    BASE_ELECTION,
                                                    PER_RANK,
                                                    LONG,
                                                    LeaderElectionContext.DEFAULT_STUCK_ELECTION_THRESHOLD,
                                                    LeaderElectionContext.DEFAULT_JITTER_SOURCE,
                                                    LeaderElectionContext.DEFAULT_RABIA_TERM_SUPPLIER,
                                                    () -> true,
                                                    Option::none,
                                                    LONG,
                                                    LONG,
                                                    LEASE_INTERVAL,
                                                    pingFresh,
                                                    TimeSpan.timeSpan(30).millis());

                holder.set(ctx);
                return ctx.dormant();
            });
            var ctx = holder.get();

            if (preVote) {
                ctx.enablePreVote(ROUND_TIMEOUT);
            }

            router.addRoute(Send.class, (Consumer<Send>) send -> route(id, send));

            return new Node(id, harness, ctx, new FsmBackedLeaderManager(harness.fsm(), ctx, false), proposals);
        }

        private void wire(Node node) {
            node.harness.dispatch(new ClusterFsmEvent.QuorumEstablished());
            node.harness.dispatch(new ClusterFsmEvent.NodeAdded(node.id, ALL));
            node.harness.dispatch(new ConsensusReady());
            node.harness.dispatch(new LeaderCommitted(believes.getOrDefault(node.id, N1), 1L));
        }

        private void route(NodeId from, Send send) {
            if (!reachable(from, send.target())) {
                return;
            }

            var target = nodes.get(send.target());

            network.execute(() -> deliver(target, send));
        }

        private boolean reachable(NodeId a, NodeId b) {
            return !dead.contains(a) && !dead.contains(b) && !cutLinks.contains(link(a, b));
        }

        private void deliver(Node target, Send send) {
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            switch (send.payload()) {
                case LeaderPreVoteRequest request -> {
                    requests.add(request);
                    target.manager.leaderPreVoteRequest(request);
                }
                case LeaderPreVoteResponse response -> target.manager.leaderPreVoteResponse(response);
                default -> {}
            }
        }

        Node node(NodeId id) {
            return nodes.get(id);
        }

        void cut(NodeId a, NodeId b) {
            cutLinks.add(link(a, b));
        }

        void kill(NodeId id) {
            dead.add(id);
        }

        /// What `node`'s transport reports when it stops seeing `gone`: a `NodeGone` with the remaining view.
        void lose(NodeId node, NodeId gone, NodeId... alsoGone) {
            var view = new ArrayList<>(ALL);
            var removed = new ArrayList<NodeId>(List.of(alsoGone));

            removed.add(gone);
            view.removeAll(removed);
            node(node).harness.dispatch(new ClusterFsmEvent.NodeGone(gone, view));
        }

        void stop() {
            nodes.values().forEach(n -> n.harness.dispatch(new ClusterFsmEvent.Shutdown()));
            network.shutdownNow();
        }
    }

    private static boolean await(java.util.function.BooleanSupplier condition, long timeoutMillis) {
        var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);

        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        return condition.getAsBoolean();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Nested
    class WhenTheLeaderIsHealthyForTheElectorate {
        /// The pin for #1748. N3 loses ONLY its own view of N1; N2, N4, N5 still see it. Before the fix N3
        /// went `Led -> ReElecting` on the `NodeGone` and proposed itself, and a majority that could still
        /// reach it committed the challenger.
        @Test
        void followerLosingOnlyItsOwnView_doesNotDeposeTheLeader() {
            cluster = new Cluster(true, Map.of());

            cluster.lose(N3, N1);
            sleep(SETTLE_MS);

            var n3 = cluster.node(N3);

            assertThat(n3.isLedBy(N1)).as("N3 keeps following the committed leader").isTrue();
            assertThat(n3.proposals.get()).as("N3 never proposed itself").isZero();
            assertThat(n3.harness.transitions()
                         .stream()
                         .filter(t -> t.to() instanceof LeaderElectionState.ReElecting))
                .as("N3 never entered ReElecting")
                .isEmpty();
            assertThat(cluster.requests)
                .as("the pre-vote actually ran, and retried while the suspicion lasted (4 voters per round)")
                .hasSizeGreaterThanOrEqualTo(8);
            assertThat(List.of(N1, N2, N4, N5)).allMatch(id -> cluster.node(id).isLedBy(N1));
        }

        /// A tenure's late `onExit` must not end an episode the next tenure already started about ANOTHER
        /// leader: cancelling about N2 leaves the live episode about N1 asking, cancelling about N1 ends it.
        @Test
        void cancel_isScopedToTheLeaderOfTheEpisode() {
            cluster = new Cluster(true, Map.of());
            var preVote = cluster.node(N3).ctx.preVote().unwrap();

            cluster.lose(N3, N1);
            preVote.cancel(N2);
            var afterWrongCancel = cluster.requests.size();

            assertThat(await(() -> cluster.requests.size() >= afterWrongCancel + 8, 3_000))
                .as("the episode about N1 survives a cancel about N2 and keeps asking")
                .isTrue();

            preVote.cancel(N1);
            sleep(300);
            var afterRightCancel = cluster.requests.size();
            sleep(SETTLE_MS / 2);

            assertThat(cluster.requests).as("cancelling about N1 ends the episode: no further questions").hasSize(afterRightCancel);
        }

        /// The S05 shape: a 2-vs-3 partition whose cuts land staggered. N2 loses N1 and keeps its links to N3
        /// and N4 (so, with itself, it can reach three of five — a Rabia quorum — exactly the commit that
        /// deposed core-0); N5 is isolated first. The leader must stay on the majority side.
        @Test
        void asymmetricPartition_staggeredCut_leaderIsKeptOnTheMajoritySide() {
            cluster = new Cluster(true, Map.of());

            // N5 is cut off from everyone, first.
            ALL.stream().filter(id -> !id.equals(N5)).forEach(id -> cluster.cut(N5, id));
            cluster.lose(N5, N1, N2, N3, N4);
            ALL.stream().filter(id -> !id.equals(N5) && !id.equals(N2)).forEach(id -> cluster.lose(id, N5));
            sleep(300);

            // ~20 s later in the cloud run: N2 loses N1, but not yet N3 / N4.
            cluster.cut(N2, N1);
            cluster.cut(N2, N5);
            cluster.lose(N2, N1, N5);
            sleep(SETTLE_MS);

            assertThat(cluster.node(N2).isLedBy(N1))
                .as("N2 reaches only N3 and N4, which both still see the leader — it must not challenge")
                .isTrue();
            assertThat(cluster.node(N2).proposals.get()).as("N2 never proposed itself").isZero();

            // The remaining links of N2 go down too: it hears nobody, and silence is not doubt.
            cluster.cut(N2, N3);
            cluster.cut(N2, N4);
            cluster.lose(N2, N3, N1, N5);
            cluster.lose(N2, N4, N1, N5, N3);
            sleep(SETTLE_MS);

            assertThat(cluster.node(N2).isLedBy(N1)).as("an isolated follower cannot assemble a majority").isTrue();
            assertThat(List.of(N1, N3, N4)).allMatch(id -> cluster.node(id).isLedBy(N1));
            assertThat(ALL).allMatch(id -> cluster.node(id).proposals.get() == 0);
        }

        /// The lease path takes the same gate: N3 alone sees a silent leader ping (its own link to the
        /// leader's pings is bad), the rest see fresh pings.
        @Test
        void followerWhoseLeaderPingIsSilent_butTheRestSeeItFresh_doesNotDepose() {
            cluster = new Cluster(true, Map.of(N3, _ -> false));

            sleep(SETTLE_MS);

            assertThat(cluster.node(N3).isLedBy(N1)).as("lease tripped, electorate vouched, N3 stays").isTrue();
            assertThat(cluster.node(N3).proposals.get()).isZero();
            assertThat(cluster.requests).as("the lease trip did ask the electorate").isNotEmpty();
        }

        /// A verdict that arrives after the leader came back into this follower's own view is discarded: the
        /// round's majority described a leader the follower no longer suspects.
        @Test
        void doubtVerdict_forALeaderTheFollowerNoLongerSuspects_isDiscarded() {
            cluster = new Cluster(true, Map.of());

            cluster.node(N3).harness.dispatch(new LeaderElectionEvents.LeaderDoubtConfirmed(N1));
            sleep(300);

            assertThat(cluster.node(N3).isLedBy(N1)).as("N3 still sees N1, so the verdict is stale").isTrue();
            assertThat(cluster.node(N3).proposals.get()).isZero();
        }

        /// Answers are paired with the round that asked: doubts carrying another round's number are not counted,
        /// or a late answer from an earlier round could complete a majority the current round never formed.
        @Test
        void answersFromAnotherRound_areNotCounted() {
            cluster = new Cluster(true, Map.of());
            var n3 = cluster.node(N3);

            cluster.lose(N3, N1);
            n3.manager.leaderPreVoteResponse(new LeaderPreVoteResponse(N2, N1, 9_999L, false));
            n3.manager.leaderPreVoteResponse(new LeaderPreVoteResponse(N4, N1, 9_999L, false));
            sleep(SETTLE_MS / 2);

            assertThat(n3.isLedBy(N1)).as("two stale doubts plus N3's own are not a majority of this round").isTrue();
            assertThat(n3.proposals.get()).isZero();
        }
    }

    @Nested
    class WhenTheLeaderIsGenuinelyGone {
        /// Liveness control for the pin above: a dead leader is replaced. Every survivor loses it, a majority
        /// doubts it, the survivors re-elect and the lowest-ranked proposes.
        @Test
        void deadLeader_isReplacedWithinTheBound() {
            cluster = new Cluster(true, Map.of());
            var started = System.nanoTime();

            cluster.kill(N1);
            List.of(N2, N3, N4, N5).forEach(id -> cluster.lose(id, N1));

            assertThat(await(() -> List.of(N2, N3, N4, N5).stream().allMatch(id -> cluster.node(id).isReElecting()),
                             1_500)).as("all survivors left Led").isTrue();
            assertThat(await(() -> cluster.node(N2).proposals.get() >= 1, 2_000)).as("a survivor proposed").isTrue();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                .as("dead leader replaced well inside the lease bound")
                .isLessThan(3_000L);
        }

        /// Detection skew: N2 loses the leader first, the others later. N2 must wait for a majority to doubt
        /// (itself plus two more) — and must then proceed, through its retry, without any new event of its own.
        @Test
        void staggeredDetection_proceedsOnceAMajorityDoubts() {
            cluster = new Cluster(true, Map.of());
            cluster.kill(N1);

            cluster.lose(N2, N1);
            sleep(250);
            assertThat(cluster.node(N2).isLedBy(N1))
                .as("one doubter of five is not a majority — N2 waits")
                .isTrue();

            cluster.lose(N3, N1);
            sleep(250);
            assertThat(cluster.node(N2).isLedBy(N1)).as("two of five — still waiting").isTrue();

            cluster.lose(N4, N1);

            assertThat(await(() -> cluster.node(N2).isReElecting(), 1_500))
                .as("three of five doubt: N2 proceeds on its next round, with no new event")
                .isTrue();
        }

        /// The leader is partitioned away from the majority: the three-node side loses it everywhere, so it
        /// re-elects — the minority side (the leader and one follower) cannot stop that and cannot elect.
        @Test
        void leaderPartitionedWithOneFollower_majoritySideReElects() {
            cluster = new Cluster(true, Map.of());
            List.of(N1, N2).forEach(a -> List.of(N3, N4, N5).forEach(b -> cluster.cut(a, b)));

            List.of(N3, N4, N5).forEach(id -> cluster.lose(id, N1, N2));

            assertThat(await(() -> List.of(N3, N4, N5).stream().allMatch(id -> cluster.node(id).isReElecting()),
                             1_500)).as("the majority side replaces the unreachable leader").isTrue();
        }

        /// The recovery edge the lease exists for must survive the gate: the crossed-pointer wedge has every node
        /// following a different leader and nobody following itself, no `NodeGone` ever fires, and every leader
        /// ping is silent. Each node's question is about ITS leader; the others follow someone else, so they doubt
        /// it, a majority forms at once, and the cluster re-elects.
        @Test
        void crossedPointerWedge_stillRecoversThroughTheLease() {
            var allSilent = Map.<NodeId, Predicate<NodeId>>of(N1, _ -> false, N2, _ -> false, N3, _ -> false, N4, _ -> false, N5, _ -> false);

            cluster = new Cluster(true, allSilent, Map.of(N1, N2, N2, N1, N3, N2, N4, N2, N5, N2));

            assertThat(await(() -> ALL.stream().allMatch(id -> cluster.node(id).isReElecting()), 3_000))
                .as("every node left its crossed pointer for an election")
                .isTrue();
        }

        /// The voter set can change mid-round: an answer from a node that has since left the electorate no longer
        /// counts, or a departed voter's doubt could complete a majority of the new electorate.
        @Test
        void doubtFromAVoterWhoLeftMidRound_isNotCounted() {
            cluster = new Cluster(true, Map.of());
            var n3 = cluster.node(N3);

            ALL.stream().filter(id -> !id.equals(N3)).forEach(id -> cluster.cut(N3, id));
            cluster.lose(N3, N1);
            n3.manager.leaderPreVoteResponse(new LeaderPreVoteResponse(N2, N1, 1L, false));
            n3.ctx.installVoters(List.of(N1, N3, N4, N5));
            n3.manager.leaderPreVoteResponse(new LeaderPreVoteResponse(N4, N1, 1L, false));
            sleep(SETTLE_MS / 4);

            assertThat(n3.isLedBy(N1)).as("N3 + N4 is 2 of the 4 voters left, not a majority; N2's doubt left with N2").isTrue();
            assertThat(n3.proposals.get()).isZero();
        }

        /// A node that is the whole electorate has nobody to ask: it re-elects directly instead of dispatching
        /// the verdict from inside the handler that started the round.
        @Test
        void singleVoterElectorate_reElectsDirectly_withoutAskingAnyone() {
            var requests = new CopyOnWriteArrayList<Object>();
            var router = MessageRouter.mutable();

            router.addRoute(Send.class, (Consumer<Send>) requests::add);
            var h = FsmTestHarness.<LeaderElectionState, ClusterFsmEvent>harness("single-voter", fsm -> {
                var ctx = new LeaderElectionContext(fsm, N3, Option.none(), List.of(N3), router, RETRY, LONG, LONG, LONG,
                                                    LeaderElectionContext.DEFAULT_STUCK_ELECTION_THRESHOLD,
                                                    LeaderElectionContext.DEFAULT_JITTER_SOURCE,
                                                    LeaderElectionContext.DEFAULT_RABIA_TERM_SUPPLIER, () -> true, Option::none,
                                                    LONG, LONG, LONG, _ -> true, LONG);

                ctx.enablePreVote(ROUND_TIMEOUT);
                return ctx.dormant();
            });

            h.dispatch(new ClusterFsmEvent.QuorumEstablished());
            h.dispatch(new ClusterFsmEvent.NodeAdded(N3, List.of(N3, N1)));
            h.dispatch(new ConsensusReady());
            h.dispatch(new LeaderCommitted(N1, 1L));
            h.dispatch(new ClusterFsmEvent.NodeGone(N1, List.of(N3)));

            assertThat(h.state()).isInstanceOf(LeaderElectionState.ReElecting.class);
            assertThat(requests).isEmpty();
            h.dispatch(new ClusterFsmEvent.Shutdown());
        }

        /// Without the pre-vote enabled (local mode, stubs) the edge is the old immediate one.
        @Test
        void preVoteNotEnabled_lossOfLeaderIsImmediate() {
            cluster = new Cluster(false, Map.of());

            cluster.lose(N3, N1);

            assertThat(cluster.node(N3).isReElecting()).isTrue();
        }

        /// #1815 interplay: a leader the electorate voted OUT has nothing to be vouched for.
        @Test
        void votedOutLeader_isLostImmediately_withoutAskingAnyone() {
            cluster = new Cluster(true, Map.of());
            cluster.node(N3).ctx.installVoters(List.of(N2, N3, N4, N5));

            cluster.lose(N3, N1);

            assertThat(cluster.node(N3).isReElecting()).isTrue();
            assertThat(cluster.requests).isEmpty();
        }
    }

    @Nested
    class VoterStance {
        private void stanceNode(boolean ignored) {
            cluster = new Cluster(true, Map.of());
        }

        private List<LeaderPreVoteResponse> askAbout(NodeId voter, NodeId leader) {
            var answers = new CopyOnWriteArrayList<LeaderPreVoteResponse>();
            var router = (MessageRouter.MutableRouter) cluster.node(voter).ctx.router();

            router.addRoute(Send.class,
                            (Consumer<Send>) send -> {
                                if (send.payload() instanceof LeaderPreVoteResponse response) {
                                    answers.add(response);
                                }
                            });
            cluster.node(voter).manager.leaderPreVoteRequest(new LeaderPreVoteRequest(N5, leader, 7L));

            return answers;
        }

        @Test
        void followerThatStillSeesTheLeader_vouches() {
            stanceNode(true);

            assertThat(askAbout(N2, N1)).singleElement().satisfies(r -> {
                assertThat(r.leaderHealthy()).isTrue();
                assertThat(r.round()).isEqualTo(7L);
                assertThat(r.leader()).isEqualTo(N1);
            });
        }

        @Test
        void leaderAskedAboutItself_vouches() {
            stanceNode(false);

            assertThat(askAbout(N1, N1)).singleElement().satisfies(r -> assertThat(r.leaderHealthy()).isTrue());
        }

        @Test
        void followerThatLostTheLeaderFromItsView_doubts() {
            stanceNode(true);
            cluster.node(N2).ctx.setCurrentTopology(List.of(N2, N3, N4, N5));

            assertThat(askAbout(N2, N1)).singleElement().satisfies(r -> assertThat(r.leaderHealthy()).isFalse());
        }

        @Test
        void followerOfAnotherLeader_doubtsThisOne() {
            stanceNode(true);

            assertThat(askAbout(N2, N3)).singleElement().satisfies(r -> assertThat(r.leaderHealthy()).isFalse());
        }

        @Test
        void nodeWithNoViewOfTheLeadership_doesNotAnswer() {
            cluster = new Cluster(true, Map.of());
            var quorumLost = cluster.node(N2);

            quorumLost.harness.dispatch(new ClusterFsmEvent.QuorumDisappeared());

            assertThat(askAbout(N2, N1)).isEmpty();
        }

        @Test
        void requestFromANonVoter_isNotAnswered() {
            stanceNode(true);
            var answers = new CopyOnWriteArrayList<LeaderPreVoteResponse>();

            ((MessageRouter.MutableRouter) cluster.node(N2).ctx.router()).addRoute(Send.class,
                                                                                   (Consumer<Send>) send -> {
                                                                                       if (send.payload() instanceof LeaderPreVoteResponse r) {
                                                                                           answers.add(r);
                                                                                       }
                                                                                   });
            cluster.node(N2).manager.leaderPreVoteRequest(new LeaderPreVoteRequest(new NodeId("stranger"), N1, 1L));

            assertThat(answers).isEmpty();
        }
    }
}
