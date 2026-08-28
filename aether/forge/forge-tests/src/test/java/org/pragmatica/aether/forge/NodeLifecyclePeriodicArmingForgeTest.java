// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.time.Duration;

import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.PeriodicTasks;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.Set;

import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.awaitility.Awaitility.await;

/// The #644 WIRING pin — the one assertion no unit test can make: that `AetherNode.start()` actually
/// arms the deferred periodic tasks, and that a CREATED-but-never-started node holds none.
/// `PeriodicTasksTest` pins the deferral state machine in isolation; without this test, deleting the
/// `periodicTasks.arm()` call from `start()` would compile, pass every unit test, and ship nodes
/// that never snapshot, never reconcile and never heal — a silently-degenerate cluster.
///
/// The held-back seam (`EmberCluster.start(heldBackNodeIds)`, the #509 probe's) produces the
/// ticket's exact evidence condition deterministically: a node CONSTRUCTED with the full topology
/// but with `start()` deferred. #642's evidence run observed such nodes running 274 snapshot ticks
/// each over 45 minutes; the contract this pins is that they now run ZERO periodic work — observed
/// structurally through the [PeriodicTasks] counts rather than by log absence, because absence of
/// log lines cannot distinguish "nothing armed" from "nothing logged yet".
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NodeLifecyclePeriodicArmingForgeTest {
    private static final int BASE_PORT = 22050;
    private static final int BASE_MGMT_PORT = 22150;
    private static final int BASE_APP_HTTP_PORT = 22250;
    private static final String NODE_PREFIX = "arm";
    private static final String HELD_BACK_ID = NODE_PREFIX + "-3";
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    /// Long enough for several fires of the TIGHTEST deferred interval (1s, the entity timer tick):
    /// if deferral were broken in the direction of arming by time rather than by start(), three-plus
    /// missed fire opportunities make the observed zero meaningful rather than merely early.
    private static final Duration HOLD_OBSERVATION = Duration.ofSeconds(4);

    private EmberCluster cluster;
    /// The held-back instance's deferred count, captured while unstarted — the start-arms-exactly-
    /// the-deferred-set assertion compares against it.
    private int deferredWhileHeld;

    @BeforeAll
    void setUp() {
        cluster = emberCluster(3, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, NODE_PREFIX);

        // 2 of 3 started is a Rabia quorum, so the cluster genuinely forms around the held-back node.
        cluster.start(Set.of(HELD_BACK_ID))
               .await()
               .onFailure(cause -> {
                   throw new AssertionError("Cluster start failed: " + cause.message());
               });

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader().isPresent());
    }

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            cluster.stop()
                   .await();
        }
    }

    /// The #644 contract, held over time: the constructed-but-unstarted node has deferred work and
    /// ZERO armed work, and stays that way through several fire intervals of the tightest task —
    /// arming is an act of start(), never of time passing.
    @Test
    @Order(1)
    void heldBackNode_holdsDeferredWork_andArmsNothingWhileUnstarted() {
        var held = periodicTasksOf(heldBackInstance());

        assertThat(held.deferredCount()).as("assembly must have deferred the periodic-task family")
                                        .isPositive();
        deferredWhileHeld = held.deferredCount();

        await().during(HOLD_OBSERVATION)
               .atMost(HOLD_OBSERVATION.plusSeconds(2))
               .pollInterval(POLL_INTERVAL)
               .until(() -> held.armedCount() == 0);
    }

    /// The wiring pin for every STARTED node: start() armed the full deferred set. This is the
    /// assertion that goes red if `periodicTasks.arm()` is dropped from start().
    @Test
    @Order(2)
    void startedNodes_haveArmedTheirFullDeferredSet() {
        assertThat(cluster.allNodes()).isNotEmpty();
        cluster.allNodes()
               .forEach(node -> {
                   var tasks = periodicTasksOf(node);

                   assertThat(tasks.armedCount()).as("a started node must have armed its periodic work")
                                                 .isPositive();
                   assertThat(tasks.deferredCount()).as("start() must arm the WHOLE deferred set, leaving nothing behind")
                                                    .isZero();
               });
    }

    /// Releasing the hold arms exactly the set that was deferred while held — late start is ordinary
    /// start, nothing lost and nothing doubled.
    @Test
    @Order(3)
    void startingTheHeldBackNode_armsExactlyTheDeferredSet() {
        var held = periodicTasksOf(heldBackInstance());

        cluster.startHeldBackNodes()
               .await()
               .onFailure(cause -> {
                   throw new AssertionError("Held-back start failed: " + cause.message());
               });

        assertThat(held.armedCount()).isEqualTo(deferredWhileHeld);
        assertThat(held.deferredCount()).isZero();
    }

    /// The disarm half of the contract: stop() leaves zero armed handles. Runs the teardown itself
    /// so the post-stop state is observable; teardown then finds the field null and does nothing.
    @Test
    @Order(4)
    void stop_disarmsEveryNode() {
        var observed = cluster.allNodes()
                              .stream()
                              .map(NodeLifecyclePeriodicArmingForgeTest::periodicTasksOf)
                              .toList();

        cluster.stop()
               .await();
        cluster = null;

        assertThat(observed).isNotEmpty();
        observed.forEach(tasks -> assertThat(tasks.armedCount()).as("stop() must cancel every armed periodic task")
                                                                .isZero());
    }

    // ---- helpers -------------------------------------------------------------------------------

    private AetherNode heldBackInstance() {
        return cluster.heldBackNode(HELD_BACK_ID)
                      .fold(() -> fail("held-back node " + HELD_BACK_ID + " not found — was it already started?"),
                            node -> node);
    }

    /// The observation seam: the node's [PeriodicTasks], via the `AetherNode.periodicTasks()`
    /// interface accessor added for exactly this contract (#644).
    private static PeriodicTasks periodicTasksOf(AetherNode node) {
        return node.periodicTasks();
    }
}
