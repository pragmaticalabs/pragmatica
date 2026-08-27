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

package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.TerminalOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #590 — the guard on `EmberCluster.addWorkerNode()`, and on the default staying untouched.
///
/// ## Why this class exists
///
/// `addNode()` is a primitive every forge test depends on, so adding a role-label variant beside it is
/// only safe if the DEFAULT path is provably unchanged. This pins both halves: the default advertises
/// no role, and the opt-in advertises `role=worker`.
///
/// ## The property being restored, and why it is the label
///
/// Community-tier mechanisms gate on a node being positively known NOT to be a core:
/// `MemberDescriptor.isCoreRole(role) = !"worker".equals(role)` — **blank or unknown counts as CORE**,
/// deliberately, because acting on an unresolved view is the dangerous direction. Production nodes
/// self-assert that label (`AETHER_ROLE` → `NodeInfo.LABEL_ROLE`); Ember set none, so every in-JVM node
/// classified as a core and the #590 core-absence fence could never fire. Measured before the fix:
/// `armed=true sinceLastPingMs=40922 remainingMs=0 thresholdMs=10000 fenced=false`.
///
/// The assertion below is on the ADVERTISED LABEL, not on downstream classification, deliberately: the
/// label is the contract this method owns, and pinning it here keeps the guard honest even if the
/// classification chain is later refactored.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmberAddNodeRoleLabelTest {
    private static final Logger log = LoggerFactory.getLogger(EmberAddNodeRoleLabelTest.class);

    private static final int INITIAL_CORES = 3;
    private static final int BASE_PORT = 21500;
    private static final int BASE_MGMT_PORT = 21600;
    private static final int BASE_APP_HTTP_PORT = 21700;

    private static final Duration FORM_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration POLL = Duration.ofMillis(250);

    private EmberCluster cluster;

    @BeforeAll
    @TerminalOperation
    void setUp() {
        cluster = emberCluster(INITIAL_CORES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "rolelbl");
        cluster.withRaisedSwimTimeouts();
        cluster.start().await().onFailure(EmberAddNodeRoleLabelTest::fail);
        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(() -> cluster.currentLeader().isPresent());
    }

    @AfterAll
    @TerminalOperation
    void tearDown() {
        Option.option(cluster).onPresent(c -> c.stop().await());
    }

    /// THE REGRESSION GUARD. Every existing forge test calls `addNode()`, and a node that started
    /// advertising a role would change how the membership FSM classifies it cluster-wide. The default
    /// must stay exactly as it was: no role label at all.
    @Test
    void addNode_advertisesNoRoleLabel_soExistingBehaviourIsUnchanged() {
        var added = cluster.addNode().await().onFailure(EmberAddNodeRoleLabelTest::fail).map(id -> id.id()).or("");
        var labels = advertisedLabels(added);

        log.info("ROLE-LABEL: default addNode -> {} labels={}", added, labels);
        assertThat(labels.containsKey(NodeInfo.LABEL_ROLE))
            .as("default addNode() must advertise NO role label — blank classifies as CORE, which is the "
                + "long-standing behaviour every forge test is written against. Saw labels=%s", labels)
            .isFalse();
    }

    /// The opt-in. Without this label the node classifies as a core and every community-tier mechanism
    /// is suppressed on it, which is exactly what made #590's fence unobservable in-JVM.
    @Test
    void addWorkerNode_advertisesTheWorkerRole_soCommunityTierMechanismsApply() {
        var added = cluster.addWorkerNode().await().onFailure(EmberAddNodeRoleLabelTest::fail).map(id -> id.id()).or("");
        var labels = advertisedLabels(added);

        log.info("ROLE-LABEL: addWorkerNode -> {} labels={}", added, labels);
        assertThat(labels.get(NodeInfo.LABEL_ROLE))
            .as("addWorkerNode() must advertise exactly the literal `MemberDescriptor.isCoreRole` tests "
                + "against — anything else, INCLUDING a near-miss like \"WORKER\", classifies as core and "
                + "silently restores the suppression this method exists to lift. Saw labels=%s", labels)
            .isEqualTo("worker");
    }

    /// Reads the node's OWN advertised `NodeInfo` — the field peers and its own `MemberDescriptor`
    /// classify from, and the same one production populates from `AETHER_ROLE`.
    private java.util.Map<String, String> advertisedLabels(String nodeId) {
        return cluster.getNode(nodeId)
                      .map(node -> node.topologyManager()
                                       .self()
                                       .labels())
                      .or(java.util.Map.of());
    }

    private static void fail(Cause cause) {
        throw new AssertionError("Ember step failed: " + cause.message());
    }
}
