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

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.SyncResponse;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;

/// #2981 step 6: `syncRoundOpen` starts closed, so an UNSOLICITED SyncResponse (one this engine never requested) cannot install
/// state and activate the engine before its pre-sync check decides: not on a never-started engine, not before cluster ACTIVE, and
/// not while the guard is pending.
class RabiaEngineUnsolicitedSyncResponseTest {
    private static final NodeId NODE_2 = nodeId("node-2").unwrap();
    private static final NodeId NODE_1 = nodeId("node-1").unwrap();

    private RabiaEnginePauseResumeTest.TestClusterNetwork network;
    private RabiaEngine<RabiaEnginePauseResumeTest.TestCommand> engine;
    private final AtomicInteger checks = new AtomicInteger();

    @BeforeEach
    void setUp() {
        network = new RabiaEnginePauseResumeTest.TestClusterNetwork();
        engine = new RabiaEngine<>(new RabiaEnginePauseResumeTest.TestTopologyManager(NODE_1, 3),
                                   network,
                                   new RabiaEnginePauseResumeTest.TestStateMachine(),
                                   ProtocolConfig.testConfig());
        engine.preSyncCheck(() -> {
            checks.incrementAndGet();
            return Promise.<Unit>promise();
        });
    }

    @AfterEach
    void tearDown() {
        engine.stop().await();
    }

    private void deliverOneColdReply() throws InterruptedException {
        engine.processSyncResponse(new SyncResponse<>(NODE_2, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
        Thread.sleep(400);
    }

    @Test
    void processSyncResponse_unsolicitedBeforeClusterActive_doesNotActivate() throws InterruptedException {
        var _ = engine.start();
        deliverOneColdReply();
        assertThat(engine.isActive()).as("activated from an unsolicited reply before the guard ran").isFalse();
    }

    @Test
    void processSyncResponse_unsolicitedWhileGuardPending_doesNotActivate() throws InterruptedException {
        var _ = engine.start();
        engine.clusterState(ClusterStateNotification.active());
        Thread.sleep(150);
        assertThat(checks.get()).as("guard is running").isEqualTo(1);
        deliverOneColdReply();
        assertThat(engine.isActive()).as("activated from an unsolicited reply while the guard is pending").isFalse();
    }

    @Test
    void processSyncResponse_unsolicitedOnNeverStartedEngine_doesNotActivate() throws InterruptedException {
        deliverOneColdReply();
        assertThat(engine.isActive()).as("activated from an unsolicited reply on a never-started engine").isFalse();
    }
}
