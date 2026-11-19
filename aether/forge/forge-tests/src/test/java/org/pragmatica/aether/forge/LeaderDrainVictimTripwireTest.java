// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #1880 / #1089 — young configured joiners are not retired inside the drain-safety grace, they are retired after it, and
/// the leader is never selected as its own surplus victim.
///
/// Restored from the #1089 tripwire deleted in 13aeb49cc0, whose stated reason ("joiners drain as
/// OVERPROVISION_SCALE_DOWN") was #1880's defect: the voter-excluded core retirement (#3324) retired a just-joined core
/// about 1.5 s after it joined, before its admission finished. The drain-safety grace is now enforced at the one choke
/// point every drain passes (`ClusterTopologyManager.drainNode`, `DrainSafetyGrace`), so that retirement waits too.
///
/// ## Scenario
/// 1. three configured seeds form (`splitTimeout` raised to 60 s via [EmberCluster#withRaisedSwimTimeouts], so the
///    drain-safety grace, `2 × splitTimeout`, is 120 s);
/// 2. three cores are added with `EmberCluster.addNode()`, which mints CONFIGURED ids (`ldv-4..6`, not ULIDs) and does
///    not change the desired core count (3): a surplus of 3, every one of them young;
/// 3. phase 1 (the young window): no added member leaves, and the leader never marks itself `Departing`;
/// 4. phase 2 (after the grace): the surplus is retired and the cluster returns to its 3 members. This is what proves the
///    grace DELAYS the retirement rather than disabling it.
///
/// The earlier form of this scenario also required the two non-leader seeds to be drained first. That precondition is
/// dropped: seeds are voters and `ClusterTopologyManager.drainNode` refuses them until a certified voter handoff (#3324),
/// so the reconciler's selection of voter seeds never completed. That selection/enforcement disagreement is #3439's, not
/// this test's, and this test does not depend on it.
///
/// ## Reading a red
/// - **SCENARIO** messages: the arrangement did not hold (a leader change). Nothing about the properties can be
///   concluded; fix the harness.
/// - **#1880 (young window)**: an added member left the cluster before the grace elapsed after its join — some drain path
///   bypassed the grace.
/// - **#1880 (after the grace)**: the surplus was not retired once the grace had passed — the grace disabled the
///   retirement instead of delaying it.
/// - **#1089**: the leader marked itself `Departing`, i.e. it selected itself as a surplus victim.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderDrainVictimTripwireTest {
    private static final Logger log = LoggerFactory.getLogger(LeaderDrainVictimTripwireTest.class);

    private static final int SEEDS = 3;
    private static final int ADDED = 3;
    private static final int BASE_PORT = 33000;
    private static final int BASE_MGMT_PORT = 33100;
    private static final int BASE_APP_HTTP_PORT = 33200;

    /// `DrainSafetyGrace.drainSafetyGrace` = 2 × `splitTimeout`; [EmberCluster#withRaisedSwimTimeouts] sets
    /// `splitTimeout` to 60 s.
    private static final Duration DRAIN_SAFETY_GRACE = Duration.ofSeconds(120);
    /// A member's age counts from first reachability evidence, which precedes the moment `addNode()` returns; this is the
    /// tolerance between the two when asserting "not retired before the grace elapsed".
    private static final Duration JOIN_STAMP_SLACK = Duration.ofSeconds(15);
    /// After the grace the retirement is a reconciler tick plus a drain: generous, not tight.
    private static final Duration RETIRE_AFTER_GRACE = Duration.ofSeconds(180);
    private static final Duration FORM_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration POLL = Duration.ofMillis(250);

    private static final String DEPARTING = "Departing";

    private EmberCluster cluster;

    @BeforeAll
    void setUp() {
        cluster = emberCluster(SEEDS, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "ldv");
        cluster.withRaisedSwimTimeouts();
        LifecycleAwait.settled("cluster start in setUp()", cluster, cluster.start());
        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(() -> cluster.currentLeader().isPresent());
        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(() -> countedOnLeader().size() == SEEDS);
    }

    @AfterAll
    void tearDown() {
        Option.option(cluster).onPresent(c -> LifecycleAwait.bestEffort("cluster stop in tearDown()", c, c.stop()));
    }

    @Test
    void youngJoinersAreRetiredOnlyAfterTheGrace_andTheLeaderIsNeverItsOwnVictim() {
        var leader = cluster.currentLeader().unwrap();
        var joinedAtNanos = new LinkedHashMap<String, Long>();

        for (int i = 0; i < ADDED; i++) {
            var id = cluster.addNode().await(org.pragmatica.lang.io.TimeSpan.timeSpan(120).seconds())
                            .fold(cause -> failScenario("addNode failed: " + cause.message()), NodeId::id);
            joinedAtNanos.put(id, System.nanoTime());
        }
        log.info("LDV: leader={} added={} (configured ids, inside the {}s drain-safety grace)",
                 leader, joinedAtNanos.keySet(), DRAIN_SAFETY_GRACE.toSeconds());

        var observation = observe(leader, joinedAtNanos);
        log.info("LDV: observation {}", observation);

        if (!observation.leaderUnchanged()) {
            failScenario("leadership moved off " + leader + " during the window; the selection rule under test is the "
                         + "leader's own. " + observation);
        }
        assertThat(observation.earliestRetirementAfterJoinMs())
            .as("#1880 (young window): an added member left %s ms after its join, inside the %ss drain-safety grace, so a "
                + "drain path bypassed the grace (or the node crashed). %s",
                observation.earliestRetirementAfterJoinMs(), DRAIN_SAFETY_GRACE.toSeconds(), observation)
            .isGreaterThanOrEqualTo(DRAIN_SAFETY_GRACE.minus(JOIN_STAMP_SLACK).toMillis());
        assertThat(observation.members())
            .as("#1880 (after the grace): the surplus was not retired within %ss of the grace, so the grace disabled the "
                + "retirement instead of delaying it. %s", RETIRE_AFTER_GRACE.toSeconds(), observation)
            .hasSize(SEEDS);
        assertThat(observation.leaderSawSelfDeparting())
            .as("#1089: the leader %s marked itself Departing — it selected itself as a surplus victim, which it must never "
                + "be (its DRAIN is never delivered to itself). %s", leader, observation)
            .isFalse();
    }

    private record Observation(boolean leaderUnchanged,
                               boolean leaderSawSelfDeparting,
                               long earliestRetirementAfterJoinMs,
                               long elapsedMs,
                               Set<String> members) {}

    /// Polls until the cluster is back to [#SEEDS] members (the surplus retired) or the deadline (the grace plus
    /// [#RETIRE_AFTER_GRACE]) passes, recording the earliest moment any added member was absent, relative to its own join.
    private Observation observe(String leader, Map<String, Long> joinedAtNanos) {
        var start = System.nanoTime();
        var deadlineMs = DRAIN_SAFETY_GRACE.plus(RETIRE_AFTER_GRACE).toMillis();
        var earliestRetirementAfterJoinMs = Long.MAX_VALUE;
        var leaderUnchanged = true;
        var sawSelfDeparting = false;

        while (true) {
            var now = System.nanoTime();
            var elapsedMs = Duration.ofNanos(now - start).toMillis();
            var members = nodeIds();
            var currentLeader = cluster.currentLeader().or("none");

            leaderUnchanged &= currentLeader.equals(leader) || currentLeader.equals("none");
            if (!sawSelfDeparting && DEPARTING.equals(selfState(leader))) {
                sawSelfDeparting = true;
                log.info("LDV: t+{}ms leader {} marked ITSELF Departing; members={}", elapsedMs, leader, members);
            }
            for (var entry : joinedAtNanos.entrySet()) {
                if (!members.contains(entry.getKey())) {
                    earliestRetirementAfterJoinMs = Math.min(earliestRetirementAfterJoinMs,
                                                             Duration.ofNanos(now - entry.getValue()).toMillis());
                }
            }
            if (members.size() <= SEEDS || elapsedMs >= deadlineMs) {
                return new Observation(leaderUnchanged, sawSelfDeparting, earliestRetirementAfterJoinMs, elapsedMs, members);
            }
            LockSupport.parkNanos(POLL.toNanos());
        }
    }

    private String selfState(String leader) {
        return leaderNode(leader).map(node -> node.membershipFsm()
                                                  .memberStates()
                                                  .getOrDefault(node.self(), "absent"))
                                 .or("absent");
    }

    private Option<AetherNode> leaderNode(String id) {
        return cluster.getNode(id);
    }

    private Set<NodeId> countedOnLeader() {
        return cluster.currentLeader()
                      .flatMap(cluster::getNode)
                      .map(node -> node.membershipFsm().coreCountedMembers())
                      .or(Set.of());
    }

    private Set<String> nodeIds() {
        return cluster.allNodes().stream().map(node -> node.self().id()).collect(Collectors.toSet());
    }

    private static <T> T failScenario(String message) {
        return fail("SCENARIO (not evidence about #1089/#1880): " + message);
    }
}
