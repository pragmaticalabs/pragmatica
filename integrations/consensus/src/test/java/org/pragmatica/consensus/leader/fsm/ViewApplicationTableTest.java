/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package org.pragmatica.consensus.leader.fsm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.consensus.leader.LeaderManager.FsmBackedLeaderManager;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.ConsensusReady;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.ElectionTick;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderCommitted;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.PeerRemovedFromView;
import org.pragmatica.consensus.topology.TransportObservation.ObservationSource;
import org.pragmatica.consensus.topology.TransportObservation.PeerDisconnected;
import org.pragmatica.consensus.topology.TransportObservation.PeerJoined;
import org.pragmatica.consensus.topology.TransportObservation.PeerObservedFaulty;
import org.pragmatica.consensus.topology.TransportObservation.PeerReconnected;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.FsmTestHarness;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// #3092 + #3426 (ruling d73377451d): view application in the leader-election FSM, pinned cell by cell as
/// (state x view event x mode) -> (resulting state, leader). The leader column is [LeaderElectionContext#currentLeader()],
/// the leader consumers read; `-` is none.
///
/// Every cell starts node 2 in the state, with the eligible view `[N1, N2]` at sequence 1 applied; `Led` follows N1
/// (reached the production way: quorum, view, consensus ready, the commit of N1). The pre-vote is off, so a lost
/// leader is observable as `ReElecting`. View events go through the manager's production paths (`peerJoined`,
/// `peerDisconnected`) at sequence 2; mode `local` is a context with no proposal handler and a local manager, mode
/// `cluster` a context with a handler and a cluster manager. The table follows from three invariants, nothing else:
/// - I1: the applied view is the event's view, an empty one included.
/// - I2: after any view, the leader is in the applied view or is none. A cluster `Led` loses a leader the view lacks;
///   a local manager elects the first node of the applied view before the event reaches the FSM (#3044), so local
///   `Led` swaps to it, and the states that adopt a committed leader (`QuorumWaiting`, `AwaitingKvSync`, the
///   electing states in local mode) adopt it. An empty view elects nobody.
/// - I3: a leaderless electing state progresses on its own tick in local mode (it elects the first node of the
///   view); in cluster mode a tick submits a proposal and the leader comes from the consensus commit.
/// States that hold no leader and do not adopt one (`Dormant`, `QuorumLost`, `Stopped`) only store the view, in both
/// modes; `Passive` stores it and drops an observed leader the view lacks (I2, F-a4). (`Stopped` and `Passive` used to
/// ignore views in cluster mode, so a readmitted worker read a replaced view.)
@Timeout(30)
class ViewApplicationTableTest {
    private static final NodeId N1 = new NodeId("node-1");
    private static final NodeId N2 = new NodeId("node-2");
    private static final NodeId N3 = new NodeId("node-3");
    private static final List<NodeId> ALL = List.of(N1, N2, N3);
    private static final List<NodeId> INITIAL_VIEW = List.of(N1, N2);
    private static final TimeSpan LONG = TimeSpan.timeSpan(60).seconds();

