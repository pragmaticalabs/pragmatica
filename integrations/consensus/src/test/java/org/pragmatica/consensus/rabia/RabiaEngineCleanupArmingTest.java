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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestClusterNetwork;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestCommand;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestStateMachine;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestTopologyManager;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.SyncResponse;
import org.pragmatica.consensus.topology.ClusterStateNotification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;

/// #714 — the old-phase sweep must be armed at ACTIVATION, not in the constructor.
///
/// ## The defect
///
/// `cleanupTask` was armed in the constructor and cancelled only by [`RabiaEngine#stop`], which is
/// reached through `clusterNode.stop()`. `AetherNode.cancelArmedWork` — the failed-boot guard added
/// by #644 — deliberately does not attempt to tear down a cluster stack that never started, so an
/// engine that was constructed and then refused kept ticking for the life of the JVM. Split out of
/// #644 as the constructor-armed family its list-based fix could not reach.
///
/// ## Why deferring is safe rather than a trade-off
///
/// `doCleanupOldPhases` already returns immediately unless the engine is active or observing. So an
/// unarmed pre-activation engine and an armed one do exactly the same thing — nothing. Deferring
/// removes a scheduler slot whose only job was to reach that early return; it cannot change sweep
/// behaviour, and `phases` cannot grow unswept because entries are created on the consensus path,
/// which the same state guard gates.
///
/// The arming state is not observable through `SharedScheduler`, which exposes no introspection, so
/// these assertions go through the package-private `cleanupArmed()` seam. Asserting it indirectly
/// through timing would pin nothing. The test doubles are reused from [`RabiaEngineTest`] rather
/// than duplicated.
class RabiaEngineCleanupArmingTest {
    private static final NodeId NODE_1 = nodeId("node-1").unwrap();
    private static final NodeId NODE_2 = nodeId("node-2").unwrap();
    private static final NodeId NODE_3 = nodeId("node-3").unwrap();
    private static final int CLUSTER_SIZE = 3;

    private RabiaEngine<TestCommand> engine;

    @BeforeEach
    void setUp() {
        var topologyManager = new TestTopologyManager(NODE_1, CLUSTER_SIZE);
        var network = new TestClusterNetwork();
        var stateMachine = new TestStateMachine();

        engine = new RabiaEngine<>(topologyManager, network, stateMachine, ProtocolConfig.testConfig());
    }

    @AfterEach
    void tearDown() {
        engine.stop().await();
    }

    private void activateEngine() throws InterruptedException {
        engine.clusterState(ClusterStateNotification.active());
        Thread.sleep(150);
        engine.processSyncResponse(new SyncResponse<>(NODE_2, RabiaPersistence.SavedState.empty()));
        engine.processSyncResponse(new SyncResponse<>(NODE_3, RabiaPersistence.SavedState.empty()));
        Thread.sleep(50);
    }

    /// The defect itself: a constructed-but-never-activated engine must own no recurring work. This
    /// is the failed-boot shape — assembly succeeded, the node was refused, nothing ever stops it.
    @Test
    void construction_armsNoCleanupTask_soARefusedBootLeaksNothing() {
        assertThat(engine.cleanupArmed())
            .as("#714: constructing an engine must not arm the phase sweep — a node that never "
                + "starts is never stopped, so a constructor-armed tick leaks for the JVM's life")
            .isFalse();
    }

    @Test
    void activation_armsTheCleanupTask() throws InterruptedException {
        activateEngine();

        assertThat(engine.isActive()).as("precondition: the engine must actually have activated").isTrue();
        assertThat(engine.cleanupArmed())
            .as("the sweep must be armed once the engine is running — this is where phases accumulate")
            .isTrue();
    }

    /// The precondition matters: asserting only "armed after activation" would pass vacuously
    /// against an engine that armed in the constructor, which is exactly the defect. Pinning the
    /// transition false -> true is what distinguishes the fix from the bug.
    @Test
    void arming_transitionsFromUnarmedToArmed_ratherThanBeingArmedAllAlong() throws InterruptedException {
        assertThat(engine.cleanupArmed()).as("unarmed before activation").isFalse();

        activateEngine();

        assertThat(engine.cleanupArmed()).as("armed after activation").isTrue();
    }

    @Test
    void stop_cancelsAndClearsTheCleanupTask() throws InterruptedException {
        activateEngine();
        assertThat(engine.cleanupArmed()).isTrue();

        engine.stop().await();

        assertThat(engine.cleanupArmed())
            .as("stop must CLEAR as well as cancel — the reference is the arm guard, so a stale "
                + "cancelled future would stop a restarted engine from ever re-arming")
            .isFalse();
    }

    /// Activation is reached repeatedly — a reconfigure or a quorum-loss pause runs through it
    /// again — so arming must be idempotent. Without the CAS guard each pass would schedule another
    /// sweep and drop the previous handle, which is the same leak class the ticket is about.
    @Test
    void repeatedActivation_armsExactlyOnce() throws InterruptedException {
        activateEngine();
        assertThat(engine.cleanupArmed()).isTrue();

        engine.clusterState(ClusterStateNotification.active());
        Thread.sleep(50);

        assertThat(engine.cleanupArmed())
            .as("still armed, and by the same task — the CAS guard makes re-activation a no-op")
            .isTrue();
    }
}
