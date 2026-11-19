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

package org.pragmatica.cluster.node.rabia;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.rabia.ConsensusMetrics;
import org.pragmatica.consensus.rabia.ProtocolConfig;
import org.pragmatica.consensus.rabia.RabiaPersistence;
import org.pragmatica.consensus.topology.TransportObservation.ObservationSource;
import org.pragmatica.consensus.topology.TransportObservation.PeerJoined;
import org.pragmatica.consensus.topology.TransportObservation.PeerObservedFaulty;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TransportView;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.messaging.MessageRouter.DelegateRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.net.tcp.security.SelfSignedCertificateProvider;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #3047: the assembled node wires the transport's sequenced view into its leader manager, so a node SWIM removed while
/// its transport link stayed up returns via the transport's own view. Consensus election only: local election has no
/// such view. The node is assembled, never started, and its network is the one production builds. The pin is behavioural:
/// every read of `ClusterNetwork#sequencedView` advances the network's observation sequence, so a read the election
/// makes after a view-free removal shows as a sequence step beyond the probes this test makes itself.
class RabiaNodeTransportViewWiringTest {
    private static final NodeId SELF = new NodeId("node-1");
    private static final NodeId PEER = new NodeId("node-2");
    private static final long PROBE_PERIOD_MS = 250;
    // The re-assertion is armed LeaderElectionContext.DEFAULT_REASSERT_PERIOD (5s) after the removal; the margin
    // absorbs scheduler lateness without admitting a second read.
    private static final long WINDOW_MS = 9_000;

    @Test
    void rabiaNode_withConsensusLeaderElection_readsTheNetworksViewAfterARemoval() throws InterruptedException {
        assertThat(electionReadsOfTheNetworkView(true)).as("the election re-reads the network's sequenced view once")
                                                       .isEqualTo(1);
    }

    @Test
    void rabiaNode_withLocalLeaderElection_neverReadsTheNetworksView() throws InterruptedException {
        assertThat(electionReadsOfTheNetworkView(false)).as("local election has no transport view")
                                                        .isZero();
    }

    /// Removes the peer view-free from the assembled node's election, then counts the network view reads that were
    /// not this method's own probes.
    private static long electionReadsOfTheNetworkView(boolean useConsensusLeaderElection) throws InterruptedException {
        var node = assembled(useConsensusLeaderElection);
        var manager = node.leaderManager();
        var base = sequenceOf(node);

        manager.peerJoined(new PeerJoined(PEER, List.of(SELF, PEER), ObservationSource.QUIC, 1L));
        manager.peerObservedFaulty(new PeerObservedFaulty(PEER, List.of(SELF), ObservationSource.SWIM));

        var probes = 0L;
        var last = base;
        var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WINDOW_MS);

        while (System.nanoTime() < deadline) {
            Thread.sleep(PROBE_PERIOD_MS);
            last = sequenceOf(node);
            probes++;
        }

        return last - base - probes;
    }

    private static long sequenceOf(RabiaNode<KVCommand<StructuredKey>> node) {
        return node.network()
                   .sequencedView()
                   .map(TransportView::sequence)
                   .or(0L);
    }

    private static RabiaNode<KVCommand<StructuredKey>> assembled(boolean useConsensusLeaderElection) {
        var router = DelegateRouter.delegate();
        var serializer = mock(Serializer.class);
        var deserializer = mock(Deserializer.class);
        var store = new KVStore<StructuredKey, Object>(MessageRouter.mutable(), serializer, deserializer);
        var topology = new TopologyConfig(SELF,
                                          2,
                                          timeSpan(1).seconds(),
                                          timeSpan(1).seconds(),
                                          List.of(info(SELF, 4801), info(PEER, 4802)));

        return RabiaNode.rabiaNode(NodeConfig.nodeConfig(ProtocolConfig.testConfig(), topology),
                                   router,
                                   store,
                                   serializer,
                                   deserializer,
                                   ConsensusMetrics.noop(),
                                   useConsensusLeaderElection,
                                   RabiaPersistence.<KVCommand<StructuredKey>> inMemory(),
                                   tls())
                        .unwrap();
    }

    private static NodeInfo info(NodeId id, int port) {
        return NodeInfo.nodeInfo(id, NodeAddress.nodeAddress("localhost", port).unwrap());
    }

    private static TlsConfig tls() {
        var provider = SelfSignedCertificateProvider.selfSignedCertificateProvider("t3047-wiring-test".getBytes(StandardCharsets.UTF_8))
                                                    .unwrap();

        return TlsConfig.fromProvider(provider, SELF.id(), "localhost").unwrap();
    }
}