    /// The cells. Columns: state, event, mode, resulting state, leader. Events (all at sequence 2 unless noted):
    /// NODE_ADDED = N3 joins, view [N1 N2 N3]; NODE_ADDED_NO_LEADER = N3 joins, view [N2 N3] (#3426, Led :514);
    /// GONE_LEADER = N1 evicted, view [N2]; GONE_OTHER = N3 evicted, view [N1 N2]; GONE_OTHER_NO_LEADER = N3
    /// evicted, view [N2] (#3426, Led :600); EMPTY = N1 evicted, view [] (#3092); REJOIN = EMPTY, then N1 rejoins
    /// with view [N1 N2] at sequence 3 (#3092, v3436a P1a); TICK = an election tick; REMOVED_LEADER = SWIM marks N1
    /// faulty, the view-free removal (`PeerRemovedFromView`), view [N2] (#3436 F-a2). `Passive` starts observing the
    /// committed N1 and drops it when a view lacks it (I2 binds every state, F-a4).
    private static final String TABLE = """
        DORMANT          NODE_ADDED           LOCAL   Dormant         -
        DORMANT          NODE_ADDED_NO_LEADER LOCAL   Dormant         -
        DORMANT          GONE_LEADER          LOCAL   Dormant         -
        DORMANT          GONE_OTHER           LOCAL   Dormant         -
        DORMANT          GONE_OTHER_NO_LEADER LOCAL   Dormant         -
        DORMANT          EMPTY                LOCAL   Dormant         -
        DORMANT          REJOIN               LOCAL   Dormant         -
        DORMANT          TICK                 LOCAL   Dormant         -
        DORMANT          NODE_ADDED           CLUSTER Dormant         -
        DORMANT          NODE_ADDED_NO_LEADER CLUSTER Dormant         -
        DORMANT          GONE_LEADER          CLUSTER Dormant         -
        DORMANT          GONE_OTHER           CLUSTER Dormant         -
        DORMANT          GONE_OTHER_NO_LEADER CLUSTER Dormant         -
        DORMANT          EMPTY                CLUSTER Dormant         -
        DORMANT          REJOIN               CLUSTER Dormant         -
        DORMANT          TICK                 CLUSTER Dormant         -
        DORMANT          REMOVED_LEADER       LOCAL   Dormant         -
        DORMANT          REMOVED_LEADER       CLUSTER Dormant         -
        QUORUM_WAITING   NODE_ADDED           LOCAL   Led             N1
        QUORUM_WAITING   NODE_ADDED_NO_LEADER LOCAL   Led             N2
        QUORUM_WAITING   GONE_LEADER          LOCAL   Led             N2
        QUORUM_WAITING   GONE_OTHER           LOCAL   Led             N1
        QUORUM_WAITING   GONE_OTHER_NO_LEADER LOCAL   Led             N2
        QUORUM_WAITING   EMPTY                LOCAL   QuorumWaiting   -
        QUORUM_WAITING   REJOIN               LOCAL   Led             N1
        QUORUM_WAITING   TICK                 LOCAL   QuorumWaiting   -
        QUORUM_WAITING   NODE_ADDED           CLUSTER QuorumWaiting   -
        QUORUM_WAITING   NODE_ADDED_NO_LEADER CLUSTER QuorumWaiting   -
        QUORUM_WAITING   GONE_LEADER          CLUSTER QuorumWaiting   -
        QUORUM_WAITING   GONE_OTHER           CLUSTER QuorumWaiting   -
        QUORUM_WAITING   GONE_OTHER_NO_LEADER CLUSTER QuorumWaiting   -
        QUORUM_WAITING   EMPTY                CLUSTER QuorumWaiting   -
        QUORUM_WAITING   REJOIN               CLUSTER QuorumWaiting   -
        QUORUM_WAITING   TICK                 CLUSTER QuorumWaiting   -
        QUORUM_WAITING   REMOVED_LEADER       LOCAL   Led             N2
        QUORUM_WAITING   REMOVED_LEADER       CLUSTER QuorumWaiting   -
        AWAITING_KV_SYNC NODE_ADDED           LOCAL   Led             N1
        AWAITING_KV_SYNC NODE_ADDED_NO_LEADER LOCAL   Led             N2
        AWAITING_KV_SYNC GONE_LEADER          LOCAL   Led             N2
        AWAITING_KV_SYNC GONE_OTHER           LOCAL   Led             N1
        AWAITING_KV_SYNC GONE_OTHER_NO_LEADER LOCAL   Led             N2
        AWAITING_KV_SYNC EMPTY                LOCAL   AwaitingKvSync  -
        AWAITING_KV_SYNC REJOIN               LOCAL   Led             N1
        AWAITING_KV_SYNC TICK                 LOCAL   AwaitingKvSync  -
        AWAITING_KV_SYNC NODE_ADDED           CLUSTER AwaitingKvSync  -
        AWAITING_KV_SYNC NODE_ADDED_NO_LEADER CLUSTER AwaitingKvSync  -
        AWAITING_KV_SYNC GONE_LEADER          CLUSTER AwaitingKvSync  -
        AWAITING_KV_SYNC GONE_OTHER           CLUSTER AwaitingKvSync  -
        AWAITING_KV_SYNC GONE_OTHER_NO_LEADER CLUSTER AwaitingKvSync  -
        AWAITING_KV_SYNC EMPTY                CLUSTER AwaitingKvSync  -
        AWAITING_KV_SYNC REJOIN               CLUSTER AwaitingKvSync  -
        AWAITING_KV_SYNC TICK                 CLUSTER AwaitingKvSync  -
        AWAITING_KV_SYNC REMOVED_LEADER       LOCAL   Led             N2
        AWAITING_KV_SYNC REMOVED_LEADER       CLUSTER AwaitingKvSync  -
        ELECTING         NODE_ADDED           LOCAL   Led             N1
        ELECTING         NODE_ADDED_NO_LEADER LOCAL   Led             N2
        ELECTING         GONE_LEADER          LOCAL   Led             N2
        ELECTING         GONE_OTHER           LOCAL   Led             N1
        ELECTING         GONE_OTHER_NO_LEADER LOCAL   Led             N2
        ELECTING         EMPTY                LOCAL   Electing        -
        ELECTING         REJOIN               LOCAL   Led             N1
        ELECTING         TICK                 LOCAL   Led             N1
        ELECTING         NODE_ADDED           CLUSTER Electing        -
        ELECTING         NODE_ADDED_NO_LEADER CLUSTER Electing        -
        ELECTING         GONE_LEADER          CLUSTER Electing        -
        ELECTING         GONE_OTHER           CLUSTER Electing        -
        ELECTING         GONE_OTHER_NO_LEADER CLUSTER Electing        -
        ELECTING         EMPTY                CLUSTER Electing        -
        ELECTING         REJOIN               CLUSTER Electing        -
        ELECTING         TICK                 CLUSTER Electing        -
        ELECTING         REMOVED_LEADER       LOCAL   Led             N2
        ELECTING         REMOVED_LEADER       CLUSTER Electing        -
        LED              NODE_ADDED           LOCAL   Led             N1
        LED              NODE_ADDED_NO_LEADER LOCAL   Led             N2
        LED              GONE_LEADER          LOCAL   Led             N2
        LED              GONE_OTHER           LOCAL   Led             N1
        LED              GONE_OTHER_NO_LEADER LOCAL   Led             N2
        LED              EMPTY                LOCAL   ReElecting      -
        LED              REJOIN               LOCAL   Led             N1
        LED              TICK                 LOCAL   Led             N1
        LED              NODE_ADDED           CLUSTER Led             N1
        LED              NODE_ADDED_NO_LEADER CLUSTER ReElecting      -
        LED              GONE_LEADER          CLUSTER ReElecting      -
        LED              GONE_OTHER           CLUSTER Led             N1
        LED              GONE_OTHER_NO_LEADER CLUSTER ReElecting      -
        LED              EMPTY                CLUSTER ReElecting      -
        LED              REJOIN               CLUSTER ReElecting      -
        LED              TICK                 CLUSTER Led             N1
        LED              REMOVED_LEADER       LOCAL   Led             N2
        LED              REMOVED_LEADER       CLUSTER ReElecting      -
        RE_ELECTING      NODE_ADDED           LOCAL   Led             N1
        RE_ELECTING      NODE_ADDED_NO_LEADER LOCAL   Led             N2
        RE_ELECTING      GONE_LEADER          LOCAL   Led             N2
        RE_ELECTING      GONE_OTHER           LOCAL   Led             N1
        RE_ELECTING      GONE_OTHER_NO_LEADER LOCAL   Led             N2
        RE_ELECTING      EMPTY                LOCAL   ReElecting      -
        RE_ELECTING      REJOIN               LOCAL   Led             N1
        RE_ELECTING      TICK                 LOCAL   Led             N1
        RE_ELECTING      NODE_ADDED           CLUSTER ReElecting      -
        RE_ELECTING      NODE_ADDED_NO_LEADER CLUSTER ReElecting      -
        RE_ELECTING      GONE_LEADER          CLUSTER ReElecting      -
        RE_ELECTING      GONE_OTHER           CLUSTER ReElecting      -
        RE_ELECTING      GONE_OTHER_NO_LEADER CLUSTER ReElecting      -
        RE_ELECTING      EMPTY                CLUSTER ReElecting      -
        RE_ELECTING      REJOIN               CLUSTER ReElecting      -
        RE_ELECTING      TICK                 CLUSTER ReElecting      -
        RE_ELECTING      REMOVED_LEADER       LOCAL   Led             N2
        RE_ELECTING      REMOVED_LEADER       CLUSTER ReElecting      -
        QUORUM_LOST      NODE_ADDED           LOCAL   QuorumLost      -
        QUORUM_LOST      NODE_ADDED_NO_LEADER LOCAL   QuorumLost      -
        QUORUM_LOST      GONE_LEADER          LOCAL   QuorumLost      -
        QUORUM_LOST      GONE_OTHER           LOCAL   QuorumLost      -
        QUORUM_LOST      GONE_OTHER_NO_LEADER LOCAL   QuorumLost      -
        QUORUM_LOST      EMPTY                LOCAL   QuorumLost      -
        QUORUM_LOST      REJOIN               LOCAL   QuorumLost      -
        QUORUM_LOST      TICK                 LOCAL   QuorumLost      -
        QUORUM_LOST      NODE_ADDED           CLUSTER QuorumLost      -
        QUORUM_LOST      NODE_ADDED_NO_LEADER CLUSTER QuorumLost      -
        QUORUM_LOST      GONE_LEADER          CLUSTER QuorumLost      -
        QUORUM_LOST      GONE_OTHER           CLUSTER QuorumLost      -
        QUORUM_LOST      GONE_OTHER_NO_LEADER CLUSTER QuorumLost      -
        QUORUM_LOST      EMPTY                CLUSTER QuorumLost      -
        QUORUM_LOST      REJOIN               CLUSTER QuorumLost      -
        QUORUM_LOST      TICK                 CLUSTER QuorumLost      -
        QUORUM_LOST      REMOVED_LEADER       LOCAL   QuorumLost      -
        QUORUM_LOST      REMOVED_LEADER       CLUSTER QuorumLost      -
        STOPPED          NODE_ADDED           LOCAL   Stopped         -
        STOPPED          NODE_ADDED_NO_LEADER LOCAL   Stopped         -
        STOPPED          GONE_LEADER          LOCAL   Stopped         -
        STOPPED          GONE_OTHER           LOCAL   Stopped         -
        STOPPED          GONE_OTHER_NO_LEADER LOCAL   Stopped         -
        STOPPED          EMPTY                LOCAL   Stopped         -
        STOPPED          REJOIN               LOCAL   Stopped         -
        STOPPED          TICK                 LOCAL   Stopped         -
        STOPPED          NODE_ADDED           CLUSTER Stopped         -
        STOPPED          NODE_ADDED_NO_LEADER CLUSTER Stopped         -
        STOPPED          GONE_LEADER          CLUSTER Stopped         -
        STOPPED          GONE_OTHER           CLUSTER Stopped         -
        STOPPED          GONE_OTHER_NO_LEADER CLUSTER Stopped         -
        STOPPED          EMPTY                CLUSTER Stopped         -
        STOPPED          REJOIN               CLUSTER Stopped         -
        STOPPED          TICK                 CLUSTER Stopped         -
        STOPPED          REMOVED_LEADER       LOCAL   Stopped         -
        STOPPED          REMOVED_LEADER       CLUSTER Stopped         -
        PASSIVE          NODE_ADDED           LOCAL   Passive         N1
        PASSIVE          NODE_ADDED_NO_LEADER LOCAL   Passive         -
        PASSIVE          GONE_LEADER          LOCAL   Passive         -
        PASSIVE          GONE_OTHER           LOCAL   Passive         N1
        PASSIVE          GONE_OTHER_NO_LEADER LOCAL   Passive         -
        PASSIVE          EMPTY                LOCAL   Passive         -
        PASSIVE          REJOIN               LOCAL   Passive         -
        PASSIVE          TICK                 LOCAL   Passive         N1
        PASSIVE          NODE_ADDED           CLUSTER Passive         N1
        PASSIVE          NODE_ADDED_NO_LEADER CLUSTER Passive         -
        PASSIVE          GONE_LEADER          CLUSTER Passive         -
        PASSIVE          GONE_OTHER           CLUSTER Passive         N1
        PASSIVE          GONE_OTHER_NO_LEADER CLUSTER Passive         -
        PASSIVE          EMPTY                CLUSTER Passive         -
        PASSIVE          REJOIN               CLUSTER Passive         -
        PASSIVE          TICK                 CLUSTER Passive         N1
        PASSIVE          REMOVED_LEADER       LOCAL   Passive         -
        PASSIVE          REMOVED_LEADER       CLUSTER Passive         -
        """;

