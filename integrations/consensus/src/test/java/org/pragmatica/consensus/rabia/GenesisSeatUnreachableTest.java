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
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestClusterNetwork;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestCommand;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestStateMachine;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestTopologyManager;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.GenesisAnnouncement;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.SyncResponse;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.utility.warning.OperatorWarningCode.GENESIS_SEAT_REACHABLE;
import static org.pragmatica.utility.warning.OperatorWarningCode.GENESIS_SEAT_UNREACHABLE;

/// #1654 engine half: the genesis view agreement counts a member's last two reports with no freshness, so a member that
/// reported the view twice and then died is still agreed. Formation goes ahead (ruling: keep forming) and the engine raises
/// the operator warning `genesis-seat-unreachable` for the dead seat, closed by `genesis-seat-reachable` when the seat is a
/// connected peer again or leaves the voter roster.
///
/// B is genesis-pending (configured count 3) with candidates X and Y, as in `GenesisJoinRuleTest`. The sink is the real
/// hand-off sink; a sentinel pushed through it after the engine settles proves every earlier warning was published.
class GenesisSeatUnreachableTest {
    private static final NodeId B = new NodeId("node-2");
    private static final NodeId X = new NodeId("node-4");
    private static final NodeId Y = new NodeId("node-5");
    private static final NodeId Z = new NodeId("node-6");
    private static final ClusterConfig VIEW = new ClusterConfig(List.of(B, X, Y));

    private final AtomicInteger sentinels = new AtomicInteger();
    private final Set<NodeId> connected = ConcurrentHashMap.newKeySet();
    private final List<OperatorWarning> published = new CopyOnWriteArrayList<>();
    private final OperatorWarningSink sink = OperatorWarningSink.handingOffTo(published::add);
    private final TestClusterNetwork network = new TestClusterNetwork() {
        @Override
        public Set<NodeId> connectedPeers() {
            return Set.copyOf(connected);
        }
    };
    private RabiaEngine<TestCommand> engine;

    @AfterEach
    void stop() {
        if (engine != null) {
            engine.stop().await();
        }
    }

    @Test
    void genesisFormedWithAMemberThatStoppedReporting_raisesSeatUnreachable() {
        formWithYGone();

        assertThat(engine.voterConfiguration()).as("formation is not blocked by the dead seat")
                                               .isEqualTo(Option.some(new VoterConfiguration(0, VIEW)));
        assertThat(seatWarnings()).containsExactly(code(GENESIS_SEAT_UNREACHABLE, Y));
    }

    /// Control: the same schedule with every seat still connected forms the same roster and raises nothing, so the warning
    /// above comes from the lost connection, not from forming genesis.
    @Test
    void genesisFormedWithEverySeatConnected_raisesNothing() {
        engine = pendingB();
        engine.runGenesisRoundForTesting();
        engine.runGenesisRoundForTesting();
        settle();
        announceStably(Y);
        settle();
        announceStably(X);
        settle();
        engine.runPhaseStallCheckForTesting();
        settle();

        assertThat(engine.voterConfiguration()).isEqualTo(Option.some(new VoterConfiguration(0, VIEW)));
        assertThat(seatWarnings()).isEmpty();
    }

    @Test
    void unreachableSeat_reconnects_isClosedOnceBySeatReachable() {
        formWithYGone();
        engine.runPhaseStallCheckForTesting();
        settle();
        assertThat(seatWarnings()).as("still unreachable: nothing closes").containsExactly(code(GENESIS_SEAT_UNREACHABLE, Y));

        connected.add(Y);
        engine.runPhaseStallCheckForTesting();
        engine.runPhaseStallCheckForTesting();
        settle();

        assertThat(seatWarnings()).containsExactly(code(GENESIS_SEAT_UNREACHABLE, Y), code(GENESIS_SEAT_REACHABLE, Y));
    }

    /// `cluster.genesis_voters` fixes the view, so a seat can be agreed and connected while membership discovery does not
    /// count it yet. Connectivity decides, not discovery: no warning, and nothing to close.
    @Test
    void fixedViewSeatConnectedButNotDiscovered_raisesNothing() {
        engine = pendingB(Set.of(B), Option.some(VIEW));
        engine.runGenesisRoundForTesting();
        engine.runGenesisRoundForTesting();
        settle();
        announceStably(Y);
        announceStably(X);
        settle();
        engine.runPhaseStallCheckForTesting();
        settle();

        assertThat(engine.voterConfiguration()).as("precondition: genesis formed on the fixed view")
                                               .isEqualTo(Option.some(new VoterConfiguration(0, VIEW)));
        assertThat(seatWarnings()).isEmpty();
    }

