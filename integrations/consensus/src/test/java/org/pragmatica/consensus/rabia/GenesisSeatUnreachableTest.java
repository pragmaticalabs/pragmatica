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
import org.pragmatica.consensus.rabia.ConsensusEvent.GenesisSeatUnreachable;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestClusterNetwork;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestCommand;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestStateMachine;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestTopologyManager;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.GenesisAnnouncement;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1654 engine half: the genesis view agreement counts a member's last two reports with no freshness, so a member that
/// reported the view twice and then died is still agreed. Formation goes ahead (ruling: keep forming) and the engine names
/// the dead seat with a WARN and a `GenesisSeatUnreachable` event.
///
/// B is genesis-pending (configured count 3) with candidates X and Y, as in `GenesisJoinRuleTest`.
class GenesisSeatUnreachableTest {
    private static final NodeId B = new NodeId("node-2");
    private static final NodeId X = new NodeId("node-4");
    private static final NodeId Y = new NodeId("node-5");
    private static final ClusterConfig VIEW = new ClusterConfig(List.of(B, X, Y));

    private final Set<NodeId> connected = ConcurrentHashMap.newKeySet();
    private final List<ConsensusEvent> events = new CopyOnWriteArrayList<>();
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
    void genesisFormedWithAMemberThatStoppedReporting_emitsSeatUnreachable() {
        engine = pendingB();
        engine.runGenesisRoundForTesting();
        engine.runGenesisRoundForTesting();
        settle();
        announceStably(Y);
        settle();
        connected.remove(Y);
        announceStably(X);
        settle();

        assertThat(engine.voterConfiguration()).as("formation is not blocked by the dead seat")
                                               .isEqualTo(Option.some(new VoterConfiguration(0, VIEW)));
        assertThat(seatEvents()).containsExactly(new GenesisSeatUnreachable(B, Y));
    }

    /// Control: the same schedule with every seat still connected forms the same roster and names no seat, so the event
    /// above comes from the lost connection, not from forming genesis.
    @Test
    void genesisFormedWithEverySeatConnected_emitsNoSeatUnreachable() {
        engine = pendingB();
        engine.runGenesisRoundForTesting();
        engine.runGenesisRoundForTesting();
        settle();
        announceStably(Y);
        settle();
        announceStably(X);
        settle();

        assertThat(engine.voterConfiguration()).isEqualTo(Option.some(new VoterConfiguration(0, VIEW)));
        assertThat(seatEvents()).isEmpty();
    }

    private List<ConsensusEvent> seatEvents() {
        return events.stream()
                     .filter(GenesisSeatUnreachable.class::isInstance)
                     .toList();
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
                                        ProtocolConfig.consensusConfig(timeSpan(60).seconds(), timeSpan(60).seconds()),
                                        ConsensusMetrics.noop(),
                                        false,
                                        RabiaPersistence.<TestCommand>inMemory(),
                                        timeSpan(60).seconds(),
                                        events::add);

        assertThat(created.deferGenesis(() -> Set.of(B, X, Y), 3, Option.none(), Set.of(B, X, Y)).isSuccess()).isTrue();
        created.clusterState(ClusterStateNotification.active());

        return created;
    }

    private void settle() {
        engine.settleForTesting().await();
        engine.settleForTesting().await();
    }
}
