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
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningSink;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.utility.warning.OperatorWarningCode.GENESIS_SEAT_UNREACHABLE;

/// #3785: the decision on the seats a genesis install found unconnected (#3774) must not depend on the engine activating. A node that
/// installs genesis and never activates (activation needs a sync majority, so the survivor of two dead seats never gets one) has no
/// quiet-slot probe and no phase, so no stall detector: with the decision on those ticks it named no dead seat at all, where before #3774
/// it named the seat at the install. The install owns its own deadline.
///
/// Production timers only, no tick hooks, and no shortened window: stall check and sync retry at 50 ms, so a probe or stall tick that ran
/// would have decided long before the window; the window is the engine's own default.
class GenesisSeatDecisionWithoutActivationTest {
    private static final NodeId B = new NodeId("node-2");
    private static final NodeId X = new NodeId("node-4");
    private static final NodeId Y = new NodeId("node-5");
    private static final ClusterConfig VIEW = new ClusterConfig(List.of(B, X, Y));
    /// Slack past the window for the executor and the poll: the bound the test holds the engine to is the window plus this.
    private static final long SLACK_MS = 5_000L;

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

    /// B installs genesis with Y gone and gets no sync answers, so it never activates: no quiet-slot probe is armed and no phase starts.
    /// Y is not named at the install, is named once the window from the install has passed, and is named within the window plus slack.
    @Test
    void installedButNeverActivated_namesTheDeadSeatWithinTheWindow() {
        engine = pendingB();
        var window = engine.genesisSeatConfirmWindowForTesting().millis();
        var started = System.nanoTime();

        formWithYGone();
        assertThat(namesY()).as("the install itself names nothing").isFalse();
        awaitNamedOrBound(window + SLACK_MS);
        var elapsedMs = (System.nanoTime() - started) / 1_000_000L;

        assertThat(engine.voterConfiguration()).as("precondition: genesis formed")
                  .isEqualTo(Option.some(new VoterConfiguration(0, VIEW)));
        assertThat(engine.isActive()).as("precondition: the engine never activated").isFalse();
        assertThat(engine.cleanupArmed()).as("precondition: activation timers (cleanup, quiet-slot probe) never armed")
                  .isFalse();
        assertThat(namesY()).as("the dead seat is named with production timers only, within the window plus slack")
                  .isTrue();
        assertThat(elapsedMs).as("never earlier than the window after the install").isGreaterThanOrEqualTo(window);
    }

    private void awaitNamedOrBound(long boundMs) {
        var deadline = System.nanoTime() + boundMs * 1_000_000L;

        while (!namesY() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(20_000_000L);
        }
    }

    private boolean namesY() {
        return published.stream()
                        .anyMatch(warning -> warning.code() == GENESIS_SEAT_UNREACHABLE && Y.id().equals(warning.subject()));
    }

    private void formWithYGone() {
        engine.runGenesisRoundForTesting();
        engine.runGenesisRoundForTesting();
        engine.settleForTesting().await();
        announceStably(Y);
        engine.settleForTesting().await();
        connected.remove(Y);
        announceStably(X);
        engine.settleForTesting().await();
        engine.settleForTesting().await();
    }

    private void announceStably(NodeId sender) {
        for (long round = 1; round <= 2; round++) {
            engine.genesisAnnouncement(new GenesisAnnouncement(sender, round, Option.some(VIEW), Option.none()));
        }
    }

    private RabiaEngine<TestCommand> pendingB() {
        connected.addAll(Set.of(B, X, Y));
        var created = new RabiaEngine<>(new TestTopologyManager(B, 3),
                                        network,
                                        new TestStateMachine(),
                                        ProtocolConfig.consensusConfig(timeSpan(60).seconds(), timeSpan(50).millis()),
                                        ConsensusMetrics.noop(),
                                        false,
                                        RabiaPersistence.<TestCommand> inMemory(),
                                        timeSpan(50).millis());

        created.useOperatorWarningSink(sink);
        assertThat(created.deferGenesis(() -> Set.of(B, X, Y), 3, Option.none(), Set.of(B, X, Y)).isSuccess()).isTrue();
        created.clusterState(ClusterStateNotification.active());

        return created;
    }
}
