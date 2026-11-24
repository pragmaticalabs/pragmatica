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
package org.pragmatica.consensus.net.quic;

import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManagementMessage;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.TlsConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.testing.PollUntil.pollUntil;

/// #3635 seen from outside: only the constructor and `startOnPort` are used, and the event-loop threads that exist
/// afterwards are read from the JVM. A start that fails on a held UDP port must leave no event-loop thread it created.
/// Written to run unchanged against the code BEFORE the fix (it touches none of the fix's seams), where it is RED.
@Timeout(60)
class QuicClusterStartFailureThreadsTest {
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(20).seconds();
    private static final long LEAK_WINDOW_MS = 10_000L;
    private static final NodeId SELF = new NodeId("threads-self");

    /// Mutations that redden it: drop `releaseOwnedGroupAfterFailedBind` from `handleBind`'s failure branch.
    @Test
    void failedStartOnHeldPort_leavesNoEventLoopThreadItCreated() throws Exception {
        var before = eventLoopThreads();
        var network = network();

        try (var holder = new DatagramSocket(0)) {
            assertThat(network.startOnPort(holder.getLocalPort()).await(AWAIT).isFailure()).as("control: a start on a held UDP port fails").isTrue();
        }

        var leaked = awaitNoNewEventLoopThreads(before);

        assertThat(leaked).as("event-loop threads created by the failed start and still alive %d ms after it reported failure", LEAK_WINDOW_MS).isEmpty();
    }

    /// CONTROL — the same probe on a start that succeeds sees the threads the server's group runs on, so an empty result above
    /// cannot mean the thread-name filter matches nothing.
    @Test
    void startedNetwork_hasEventLoopThreads_theProbeCanSee() {
        var before = eventLoopThreads();
        var network = network();

        try {
            network.startOnPort(0).await(AWAIT).onFailure(cause -> fail("start failed: " + cause.message()));

            assertThat(newThreads(before)).as("control: a running network owns event-loop threads").isNotEmpty();
        } finally {
            network.stop().await(AWAIT);
        }
    }

    private static Set<Thread> awaitNoNewEventLoopThreads(Set<Thread> before) {
        return pollUntil(() -> newThreads(before), Set::isEmpty, Duration.ofMillis(LEAK_WINDOW_MS)).finalValue();
    }

    private static Set<Thread> newThreads(Set<Thread> before) {
        var now = eventLoopThreads();

        now.removeAll(before);

        return now;
    }

    private static Set<Thread> eventLoopThreads() {
        var found = new HashSet<Thread>();

        for (var thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName().toLowerCase().contains("eventloop")) {
                found.add(thread);
            }
        }

        return found;
    }

    private static QuicClusterNetwork network() {
        var serverSsl = QuicTlsProvider.serverContext(ClusterTestTls.clusterTls("threads-server")).fold(_ -> fail("server ssl"), ssl -> ssl);
        var clientSsl = QuicTlsProvider.clientContext(ClusterTestTls.clusterTls("threads-client")).fold(_ -> fail("client ssl"), ssl -> ssl);
        var codec = LaneProbe.codec();
        var self = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("127.0.0.1", 19996).fold(_ -> fail("bad address"), a -> a));

        return new QuicClusterNetwork(stubTopology(self), codec, codec, MessageRouter.mutable(), serverSsl, clientSsl);
    }

    private static TopologyObserver stubTopology(NodeInfo self) {
        return new TopologyObserver() {
            @Override public Unit setConsensusMembership(Predicate<NodeId> membership) {return Unit.unit();}
            @Override public NodeInfo self() {return self;}
            @Override public Option<NodeInfo> get(NodeId id) {return id.equals(self.id()) ? Option.some(self) : Option.empty();}
            @Override public int clusterSize() {return 3;}
            @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {return Option.empty();}
            @Override public Promise<Unit> start() {return Promise.unitPromise();}
            @Override public Promise<Unit> stop() {return Promise.unitPromise();}
            @Override public TimeSpan pingInterval() {return TimeSpan.timeSpan(30).seconds();}
            @Override public TimeSpan helloTimeout() {return TimeSpan.timeSpan(5).seconds();}
            @Override public Option<TlsConfig> tls() {return Option.empty();}
            @Override public Option<NodeState> getState(NodeId id) {return Option.empty();}
            @Override public List<NodeId> topology() {return List.of(self.id());}
            @Override public void reconcile(NetworkServiceMessage.ConnectedNodesList connectedNodesList) {}
            @Override public void handleDiscoverNodes(NetworkMessage.DiscoverNodes discoverNodes) {}
            @Override public void handleDiscoveredNodes(NetworkMessage.DiscoveredNodes discoveredNodes) {}
            @Override public void handleSetClusterSize(TopologyManagementMessage.SetClusterSize message) {}
        };
    }
}