    /// The applied view each event leaves (I1), the same in every state and mode.
    private static final String VIEWS = """
        NODE_ADDED           N1 N2 N3
        NODE_ADDED_NO_LEADER N2 N3
        GONE_LEADER          N2
        GONE_OTHER           N1 N2
        GONE_OTHER_NO_LEADER N2
        EMPTY
        REJOIN               N1 N2
        TICK                 N1 N2
        REMOVED_LEADER       N2
        """;

    enum Mode {
        LOCAL,
        CLUSTER
    }

    enum StartState {
        DORMANT(LeaderElectionContext::dormant, "Dormant -"),
        QUORUM_WAITING(LeaderElectionContext::quorumWaiting, "QuorumWaiting -"),
        AWAITING_KV_SYNC(LeaderElectionContext::awaitingKvSync, "AwaitingKvSync -"),
        ELECTING(LeaderElectionContext::electing, "Electing -"),
        LED(LeaderElectionContext::dormant, "Led N1"),
        RE_ELECTING(LeaderElectionContext::reElecting, "ReElecting -"),
        QUORUM_LOST(LeaderElectionContext::quorumLost, "QuorumLost -"),
        STOPPED(LeaderElectionContext::stopped, "Stopped -"),
        PASSIVE(ctx -> new LeaderElectionState.Passive(ctx, ALL), "Passive N1");