    /// F2: the engine is Idle once genesis has formed (no phase, no stall detector), so the close must also run on the idle
    /// slot probe tick; the stall tick is never invoked here.
    @Test
    void unreachableSeat_reconnectsWhileEngineIdle_isClosedByTheIdleProbeTick() {
        formWithYGone();
        engine.runQuietSlotProbeForTesting();
        settle();
        assertThat(seatWarnings()).as("still unreachable: nothing closes").containsExactly(code(GENESIS_SEAT_UNREACHABLE, Y));

        connected.add(Y);
        engine.runQuietSlotProbeForTesting();
        settle();

        assertThat(seatWarnings()).containsExactly(code(GENESIS_SEAT_UNREACHABLE, Y), code(GENESIS_SEAT_REACHABLE, Y));
    }

    /// The replacement arm: a LIVE sync answer carries a newer roster without the seat (the committed reconfiguration that
    /// replaced it), which the engine adopts; the next tick closes the warning.
    @Test
    void unreachableSeat_leavesTheVoterRoster_isClosedBySeatReachable() {
        formWithYGone();
        var replaced = new VoterConfiguration(1, new ClusterConfig(List.of(B, X, Z)));

        for (var responder : List.of(X, Z)) {
            engine.processSyncResponse(new SyncResponse<>(responder,
                                                          new RabiaPersistence.SavedState<>(new byte[0],
                                                                                            Phase.phase(7),
                                                                                            List.of(),
                                                                                            Option.some(replaced)),
                                                          ResponderState.LIVE));
        }
        settle();
        assertThat(engine.voterConfiguration()).as("precondition: the replacement roster was adopted")
                                               .isEqualTo(Option.some(replaced));
        engine.runPhaseStallCheckForTesting();
        settle();

        assertThat(seatWarnings()).containsExactly(code(GENESIS_SEAT_UNREACHABLE, Y), code(GENESIS_SEAT_REACHABLE, Y));
    }

    private void formWithYGone() {
        engine = pendingB();
        engine.runGenesisRoundForTesting();
        engine.runGenesisRoundForTesting();
        settle();
        announceStably(Y);
        settle();
        connected.remove(Y);
        announceStably(X);
        settle();
    }

    private static String code(OperatorWarningCode code, NodeId subject) {
        return code.code() + " " + subject.id();
    }

    /// Pushes a fresh sentinel through the same in-order hand-off and waits for it, so every warning raised before it is
    /// published. Each call uses its own sentinel: a shared one is already published after the first call and would make
    /// every later drain return at once.
    private List<String> seatWarnings() {
        var sentinel = OperatorWarning.operatorWarning(OperatorWarningCode.OBSERVER_CONSENSUS_RESUMED,
                                                       "sentinel",
                                                       "sentinel-" + sentinels.incrementAndGet());

        sink.accept(sentinel);

        var deadline = System.nanoTime() + 5_000_000_000L;

        while (!published.contains(sentinel) && System.nanoTime() < deadline) {
            LockSupport.parkNanos(10_000_000L);
        }

        assertThat(published).as("the hand-off drained up to the sentinel").contains(sentinel);

        return published.stream()
                        .filter(warning -> warning.code() != OperatorWarningCode.OBSERVER_CONSENSUS_RESUMED)
                        .map(warning -> warning.code().code() + " " + warning.subject())
                        .toList();
    }

    private void announceStably(NodeId sender) {
        for (long round = 1; round <= 2; round++) {
            engine.genesisAnnouncement(new GenesisAnnouncement(sender, round, Option.some(VIEW), Option.none()));
        }
    }

    private RabiaEngine<TestCommand> pendingB() {
        return pendingB(Set.of(B, X, Y), Option.none());
    }

    private RabiaEngine<TestCommand> pendingB(Set<NodeId> discovered, Option<ClusterConfig> fixedView) {
        connected.addAll(Set.of(B, X, Y));

        var created = new RabiaEngine<>(new TestTopologyManager(B, 3),
                                        network,
                                        new TestStateMachine(),
                                        ProtocolConfig.consensusConfig(timeSpan(60).seconds(), timeSpan(60).seconds()),
                                        ConsensusMetrics.noop(),
                                        false,
                                        RabiaPersistence.<TestCommand>inMemory());

        created.useOperatorWarningSink(sink);
        assertThat(created.deferGenesis(() -> discovered, 3, fixedView, Set.of(B, X, Y)).isSuccess()).isTrue();
        created.clusterState(ClusterStateNotification.active());

        return created;
    }

    private void settle() {
        engine.settleForTesting().await();
        engine.settleForTesting().await();
    }
}
