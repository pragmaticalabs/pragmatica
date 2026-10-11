// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.deployment.cluster.NodeReplacementPlanner;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeRunState;
import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeRunValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #1543 part F on a REAL in-JVM 3-core cluster: a rolling upgrade is a run of serial replacements, and no node is ever restarted
/// under its own id.
///
/// Ember runs one binary, so the versions are labels: the three initial nodes advertise `1.0.0` and, from the moment the run is started,
/// every node the cluster provisions advertises `2.0.0` (`EmberCluster.nodeVersion`). The two labels are different strings, so "every
/// node reports the target" can fail: a run that replaced nothing would leave all three on `1.0.0`.
///
/// - `upgradeThreeCores`: the run ends COMPLETED; all three nodes now report `2.0.0`, none of them has an original id, the
///   original LEADER was replaced last, and the installed electorate is the three new nodes.
/// - `abortWhileAReplacementIsInFlight`: the run does not abandon a record mid-phase. The abort is a request; the run goes ABORTED
///   only after the replacement in flight is terminal (DONE), and on every sample that shows ABORTED the record for that node is terminal.
@PortBudget
class EmberUpgradeRunTest {
    private static final String OLD = "1.0.0";
    private static final String NEW = "2.0.0";
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final String TIMINGS_PROPERTY = NodeReplacementPlanner.Timings.OVERRIDE_PROPERTY;

    private EmberCluster cluster;
    private String priorTimings;

    @BeforeEach
    void shortBudgets() {
        priorTimings = System.getProperty(TIMINGS_PROPERTY);
        System.setProperty(TIMINGS_PROPERTY, "60000,60000,90000,60000,3000,90000,60000");
    }

    @AfterEach
    void tearDown() {
        if (priorTimings == null) {
            System.clearProperty(TIMINGS_PROPERTY);
        } else {
            System.setProperty(TIMINGS_PROPERTY, priorTimings);
        }

        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }

    @Test
    @Timeout(1800)
    void upgradeThreeCores_replacesEveryNode_leaderLast_andNoNodeKeepsItsId() {
        start("upg");

        var leader = awaitLeader();
        var originals = ids(live());

        assertThat(originals).hasSize(3);
        assertThat(advertisedVersions()).as("before the run every node advertises the old label").containsOnly(OLD);

        cluster.nodeVersion(NEW);
        leader.upgradeRunService().start(NEW).await(START_BOUND).onFailure(cause -> throwBecause(cause.message()));

        var finished = awaitRunEnds(1_500_000L);

        assertThat(finished.state()).as("reason: %s", finished.reason()).isEqualTo(UpgradeRunState.COMPLETED);
        assertThat(finished.order()).as("the run's order: followers first, the original leader last").hasSize(3);
        assertThat(finished.order().getLast()).isEqualTo(leader.self());

        var survivor = awaitLeader();
        var now = ids(live());

        assertThat(now).as("three nodes, none of them an original id").hasSize(3).doesNotContainAnyElementsOf(originals);
        assertThat(advertisedVersions()).as("every node advertises the target version").containsOnly(NEW);
        assertThat(viewedBy(survivor, now)).as("and the leader's own view of every node, which is what the run reads").containsOnly(NEW);
        awaitCondition("the installed electorate is the three new nodes", 120_000L, () -> EmberNodeReplacementTest.installedVoters(awaitLeader()).equals(now));
    }

    @Test
    @Timeout(1800)
    void abortWhileAReplacementIsInFlight_waitsForItsTerminalState_thenEndsAborted() {
        start("upa");

        var leader = awaitLeader();

        cluster.nodeVersion(NEW);
        leader.upgradeRunService().start(NEW).await(START_BOUND).onFailure(cause -> throwBecause(cause.message()));

        var inFlight = new NodeId[1];

        awaitCondition("a replacement is in flight and not yet terminal", 300_000L, () -> {
            var run = newestRun();

            if (run == null || run.inFlight().isEmpty()) {
                return false;
            }

            var record = recordOf(new NodeId(run.inFlight()));

            inFlight[0] = new NodeId(run.inFlight());

            return record != null && !isTerminal(record.phase());
        });
        awaitLeader().upgradeRunService().abort().await(START_BOUND).onFailure(cause -> throwBecause(cause.message()));

        var abandoned = new ArrayList<String>();
        var deadline = System.currentTimeMillis() + 900_000L;
        UpgradeRunValue run;

        do {
            sleep(100);
            run = newestRun();

            if (run != null && run.state() == UpgradeRunState.ABORTED) {
                var record = recordOf(inFlight[0]);

                if (record == null || !isTerminal(record.phase())) {
                    abandoned.add("ABORTED while the record of " + inFlight[0].id() + " was " + (record == null ? "absent" : record.phase()));
                }
            }
        } while ((run == null || run.state() != UpgradeRunState.ABORTED) && System.currentTimeMillis() < deadline);

        assertThat(run).as("the run ended").isNotNull();
        assertThat(run.state()).as("reason: %s", run.reason()).isEqualTo(UpgradeRunState.ABORTED);
        assertThat(abandoned).as("a record abandoned mid-phase").isEmpty();
        assertThat(recordOf(inFlight[0]).phase()).as("the replacement in flight finished, it was not cut off").isEqualTo(NodeReplacementPhase.DONE);

        assertThat(advertisedVersions()).as("exactly the replaced node reports the target; the run did not go on").containsExactlyInAnyOrder(NEW, OLD, OLD);
    }

