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
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #1089 TRIPWIRE — the leader can be selected as a surplus-drain victim, and a leader so selected never drains.
///
/// The mechanism (#1089, open): `LeaderReconciler.selectDrainVictims` considers the leader LAST, after every other
/// member, but does not exclude it; and the DRAIN command rides only the leader's broadcast `ClusterSyncPing`, which
/// `ClusterSyncState.dispatchPing` never sends to self. A leader that selects itself therefore marks itself
/// `Departing`, never runs its `DrainProcedure` (no departure push; in cloud the CTM drain-grace backstop later
/// terminates it ungracefully, the #427 loss mode), and #1058 withdraws it to MEMBER.
///
/// ## Why this scenario selects the leader deterministically
/// Leader-last selection only reaches the leader when no other member is eligible. The tiers
/// (`appendTieredVictims`): ephemeral (minted-ULID) non-owners at any age, then CONFIGURED non-owners past the
/// drain-safety grace, then slice owners through the availability guard. So the scenario arranges that every
/// non-leader is either drained or ineligible, with no slice and no CTM provisioning involved:
/// 1. three configured seeds form and are left to pass the drain-safety grace (`2 × splitTimeout`; this class
///    raises `splitTimeout` to 60 s via [EmberCluster#withRaisedSwimTimeouts], so the grace is 120 s);
/// 2. three cores are added with `EmberCluster.addNode()`, which mints CONFIGURED ids (`ldv-4..6`, not ULIDs) and
///    does not change the desired core count (3) — a surplus of 3 whose added members stay INELIGIBLE (young,
///    configured) for the whole observation window;
/// 3. the reconciler drains the two mature non-leader seeds, and the remaining surplus has exactly one eligible
///    candidate: the leader.
///
/// This replaces the tripwire that lived in [ArtifactChurnSurvival5to7to5ProbeTest]. That one depended on the
/// 5→7 up-leg producing configured ids inside the grace; once #1812 restored CTM provisioning, the up-leg's cores
/// carry minted ids, are drained first, and the leader is never selected there — so it printed a false
/// "#1089 landed".
///
/// ## Reading a red
/// - **SCENARIO** messages: the arrangement above did not hold (a seed was not drained, an added node left, the
///   leader changed). Nothing about #1089 can be concluded; fix the harness.
/// - **#1089 LANDED (shape B: the leader is excluded from selection)**: the scenario held, the leader was the only
///   eligible candidate, and it never marked itself `Departing`.
/// - **#1089 LANDED (shape A: the drain reaches the leader itself)**: the leader was selected and then left the
///   cluster, i.e. its `DrainProcedure` ran.
///
/// Either landed message means: delete this tripwire, and replace it with the positive property the fix
/// establishes (shape B: a surplus with only the leader eligible defers rather than selecting it; shape A: a
/// leader selected as a victim drains gracefully and leadership moves).
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

    /// `LeaderReconciler.computeDrainSafetyGrace` = 2 × `splitTimeout`; [EmberCluster#withRaisedSwimTimeouts] sets
    /// `splitTimeout` to 60 s.
    private static final Duration DRAIN_SAFETY_GRACE = Duration.ofSeconds(120);
    private static final Duration MATURITY_MARGIN = Duration.ofSeconds(5);
    /// How long the leader must stay a member after it first marks itself `Departing` for "its DrainProcedure
    /// never ran" to mean something: a delivered drain completes (push, `handleSelfDrain`) well inside this.
    private static final Duration DRAIN_DWELL = Duration.ofSeconds(25);
    /// Ends the observation while the added members are still inside the grace (they joined after the seeds
    /// matured; 120 s grace minus join time minus margin).
    private static final Duration OBSERVE_BUDGET = Duration.ofSeconds(90);
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
    void leaderIsTheOnlyEligibleDrainVictim_selectsItself_andNeverDrains_tripwireUntil1089() {
        var leader = cluster.currentLeader().unwrap();
        var seeds = nodeIds();
        var otherSeeds = seeds.stream().filter(id -> !id.equals(leader)).collect(Collectors.toSet());

        awaitSeedsPastDrainSafetyGrace(leader, seeds);

        var added = new LinkedHashSet<String>();
        for (int i = 0; i < ADDED; i++) {
            added.add(cluster.addNode().await(org.pragmatica.lang.io.TimeSpan.timeSpan(120).seconds())
                             .fold(cause -> failScenario("addNode failed: " + cause.message()), NodeId::id));
        }
        log.info("LDV: leader={} seeds={} added={} (configured ids, inside the {}s drain-safety grace)",
                 leader, seeds, added, DRAIN_SAFETY_GRACE.toSeconds());

        var observation = observe(leader, otherSeeds, added);
        log.info("LDV: observation {}", observation);

        if (!observation.leaderUnchanged()) {
            failScenario("leadership moved off " + leader + " during the window; the selection rule under test is the "
                         + "leader's own. " + observation);
        }
        if (!observation.addedAllPresent()) {
            failScenario("an added (young, configured) member left the cluster, so it was drained while ineligible "
                         + "or crashed. " + observation);
        }
        if (!observation.otherSeedsDrained()) {
            failScenario("the two mature non-leader seeds " + otherSeeds + " were not both drained within "
                         + OBSERVE_BUDGET.toSeconds() + "s, so the leader was never the only eligible candidate. "
                         + observation);
        }

        assertThat(observation.leaderSawSelfDeparting())
            .as("#1089 LANDED (shape B: the leader is excluded from selection) — the surplus had the leader %s as its ONLY "
                + "eligible candidate (both mature seeds drained, %s young and configured), and the leader never "
                + "marked itself Departing. Delete this tripwire and pin the positive property instead: such a "
                + "surplus defers. %s", leader, added, observation)
            .isTrue();
        assertThat(observation.leaderStayedAfterDeparting())
            .as("#1089 LANDED (shape A: the drain reaches the leader itself) — the leader %s selected itself and then "
                + "left the cluster within %ss, so its DrainProcedure ran. Delete this tripwire and pin the positive "
                + "property instead: a leader-victim drains gracefully and leadership moves. %s",
                leader, DRAIN_DWELL.toSeconds(), observation)
            .isTrue();
    }

    private record Observation(boolean leaderUnchanged,
                               boolean addedAllPresent,
                               boolean otherSeedsDrained,
                               boolean leaderSawSelfDeparting,
                               boolean leaderStayedAfterDeparting,
                               long firstSelfDepartingMs,
                               long elapsedMs,
                               Set<String> members) {}

    /// Polls the cluster until the leader has stayed a member for [#DRAIN_DWELL] after first marking itself
    /// `Departing`, or the budget (bounded by the added members' grace) runs out.
    private Observation observe(String leader, Set<String> otherSeeds, Set<String> added) {
        var start = System.nanoTime();
        var firstSelfDepartingMs = -1L;
        var leaderUnchanged = true;
        var leaderLeftAfterDeparting = false;

        while (true) {
            var elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
            var members = nodeIds();
            var currentLeader = cluster.currentLeader().or("none");

            leaderUnchanged &= currentLeader.equals(leader) || currentLeader.equals("none");
            if (firstSelfDepartingMs < 0 && DEPARTING.equals(selfState(leader))) {
                firstSelfDepartingMs = elapsedMs;
                log.info("LDV: t+{}ms leader {} marked ITSELF Departing; members={}", elapsedMs, leader, members);
            }
            if (firstSelfDepartingMs >= 0 && !members.contains(leader)) {
                leaderLeftAfterDeparting = true;
            }

            var dwellDone = firstSelfDepartingMs >= 0 && elapsedMs - firstSelfDepartingMs >= DRAIN_DWELL.toMillis();
            if (dwellDone || leaderLeftAfterDeparting || elapsedMs >= OBSERVE_BUDGET.toMillis()) {
                return new Observation(leaderUnchanged,
                                       members.containsAll(added),
                                       otherSeeds.stream().noneMatch(members::contains),
                                       firstSelfDepartingMs >= 0,
                                       firstSelfDepartingMs >= 0 && !leaderLeftAfterDeparting && dwellDone,
                                       firstSelfDepartingMs,
                                       elapsedMs,
                                       members);
            }
            LockSupport.parkNanos(POLL.toNanos());
        }
    }

    /// Waits until the leader has tracked every seed for longer than the drain-safety grace, so the seeds are
    /// eligible configured victims and the added cores (joining afterwards) are not.
    private void awaitSeedsPastDrainSafetyGrace(String leader, Set<String> seeds) {
        var threshold = DRAIN_SAFETY_GRACE.plus(MATURITY_MARGIN).toMillis();

        await().atMost(DRAIN_SAFETY_GRACE.plus(FORM_TIMEOUT))
               .pollInterval(Duration.ofSeconds(1))
               .until(() -> leaderNode(leader).map(node -> seeds.stream()
                                                                 .allMatch(id -> node.membershipFsm()
                                                                                     .memberAgeMs(new NodeId(id))
                                                                                     .filter(age -> age >= threshold)
                                                                                     .isPresent()))
                                              .or(false));
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
        return fail("SCENARIO (not evidence about #1089): " + message);
    }
}
