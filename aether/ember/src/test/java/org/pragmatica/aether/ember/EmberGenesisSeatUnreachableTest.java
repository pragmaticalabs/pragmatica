// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.ember;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.api.ClusterEvent;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #1654, end to end on REAL transport: genesis forms with an agreed seat whose process is dead, and the operator surface
/// (the cluster event log, read through a node's aggregator, the path the Management API serves) names that seat with its remedy.
///
/// Three configured cores with a fixed `cluster.genesis_voters`. `-1` and `-2` start and wait in genesis; `-3` is late. `-1`
/// is killed while still pending, so `-2` keeps `-1`'s last two reports (GenesisViewAgreement keeps them with no freshness, by
/// ruling: formation must not wait for a dead identity). `-3` then starts and the roster forms with `-1` as a seat that never
/// connects. The engine raises `genesis-seat-unreachable` (subject `-1`) and the cluster forms with the two live cores.
///
/// Pins the assembly: `AetherNode` binding the engine's warning sink to its event aggregator (`useOperatorWarningSink`) and
/// `RabiaEngine#signalUnreachableSeats` at the install. Removing either leaves the event log without the warning and this test red.
/// `GenesisSeatUnreachableTest` pins the same emission at unit level with a faked network.
///
/// [unverified: auto-heal replacing this seat] The heal half of the #1654 ruling is pinned for a voter that dies after
/// formation by `EmberVoterHealTest` (#2098); this class does not run a spare and does not assert a swap.
@PortBudget
class EmberGenesisSeatUnreachableTest {
    private static final int CLUSTER_SIZE = 3;
    private static final int SLOTS = 2 * CLUSTER_SIZE;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;
    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                                EmberTestPorts.POOL_LAST,
                                                                                EmberTestPorts.POOL_STEP,
                                                                                SLOTS,
                                                                                MGMT_OFFSET,
                                                                                APP_HTTP_OFFSET);
    private static final String PREFIX = "gsu";
    private static final String DEAD_SEAT = PREFIX + "-1";
    private static final String UNREACHABLE_CODE = "genesis-seat-unreachable";
    private static final String CLOSED_CODE = "genesis-seat-reachable";
    private static final Set<String> GENESIS_IDS = Set.of(PREFIX + "-1", PREFIX + "-2", PREFIX + "-3");
    private static final long HEAL_BOUND_MS = 240_000L;
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final long PENDING_SETTLE_MS = 5_000L;
    private static final long CONDITION_BOUND_MS = 90_000L;

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }

    @Test
    @Timeout(400)
    void anAgreedSeatThatNeverConnects_isNamedWithItsRemedy_andTheClusterStillForms() {
        startTwoPendingCores();
        assertThat(cluster.killNode(DEAD_SEAT, false).await(STOP_BOUND).isSuccess()).as("the seat's process is killed while pending").isTrue();
        assertThat(cluster.startHeldBackNodes().await(START_BOUND).isSuccess()).as("the late core starts").isTrue();

        awaitCondition("the two live cores install a three-seat roster that still names the dead seat", this::liveCoresFormedWithDeadSeat);
        awaitCondition("the unreachable-seat warning reaches a live node's event log", () -> !unreachableSubjects().isEmpty());

        assertThat(unreachableSubjects()).as("the warning names the one seat that never connected, and no other").containsExactly(DEAD_SEAT);
        var summary = unreachableEvents().getFirst().summary();

        assertThat(summary).as("the message names the seat, the effect on formation and the remedy")
                           .contains(DEAD_SEAT, "Formation is not blocked", "auto-heal", "Operator action");
        assertThat(cluster.getNode(DEAD_SEAT).isEmpty()).as("the seat never came back").isTrue();
    }

    /// Ask 2 of #1654: the seat that is dead from genesis is not kept for ever. Auto-heal provisions a replacement core, the voter
    /// reconciler swaps it into the dead seat's slot (one out, one in, never a shrink), and the warning is closed with
    /// `genesis-seat-reachable` because the seat left the roster. Bounded like the post-formation heal of [EmberVoterHealTest].
    @Test
    @Timeout(900)
    void anAgreedSeatThatNeverConnects_isReplacedByAutoHeal_withinTheBound() {
        startTwoPendingCores();
        assertThat(cluster.killNode(DEAD_SEAT, false).await(STOP_BOUND).isSuccess()).as("the seat's process is killed while pending").isTrue();
        assertThat(cluster.startHeldBackNodes().await(START_BOUND).isSuccess()).as("the late core starts").isTrue();
        awaitCondition("the two live cores install a three-seat roster that still names the dead seat", this::liveCoresFormedWithDeadSeat);

        var started = System.currentTimeMillis();

        awaitCondition(HEAL_BOUND_MS,
                       "a core that was not in the genesis roster replaces the dead seat: three voters, the dead seat gone",
                       this::deadSeatReplaced);
        System.out.println("EmberGenesisSeatUnreachableTest: replaced in " + (System.currentTimeMillis() - started) + " ms; voters="
                           + EmberNodeReplacementTest.installedVoters(cluster.allNodes().getFirst()));
        awaitCondition("the warning is closed because the seat left the roster", () -> closedSubjects().contains(DEAD_SEAT));
    }

    private boolean deadSeatReplaced() {
        var voters = cluster.allNodes()
                            .stream()
                            .filter(core -> !DEAD_SEAT.equals(core.self().id()))
                            .map(EmberNodeReplacementTest::installedVoters)
                            .toList();

        return !voters.isEmpty()
               && voters.stream()
                        .allMatch(set -> set.size() == CLUSTER_SIZE
                                         && set.stream().noneMatch(member -> DEAD_SEAT.equals(member.id()))
                                         && set.stream().anyMatch(member -> !GENESIS_IDS.contains(member.id())));
    }

    private Set<String> closedSubjects() {
        return cluster.allNodes()
                      .stream()
                      .flatMap(core -> core.eventAggregator().events().await().map(List::copyOf).or(List.of()).stream())
                      .filter(ClusterEvent.OperatorWarning.class::isInstance)
                      .map(ClusterEvent.OperatorWarning.class::cast)
                      .filter(event -> CLOSED_CODE.equals(event.details().get("code")))
                      .map(event -> event.details().get("subject"))
                      .collect(Collectors.toSet());
    }

    private boolean liveCoresFormedWithDeadSeat() {
        return cluster.allNodes().size() == CLUSTER_SIZE - 1
               && cluster.allNodes()
                         .stream()
                         .allMatch(core -> EmberAmnesiacRestartTest.runtime(core)
                                                                   .voterConfiguration()
                                                                   .filter(configuration -> configuration.members().size() == CLUSTER_SIZE)
                                                                   .filter(configuration -> configuration.members()
                                                                                                         .stream()
                                                                                                         .anyMatch(member -> DEAD_SEAT.equals(member.id())))
                                                                   .isPresent());
    }

    private Set<String> unreachableSubjects() {
        return unreachableEvents().stream().map(event -> event.details().get("subject")).collect(Collectors.toSet());
    }

    private List<ClusterEvent.OperatorWarning> unreachableEvents() {
        return cluster.allNodes()
                      .stream()
                      .flatMap(core -> core.eventAggregator()
                                           .events()
                                           .await()
                                           .map(List::copyOf)
                                           .or(List.of())
                                           .stream())
                      .filter(ClusterEvent.OperatorWarning.class::isInstance)
                      .map(ClusterEvent.OperatorWarning.class::cast)
                      .filter(event -> UNREACHABLE_CODE.equals(event.details().get("code")))
                      .toList();
    }

    /// Cores `-1` and `-2` started, `-3` held back but in the genesis roster: both wait in genesis. A genesis-pending start never
    /// settles on success, so the start is not awaited; a start that fails settles at once and a bind collision retries on a fresh block.
    private void startTwoPendingCores() {
        var attempted = new HashSet<Integer>();
        var collision = "";

        for (int attempt = 1; attempt <= EmberTestPorts.START_ATTEMPTS; attempt++) {
            var base = EmberTestPorts.freeBase(PORTS, attempted);

            attempted.add(base);
            cluster = emberCluster(CLUSTER_SIZE, base, base + MGMT_OFFSET, base + APP_HTTP_OFFSET, PREFIX);
            var starting = cluster.startWithLateGenesisMembers(Set.of(PREFIX + "-3"));
            var current = cluster;

            awaitCondition("the start fails or the two started cores run",
                           () -> starting.isResolved()
                                 || current.getNode(PREFIX + "-1").isPresent() && current.getNode(PREFIX + "-2").isPresent());

            if (!starting.isResolved()) {
                break;
            }

            var outcome = starting.await(START_BOUND).fold(Cause::message, _ -> "started");

            cluster.stop().await(STOP_BOUND);
            cluster = null;
            assertThat(EmberTestPorts.isBindCollision(outcome)).as("start failure: " + outcome).isTrue();
            collision = outcome;
        }

        assertThat(cluster).as("every cluster start lost a port between probe and bind; last: " + collision).isNotNull();
        sleep(PENDING_SETTLE_MS);
        assertThat(List.<AetherNode>of(cluster.getNode(PREFIX + "-1").unwrap(), cluster.getNode(PREFIX + "-2").unwrap()))
            .as("both started cores are genesis-pending, so each holds the other's reports")
            .allMatch(core -> EmberAmnesiacRestartTest.runtime(core).voterConfiguration().isEmpty());
    }

    private static void awaitCondition(String what, BooleanSupplier condition) {
        awaitCondition(CONDITION_BOUND_MS, what, condition);
    }

    private static void awaitCondition(long boundMs, String what, BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + boundMs;

        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting: " + what);
            }
            sleep(250);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