    /// The pin for what Ember measured (#1543 F): with the version only in the bootstrap peer LIST, a node's topology holds its own label
    /// and no other, for ever. The label rides the handshake into the membership descriptor like role and source, so every node sees every
    /// peer's version, bootstrap peers included, and `/nodes/lifecycle` and the upgrade status report real versions.
    @Test
    @Timeout(600)
    void everyNodeSeesEveryPeersVersion_bootstrapPeersIncluded() {
        start("upv");
        awaitLeader();

        var everyone = ids(live());

        awaitCondition("every node sees every node's version", 120_000L, () -> live().stream().allMatch(viewer -> viewedBy(viewer, everyone).stream().allMatch(OLD::equals)));

        for (var viewer : live()) {
            assertThat(viewedBy(viewer, everyone)).as("%s's view of %s", viewer.self().id(), everyone).hasSize(3).containsOnly(OLD);
        }
    }

    private static List<String> viewedBy(AetherNode viewer, Set<NodeId> ids) {
        return ids.stream()
                  .map(id -> org.pragmatica.aether.node.AdvertisedVersion.of(id,
                                                                            EmberNodeReplacementTest.runtime(viewer).topologyManager(),
                                                                            Option.option(viewer.membershipFsm())))
                  .toList();
    }

    // ---- plumbing ---------------------------------------------------------------------------------------------------

    private void start(String prefix) {
        cluster = EmberTestPorts.startedCluster(new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                          EmberTestPorts.POOL_LAST,
                                                                          EmberTestPorts.POOL_STEP,
                                                                          2 * 3 + 4,
                                                                          MGMT_OFFSET,
                                                                          APP_HTTP_OFFSET),
                                                basePort -> emberCluster(3, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, prefix).nodeVersion(OLD),
                                                START_BOUND);
    }

    private List<AetherNode> live() {
        return new ArrayList<>(cluster.allNodes());
    }

    private static Set<NodeId> ids(List<AetherNode> nodes) {
        return nodes.stream().map(AetherNode::self).collect(Collectors.toUnmodifiableSet());
    }

    private AetherNode awaitLeader() {
        var found = new AetherNode[1];

        awaitCondition("a leader is elected", 120_000L, () -> {
            found[0] = live().stream().filter(AetherNode::isLeader).findFirst().orElse(null);

            return found[0] != null;
        });

        return found[0];
    }

    /// The version label each live node advertises about ITSELF. This is what "every node reports the target" means here: a node's own
    /// topology holds its own label for certain, whereas the label of a BOOTSTRAP peer never reaches another node's topology (measured on
    /// a 3-core Ember cluster: unchanged after 60 s, each node saw only its own), so a peer's view cannot be asserted for those.
    private List<String> advertisedVersions() {
        return live().stream()
                     .map(node -> Option.option(EmberNodeReplacementTest.runtime(node).topologyManager().self().labels().get(NodeInfo.LABEL_VERSION)).or("<none>"))
                     .toList();
    }

    /// The newest committed run any node knows: nodes apply the leader's commits at slightly different moments, so the highest epoch wins.
    private UpgradeRunValue newestRun() {
        UpgradeRunValue newest = null;

        for (var node : live()) {
            var found = node.upgradeRunService().status().or((UpgradeRunValue) null);

            if (found != null && (newest == null || found.epoch() > newest.epoch())) {
                newest = found;
            }
        }

        return newest;
    }

    private UpgradeRunValue awaitRunEnds(long boundMs) {
        awaitCondition("the run ends", boundMs, () -> {
            var run = newestRun();

            return run != null && !run.live();
        });

        return newestRun();
    }

    private NodeReplacementValue recordOf(NodeId original) {
        NodeReplacementValue newest = null;

        for (var node : live()) {
            var found = node.nodeReplacementService().status(original).or((NodeReplacementValue) null);

            if (found != null && (newest == null || found.epoch() > newest.epoch())) {
                newest = found;
            }
        }

        return newest;
    }

    private static boolean isTerminal(NodeReplacementPhase phase) {
        return phase == NodeReplacementPhase.DONE || phase == NodeReplacementPhase.ROLLED_BACK || phase == NodeReplacementPhase.FAILED_KEPT_BOTH;
    }

    private static void throwBecause(String message) {
        throw new AssertionError(message);
    }

    private static void awaitCondition(String what, long boundMs, BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + boundMs;

        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting: " + what);
            }
            sleep(250);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