        final Function<LeaderElectionContext, LeaderElectionState> initial;
        final String started;

        StartState(Function<LeaderElectionContext, LeaderElectionState> initial, String started) {
            this.initial = initial;
            this.started = started;
        }
    }

    /// Each event names the FSM event types it delivers, read by [#everyViewEventTypeOfTheCode_hasARow()].
    enum ViewEvent {
        NODE_ADDED(node -> node.manager.peerJoined(new PeerJoined(N3, ALL, ObservationSource.QUIC, 2L)), ClusterFsmEvent.NodeAdded.class),
        NODE_ADDED_NO_LEADER(node -> node.manager.peerJoined(new PeerJoined(N3, List.of(N2, N3), ObservationSource.QUIC, 2L)), ClusterFsmEvent.NodeAdded.class),
        GONE_LEADER(node -> node.manager.peerDisconnected(new PeerDisconnected(N1, List.of(N2), ObservationSource.QUIC, 2L)), ClusterFsmEvent.NodeGone.class),
        GONE_OTHER(node -> node.manager.peerDisconnected(new PeerDisconnected(N3, INITIAL_VIEW, ObservationSource.QUIC, 2L)), ClusterFsmEvent.NodeGone.class),
        GONE_OTHER_NO_LEADER(node -> node.manager.peerDisconnected(new PeerDisconnected(N3, List.of(N2), ObservationSource.QUIC, 2L)), ClusterFsmEvent.NodeGone.class),
        EMPTY(ViewEvent::evictIntoAnEmptyView, ClusterFsmEvent.NodeGone.class),
        REJOIN(ViewEvent::evictThenRejoin, ClusterFsmEvent.NodeGone.class, ClusterFsmEvent.NodeAdded.class),
        TICK(node -> node.harness.dispatch(new ElectionTick()), ElectionTick.class),
        REMOVED_LEADER(node -> node.manager.peerObservedFaulty(new PeerObservedFaulty(N1, ALL, ObservationSource.QUIC)), PeerRemovedFromView.class);

