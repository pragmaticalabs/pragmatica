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

import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.rabia.ConsensusMetrics;
import org.pragmatica.consensus.rabia.ProtocolConfig;
import org.pragmatica.consensus.rabia.RabiaPersistence;
import org.pragmatica.consensus.topology.TopologyConfig;
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
/// such view. The node is assembled, never started: the wiring happens at assembly.
class RabiaNodeTransportViewWiringTest {
    private static final NodeId SELF = new NodeId("node-1");
    private static final NodeId PEER = new NodeId("node-2");

    @Test
    void rabiaNode_withConsensusLeaderElection_installsTheTransportViewInItsManager() {
        assertThat(assembled(true).leaderManager()
                                  .hasTransportView()).as("consensus election re-reads the transport view")
                                                      .isTrue();
    }

    @Test
    void rabiaNode_withLocalLeaderElection_installsNoTransportView() {
        assertThat(assembled(false).leaderManager()
                                   .hasTransportView()).as("local election has no transport view")
                                                       .isFalse();
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
