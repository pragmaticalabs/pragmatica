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

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.ConsensusError;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;

/// #2981 step 6 — the pre-sync hook: it runs once per engine start, before the node's first sync, and a failure
/// refuses startup.
class RabiaEnginePreSyncCheckTest {
    private static final NodeId NODE_1 = nodeId("node-1").unwrap();
    private static final NodeId NODE_2 = nodeId("node-2").unwrap();
    private static final Duration QUIET = Duration.ofMillis(400);

    private RabiaEnginePauseResumeTest.TestClusterNetwork network;
    private RabiaEngine<RabiaEnginePauseResumeTest.TestCommand> engine;

    @BeforeEach
    void setUp() {
        network = new RabiaEnginePauseResumeTest.TestClusterNetwork();
        engine = new RabiaEngine<>(new RabiaEnginePauseResumeTest.TestTopologyManager(NODE_1, 3),
                                   network,
                                   new RabiaEnginePauseResumeTest.TestStateMachine(),
                                   ProtocolConfig.testConfig());
    }

    @AfterEach
    void tearDown() {
        engine.stop().await();
    }

    private boolean syncStarted() {
        return network.getMessages().stream()
                      .anyMatch(RabiaProtocolMessage.Synchronous.SyncRequest.class::isInstance);
    }

    private void awaitSyncStarted() {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();

        while (!syncStarted() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        assertThat(syncStarted()).as("sync started").isTrue();
    }

    private void stayQuiet() throws InterruptedException {
        Thread.sleep(QUIET.toMillis());
    }

    @Test
    void preSyncCheck_syncStartsOnlyAfterTheCheckSucceeds() throws InterruptedException {
        var gate = Promise.<Unit>promise();

        engine.preSyncCheck(() -> gate);
        engine.clusterState(ClusterStateNotification.active());
        stayQuiet();

        assertThat(syncStarted()).as("no sync while the pre-sync check is pending").isFalse();

        gate.succeed(Unit.unit());
        awaitSyncStarted();
    }

    @Test
    void preSyncCheck_failure_failsStartupAndNeverSyncs() throws InterruptedException {
        var refusal = new ConsensusError.WireGenerationRefused(NODE_2, 1, 2);
        var started = engine.start();

        engine.preSyncCheck(() -> refusal.promise());
        engine.clusterState(ClusterStateNotification.active());

        started.await(org.pragmatica.lang.io.TimeSpan.timeSpan(5).seconds())
               .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("startup must be refused"))
               .onFailure(cause -> assertThat(cause).isEqualTo(refusal));
        stayQuiet();
        assertThat(syncStarted()).as("a refused node never starts syncing").isFalse();
        assertThat(engine.isStopRequested()).as("a refused engine is stopped").isTrue();
    }

    @Test
    void preSyncCheck_passiveDuringWait_noSyncOnCompletion_thenNextActiveProceedsWithoutRerun()
        throws InterruptedException {
        var runs = new AtomicInteger();
        var gate = Promise.<Unit>promise();

        engine.preSyncCheck(() -> {
            runs.incrementAndGet();
            return gate;
        });
        engine.clusterState(ClusterStateNotification.active());
        engine.clusterState(ClusterStateNotification.passive());
        gate.succeed(Unit.unit());
        stayQuiet();

        assertThat(syncStarted()).as("a PASSIVE arrived during the wait: completion must not start sync").isFalse();

        engine.clusterState(ClusterStateNotification.active());
        awaitSyncStarted();

        assertThat(runs).as("the guard passed once; a later ACTIVE does not run it again").hasValue(1);
    }

    @Test
    void preSyncCheck_secondActiveWhileWaiting_runsTheCheckOnce() throws InterruptedException {
        var runs = new AtomicInteger();
        var gate = Promise.<Unit>promise();

        engine.preSyncCheck(() -> {
            runs.incrementAndGet();
            return gate;
        });
        engine.clusterState(ClusterStateNotification.active());
        engine.clusterState(ClusterStateNotification.active());
        stayQuiet();
        gate.succeed(Unit.unit());
        awaitSyncStarted();

        assertThat(runs).hasValue(1);
    }

    @Test
    void noPreSyncCheckInstalled_syncStartsAtOnce() {
        engine.clusterState(ClusterStateNotification.active());
        awaitSyncStarted();
    }
}