        final Consumer<Node> apply;
        final Set<Class<?>> delivers;

        ViewEvent(Consumer<Node> apply, Class<?>... delivers) {
            this.apply = apply;
            this.delivers = Set.of(delivers);
        }

        private static void evictIntoAnEmptyView(Node node) {
            node.manager.peerDisconnected(new PeerDisconnected(N1, List.of(), ObservationSource.QUIC, 2L));
        }

        private static void evictThenRejoin(Node node) {
            evictIntoAnEmptyView(node);
            node.manager.peerJoined(new PeerJoined(N1, INITIAL_VIEW, ObservationSource.QUIC, 3L));
        }
    }

    private Node node;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.manager.stop();
        }
    }

    /// Node 2 with the real FSM and context, started in `start` (no entry action runs for a directly started state).
    private static final class Node {
        final FsmTestHarness<LeaderElectionState, ClusterFsmEvent> harness;
        final LeaderElectionContext ctx;
        final FsmBackedLeaderManager manager;

        Node(Function<LeaderElectionContext, LeaderElectionState> initial, Mode mode) {
            this(initial, mode, Option::none, LONG);
        }

        Node(Function<LeaderElectionContext, LeaderElectionState> initial,
             Mode mode,
             Supplier<Option<NodeId>> kvLeader,
             TimeSpan retryDelay) {
            var router = MessageRouter.mutable();
            var holder = new AtomicReference<LeaderElectionContext>();
            LeaderManager.LeaderProposalHandler handler = (candidate, viewSequence) -> Promise.success(Unit.unit());
            var proposalHandler = mode == Mode.LOCAL
                                  ? Option.<LeaderManager.LeaderProposalHandler>none()
                                  : Option.some(handler);

            harness = FsmTestHarness.<LeaderElectionState, ClusterFsmEvent>harness("t3092-node", fsm -> {
                var context = new LeaderElectionContext(fsm,
                                                        N2,
                                                        proposalHandler,
                                                        ALL,
                                                        router,
                                                        retryDelay,
                                                        LONG,
                                                        LONG,
                                                        LONG,
                                                        LeaderElectionContext.DEFAULT_STUCK_ELECTION_THRESHOLD,
                                                        LeaderElectionContext.DEFAULT_JITTER_SOURCE,
                                                        LeaderElectionContext.DEFAULT_RABIA_TERM_SUPPLIER,
                                                        () -> true,
                                                        kvLeader,
                                                        LONG,
                                                        LONG,
                                                        LONG,
                                                        _ -> true,
                                                        LONG);

                holder.set(context);
                return initial.apply(context);
            });
            ctx = holder.get();
            manager = new FsmBackedLeaderManager(harness.fsm(), ctx, mode == Mode.LOCAL);
        }

        static Node startedIn(StartState start, Mode mode) {
            var node = new Node(start.initial, mode);

            if (start == StartState.LED) {
                node.harness.dispatch(new ClusterFsmEvent.QuorumEstablished());
                node.harness.dispatch(new ClusterFsmEvent.NodeAdded(N2, INITIAL_VIEW, 1L));
                node.harness.dispatch(new ConsensusReady());
                node.harness.dispatch(new LeaderCommitted(N1, 1L));
            } else {
                node.ctx.applyTopology(new ClusterFsmEvent.NodeAdded(N2, INITIAL_VIEW, 1L));
            }
            if (start == StartState.PASSIVE) {
                node.harness.dispatch(new LeaderCommitted(N1, 1L));
            }

            return node;
        }

        String state() {
            return harness.state().getClass().getSimpleName();
        }

        String leader() {
            return ctx.currentLeader().map(NodeId::id).map(ViewApplicationTableTest::shortName).or("-");
        }
    }

    private static String shortName(String id) {
        return "N" + id.substring(id.lastIndexOf('-') + 1);
    }

    static Stream<Arguments> cells() {
        return TABLE.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .map(line -> line.split("\\s+"))
                    .map(c -> Arguments.of(StartState.valueOf(c[0]), ViewEvent.valueOf(c[1]), Mode.valueOf(c[2]), c[3], c[4]));
    }

    @ParameterizedTest(name = "{0} x {1} x {2} -> {3} {4}")
    @MethodSource("cells")
    void viewEvent_inEveryStateAndMode_followsTheTable(StartState start,
                                                      ViewEvent event,
                                                      Mode mode,
                                                      String expectedState,
                                                      String expectedLeader) {
        node = Node.startedIn(start, mode);
        assertThat(node.state() + " " + node.leader()).as("control: started in %s", start).isEqualTo(start.started);
        assertThat(node.ctx.isLocalMode()).as("control: mode %s", mode).isEqualTo(mode == Mode.LOCAL);

        event.apply.accept(node);

        assertThat(node.state() + " " + node.leader()).as("%s x %s x %s", start, event, mode)
                                                      .isEqualTo(expectedState + " " + expectedLeader);
        assertThat(node.ctx.currentTopology()).as("I1: the applied view after %s", event)
                                              .containsExactlyElementsOf(viewAfter(event));
        assertThat(node.leader().equals("-") || viewAfter(event).stream().map(NodeId::id).map(ViewApplicationTableTest::shortName).toList().contains(node.leader()))
            .as("I2: leader %s is in the applied view or none", node.leader())
            .isTrue();
    }

    @Test
    void table_coversEveryCell() {
        var cells = cells().map(arguments -> Arrays.asList(arguments.get()).subList(0, 3)).toList();

        assertThat(cells).as("one row per (state, event, mode)").doesNotHaveDuplicates()
                         .hasSize(StartState.values().length * ViewEvent.values().length * Mode.values().length);
    }

    /// F-a2: a value added to any column's enum without rows fails here, naming the missing cells.
    @Test
    void everyEnumValue_hasItsRows() {
        var present = cells().map(arguments -> Arrays.asList(arguments.get()).subList(0, 3)).collect(Collectors.toSet());
        var missing = Arrays.stream(StartState.values())
                            .flatMap(start -> Arrays.stream(ViewEvent.values())
                                                    .flatMap(event -> Arrays.stream(Mode.values())
                                                                            .map(mode -> List.<Object>of(start, event, mode))))
                            .filter(cell -> !present.contains(cell))
                            .toList();

        assertThat(missing).as("cells with no row").isEmpty();
    }

    /// F-a2: the state column is the code's sealed state hierarchy, one row block per permitted state.
    @Test
    void startStates_areTheSealedStatesOfTheCode() {
        var code = Arrays.stream(LeaderElectionState.class.getPermittedSubclasses()).map(Class::getSimpleName).collect(Collectors.toSet());
        var table = Arrays.stream(StartState.values()).map(start -> start.started.split(" ")[0]).collect(Collectors.toSet());

        assertThat(table).as("StartState covers every permitted LeaderElectionState").isEqualTo(code);
        assertThat(StartState.values()).hasSameSizeAs(LeaderElectionState.class.getPermittedSubclasses());
    }

    /// F-a2: every FSM event type the code declares is either delivered by a table event or listed here as one that
    /// writes no view (none of them reaches `storeView` / `storeRemoval`). A new event type fails until it is
    /// classified; a view-writing one needs a row. `TopologyChange`'s permits are reflected, so a new view type is
    /// not listable as non-view.
    @Test
    void everyViewEventTypeOfTheCode_hasARow() {
        var nonView = Set.<Class<?>>of(ClusterFsmEvent.QuorumEstablished.class,
                                       ClusterFsmEvent.QuorumDisappeared.class,
                                       ClusterFsmEvent.LeaderChange.class,
                                       ClusterFsmEvent.Shutdown.class,
                                       LeaderCommitted.class,
                                       LeaderElectionEvents.PassiveDirectory.class,
                                       LeaderElectionEvents.VoterReadmitted.class,
                                       ConsensusReady.class,
                                       ElectionTick.class,
                                       LeaderElectionEvents.ProposalSettled.class,
                                       LeaderElectionEvents.KvSyncGraceTimeout.class,
                                       LeaderElectionEvents.LeaderSilent.class,
                                       LeaderElectionEvents.LeaderDoubtConfirmed.class,
                                       LeaderElectionEvents.LeaderOutOfView.class);
        var declared = Stream.concat(Arrays.stream(ClusterFsmEvent.class.getDeclaredClasses()),
                                     Arrays.stream(LeaderElectionEvents.class.getDeclaredClasses()))
                             .filter(Class::isRecord)
                             .collect(Collectors.toSet());
        var view = declared.stream().filter(type -> !nonView.contains(type)).collect(Collectors.toSet());
        var delivered = Arrays.stream(ViewEvent.values()).flatMap(event -> event.delivers.stream()).collect(Collectors.toSet());

        assertThat(declared).as("control: the reflected event types include the non-view list").containsAll(nonView);
        assertThat(view).as("view-writing event types").containsAll(List.of(ClusterFsmEvent.TopologyChange.class.getPermittedSubclasses()))
                        .contains(PeerRemovedFromView.class);
        assertThat(delivered).as("every view-writing event type has a table event").containsAll(view);
    }

    private static List<NodeId> viewAfter(ViewEvent event) {
        return VIEWS.lines()
                    .map(String::trim)
                    .map(line -> line.split("\\s+"))
                    .filter(c -> c[0].equals(event.name()))
                    .findFirst()
                    .map(c -> Arrays.stream(c).skip(1).map(ViewApplicationTableTest::nodeOf).toList())
                    .orElseThrow();
    }

    private static NodeId nodeOf(String shortName) {
        return new NodeId("node-" + shortName.substring(1));
    }

    // ---- v3436a probes P1a-P1d, replayed (the shape the verifier used: the eligible set narrowed to N1, local mode) ----

    private FsmBackedLeaderManager evictTheOnlyEligibleLeader() {
        node = Node.startedIn(StartState.LED, Mode.LOCAL);
        node.ctx.installVoters(List.of(N1));
        node.manager.peerDisconnected(new PeerDisconnected(N1, List.of(N2, N3), ObservationSource.QUIC, 2L));
        assertThat(node.state()).as("control: the empty eligible view loses the leader").isEqualTo("ReElecting");
        assertThat(node.ctx.currentTopology()).as("control: I1, the empty view is the applied view").isEmpty();

        return node.manager;
    }

    @Test
    void p1a_emptyEviction_thenTheEvictedLeaderRejoins_followsItAgain() {
        evictTheOnlyEligibleLeader().peerJoined(new PeerJoined(N1, ALL, ObservationSource.QUIC, 3L));

        assertThat(node.state() + " " + node.leader()).isEqualTo("Led N1");
    }

    @Test
    void p1b_emptyEviction_thenANewlyEligiblePeerJoins_followsIt() {
        var local = evictTheOnlyEligibleLeader();
        node.ctx.installVoters(List.of(N1, N3));
        local.peerJoined(new PeerJoined(N3, List.of(N2, N3), ObservationSource.QUIC, 3L));

        assertThat(node.state() + " " + node.leader()).isEqualTo("Led N3");
    }

    @Test
    void p1c_emptyEviction_quorumFlapThenRejoin_followsTheLeader() {
        var local = evictTheOnlyEligibleLeader();
        node.harness.dispatch(new ClusterFsmEvent.QuorumDisappeared());
        node.harness.dispatch(new ClusterFsmEvent.QuorumEstablished());
        local.peerJoined(new PeerJoined(N1, ALL, ObservationSource.QUIC, 3L));

        assertThat(node.state() + " " + node.leader()).isEqualTo("Led N1");
    }

    @Test
    void p1d_emptyEviction_faultFlapAndStaleJoin_neverReadoptsTheEvictedLeader_thenANewerJoinElectsFromTheView() {
        var local = evictTheOnlyEligibleLeader();
        local.peerObservedFaulty(new PeerObservedFaulty(N3, ALL, ObservationSource.QUIC));
        assertThat(node.state()).as("a fault of another peer in an empty view changes nothing").isEqualTo("ReElecting");
        node.harness.dispatch(new ClusterFsmEvent.QuorumDisappeared());
        node.harness.dispatch(new ClusterFsmEvent.QuorumEstablished());
        node.ctx.installVoters(List.of(N1, N3));
        local.peerJoined(new PeerJoined(N3, List.of(N2, N3), ObservationSource.QUIC, 1L));

        assertThat(node.leader()).as("the stale join is dropped and the evicted N1 is not re-adopted").isEqualTo("-");
        assertThat(node.ctx.currentTopology()).as("I1: the stored view is still the empty one").isEmpty();

        local.peerJoined(new PeerJoined(N3, List.of(N2, N3), ObservationSource.QUIC, 3L));

        assertThat(node.state() + " " + node.leader()).as("a newer view elects from the applied view").isEqualTo("Led N3");
    }

    // ---- #3436 fix round: the one I2 site and its admission (F-a1, item 5) and the sites item 6 names ----

    private Node localNode(StartState start, Supplier<Option<NodeId>> kvLeader, TimeSpan retryDelay) {
        node = new Node(start.initial, Mode.LOCAL, kvLeader, retryDelay);
        node.ctx.applyTopology(new ClusterFsmEvent.NodeAdded(N2, INITIAL_VIEW, 1L));

        return node;
    }

    private boolean enteredLed() {
        return node.harness.transitions().stream().anyMatch(transition -> transition.to() instanceof LeaderElectionState.Led);
    }

    /// F-a1: a local `Led` receives a commit of a node its stored view lacks (a stale local commit: the KV push of a
    /// leader the transport has since evicted). At 3fada0b8c3 the swap path adopted it (FIX 1) and the node followed a
    /// leader outside the view; the one I2 site on the local `Led` entry drops that tenure before it is published.
    @Test
    void f_a1_localLed_staleCommitOfANodeOutsideTheView_neverFollowsIt() {
        node = Node.startedIn(StartState.LED, Mode.LOCAL);

        node.harness.dispatch(new LeaderCommitted(N3));

        assertThat(node.state() + " " + node.leader()).as("I2: the leader is in the view or none").isEqualTo("ReElecting -");
    }

    /// A `LeaderOutOfView` is fenced by the carried leader: a stale check made for an earlier tenure (naming a leader
    /// other than the current one) is discarded in `Led`; the state and the published leader are unchanged.
    @Test
    void staleLeaderOutOfView_namingAnotherLeader_isDiscardedInLed() {
        node = Node.startedIn(StartState.LED, Mode.LOCAL);

        node.harness.dispatch(new LeaderElectionEvents.LeaderOutOfView(N3));

        assertThat(node.state() + " " + node.leader()).as("stale out-of-view for another leader changes nothing").isEqualTo("Led N1");
    }

    /// Item 5 (v3436c's survivor): an evicted leader's stale local commit reaches `ReElecting`. The admission refuses it,
    /// so no `Led` tenure is ever entered (the post-change site alone would enter one and then drop it).
    @Test
    void item5_reElecting_staleLocalCommitOfTheEvictedLeader_isNotAdmitted() {
        localNode(StartState.RE_ELECTING, Option::none, LONG);
        node.ctx.applyTopology(new ClusterFsmEvent.NodeGone(N1, List.of(), 2L));

        node.harness.dispatch(new LeaderCommitted(N1));

        assertThat(node.state() + " " + node.leader()).isEqualTo("ReElecting -");
        assertThat(enteredLed()).as("no Led tenure for a leader outside the view").isFalse();
    }

    /// Item 5, the livelock the admission prevents: the KV keeps the evicted leader, and every `ReElecting` entry
    /// re-pulls it. Without the admission, admit -> drop -> re-enter -> re-pull recurses on the dispatching thread.
    @Test
    void item5_reElecting_kvKeepsTheEvictedLeader_settlesWithoutALedTenure() {
        localNode(StartState.LED, () -> Option.some(N1), LONG);
        node.harness.dispatch(new ClusterFsmEvent.QuorumEstablished());
        node.harness.dispatch(new ConsensusReady());
        node.harness.dispatch(new LeaderCommitted(N1, 1L));
        assertThat(node.state() + " " + node.leader()).as("control: Led N1").isEqualTo("Led N1");
        var before = node.harness.transitions().size();

        node.manager.peerDisconnected(new PeerDisconnected(N1, List.of(), ObservationSource.QUIC, 2L));

        assertThat(node.state() + " " + node.leader()).isEqualTo("ReElecting -");
        assertThat(node.harness.transitions().subList(before, node.harness.transitions().size()))
            .as("Led -> ReElecting once, no re-adoption")
            .extracting(transition -> transition.to().getClass().getSimpleName())
            .containsExactly("ReElecting");
    }

    /// Item 6: the adopted sequence a local adoption records is the fence of the next swap (FIX 2): a sequenced commit
    /// at or below the committed sequence this node observed is discarded.
    @Test
    void item6_localAdoption_recordsTheObservedCommittedSequence() {
        localNode(StartState.RE_ELECTING, Option::none, LONG);
        node.ctx.observeViewSequence(5L);

        node.harness.dispatch(new LeaderCommitted(N1));
        assertThat(node.ctx.adoptedViewSequence()).as("recorded at adoption").isEqualTo(5L);
        node.harness.dispatch(new LeaderCommitted(N2, 3L));

        assertThat(node.state() + " " + node.leader()).as("the lower-sequence swap is fenced").isEqualTo("Led N1");
    }

    /// Item 6: a tick whose KV pull already moved the FSM to `Led` elects nothing more; without the `current() !=
    /// owner` guard the tick would commit the first node of the view and swap the leader the KV named.
    @Test
    void item6_localTick_afterTheKvPullMovedTheFsm_electsNothing() {
        localNode(StartState.ELECTING, () -> Option.some(N2), LONG);

        node.harness.dispatch(new ElectionTick());

        assertThat(node.state() + " " + node.leader()).isEqualTo("Led N2");
    }

    /// Item 6: a local tick over an empty view reschedules, so the node elects once a view appears without any event.
    @Test
    void item6_localTick_onAnEmptyView_reschedulesAndElectsWhenAViewAppears() {
        localNode(StartState.ELECTING, Option::none, TimeSpan.timeSpan(20).millis());
        node.ctx.applyTopology(new ClusterFsmEvent.NodeGone(N1, List.of(), 2L));

        node.harness.dispatch(new ElectionTick());
        assertThat(node.state()).as("control: nothing to elect").isEqualTo("Electing");
        node.ctx.applyTopology(new ClusterFsmEvent.NodeAdded(N1, INITIAL_VIEW, 3L));

        var deadline = System.nanoTime() + 5_000_000_000L;
        while (!node.state().equals("Led") && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(node.state() + " " + node.leader()).as("the rescheduled tick elected").isEqualTo("Led N1");
    }

    /// Item 6: a reconnect that restores the view stores it, but never elects, in local mode either (#3044: a
    /// reconnect never adopts). The leader the eviction left is in the restored view (I2 holds), so it stays; electing
    /// from the restored view would swap it for the view's first node (integrations/cluster
    /// `LeaderManagerLocalModeOrderTest.localMode_staleEvictionAfterLowerNodeReconnect_keepsTheAdoptedLeader`).
    @Test
    void item6_localReconnectRestoringTheView_storesItAndKeepsTheLeader() {
        node = Node.startedIn(StartState.LED, Mode.LOCAL);
        node.manager.peerDisconnected(new PeerDisconnected(N1, List.of(N2), ObservationSource.QUIC, 2L));
        assertThat(node.state() + " " + node.leader()).as("control").isEqualTo("Led N2");

        node.manager.peerReconnected(new PeerReconnected(N1, INITIAL_VIEW, ObservationSource.QUIC, 3L));

        assertThat(node.ctx.currentTopology()).as("the reconnect restored the view").contains(N1);
        assertThat(node.state() + " " + node.leader()).as("a reconnect never elects").isEqualTo("Led N2");
    }
}
