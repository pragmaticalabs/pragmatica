// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.DatagramChannel;
import java.util.List;
import java.util.stream.IntStream;

import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;


/// #1070 review B1 — pins [EmberCluster#currentLeader] to the node whose own `isLeader()` holds.
///
/// It used to answer from the leader VIEW of whichever entry `ConcurrentHashMap` iterated first. With
/// fixed membership every entry holds the same view and the defect is invisible; after [EmberCluster#addNode]
/// the first entry can be the newborn — registered in the running-node map BEFORE its `start()` — which
/// holds no leader view at all. `currentLeader()`, `status()` and `getLeaderManagementPort()` then said
/// "no leader" while the elected leader was running, and probes reading membership through the same
/// entry counted the newborn's seeded core set (`MembershipFsm.seed`) as a completed scale-up.
///
/// The fixture reaches that exact state on purpose: [#PREFIX] is chosen so that `newborn-4` iterates FIRST
/// in a default-capacity `ConcurrentHashMap` holding `newborn-1..4` (JDK `String.hashCode` and `CHM.spread`
/// have not changed across releases; verified on JDK 25). That is a fixture precondition, not an assumption
/// — the test asserts it, and asserts the newborn holds no leader view, before it asserts anything about the
/// code under test. If either control fails the test says so instead of passing on a fixture that no longer
/// reaches the defect; the second control is time-sensitive (a join faster than the assertions would trip
/// it), and it can only fail in the red direction. Reverting `currentLeader()` to `findFirst()` +
/// [AetherNode#leader] turns the pin red.
class EmberClusterCurrentLeaderTest {
    private static final int CLUSTER_SIZE = 3;
    /// `EmberCluster.start` builds a slot pool of `2 * clusterSize`; the newborn takes the fourth slot.
    private static final int SLOTS = 2 * CLUSTER_SIZE;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;
    /// Disjoint from the ranges `EmberClusterObservedNodeStateTest` (25700–27500) and
    /// `EmberBootstrapAdminKeyAuthTest` (27700–29500) probe, so the three never contend.
    private static final int FIRST_CANDIDATE_BASE = 29700;
    private static final int LAST_CANDIDATE_BASE = 31500;
    private static final int CANDIDATE_STEP = 200;
    private static final String PREFIX = "newborn";
    private static final String NEWBORN_ID = PREFIX + "-" + (CLUSTER_SIZE + 1);
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final long POLL_INTERVAL_MS = 250L;
    private static final int LEADER_ELECTION_POLLS = 240;

    private Option<EmberCluster> cluster = none();

    @AfterEach
    void tearDown() {
        cluster.onPresent(EmberClusterCurrentLeaderTest::stopWithinBound);
    }

    private static void stopWithinBound(EmberCluster running) {
        var outcome = running.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped");

        assertThat(outcome).describedAs("cluster stop must complete within %s", STOP_BOUND).isEqualTo("stopped");
    }

    @Test
    @Timeout(300)
    void currentLeader_isTheNodeClaimingLeadership_notTheFirstMapEntry() {
        var basePort = freeBasePort();
        var running = emberCluster(CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, PREFIX);

        cluster = some(running);
        assertThat(running.start().await(START_BOUND).fold(Cause::message, _ -> "started")).describedAs("a three-node cluster on a verified-free port block at %d must form within %s",
                                                                                                        basePort,
                                                                                                        START_BOUND)
                  .isEqualTo("started");
        var leaderId = awaitLeader(running).or("none");

        assertThat(leaderId).describedAs("a formed cluster must elect a leader within %d polls of %dms; without one there is nothing to pin",
                                         LEADER_ELECTION_POLLS,
                                         POLL_INTERVAL_MS)
                  .isNotEqualTo("none");
        assertThat(claimants(running)).describedAs("currentLeader() must name the one running node whose own isLeader() holds")
                  .containsExactly(leaderId);
        // Registers the newborn in the running-node map synchronously, BEFORE its start() completes.
        var joining = running.addNode();
        // Fixture control 1: the map now iterates the newborn first — the entry the old findFirst() read.
        assertThat(running.allNodes().getFirst().self().id()).describedAs("FIXTURE PRECONDITION: the newborn must be the first ConcurrentHashMap entry, or this "
                                                                         + "test cannot reach the defect it pins (pick a prefix for which it is)")
                  .isEqualTo(NEWBORN_ID);
        // Fixture control 2: the newborn has no leader view yet, so the old code answered "none" from it.
        assertThat(running.getNode(NEWBORN_ID).flatMap(AetherNode::leader).isEmpty()).describedAs("FIXTURE PRECONDITION: the newborn must hold no leader view at this instant; if it "
                                                                                                 + "already learned the leader the old findFirst() would have answered correctly too")
                  .isTrue();
        // The pin: every leader accessor still answers from the node that claims leadership.
        assertThat(running.currentLeader()).describedAs("currentLeader() must still name %s while the newborn is the first map entry",
                                                        leaderId)
                  .isEqualTo(some(leaderId));
        assertThat(running.status().leaderId()).describedAs("status().leaderId() derives from currentLeader()")
                  .isEqualTo(leaderId);
        assertThat(flaggedLeaders(running)).describedAs("status() must flag exactly the claiming node as leader")
                  .containsExactly(leaderId);
        assertThat(running.getLeaderManagementPort()).describedAs("getLeaderManagementPort() must resolve the claiming node's management port")
                  .isEqualTo(some(MGMT_OFFSET + leaderClusterPort(running, leaderId)));
        assertThat(joining.await(START_BOUND).fold(Cause::message, NodeId::id)).describedAs("the newborn must join within %s so teardown stops a formed cluster",
                                                                                            START_BOUND)
                  .isEqualTo(NEWBORN_ID);
    }

    private static List<String> claimants(EmberCluster running) {
        return running.allNodes()
                      .stream()
                      .filter(AetherNode::isLeader)
                      .map(EmberClusterCurrentLeaderTest::selfId)
                      .toList();
    }

    private static String selfId(AetherNode node) {
        return node.self()
                   .id();
    }

    private static List<String> flaggedLeaders(EmberCluster running) {
        var nodes = running.status().nodes();

        return nodes.stream()
                    .filter(EmberCluster.NodeStatus::isLeader)
                    .map(EmberCluster.NodeStatus::id)
                    .toList();
    }

    /// The leader's cluster port; the management port is that plus [#MGMT_OFFSET] by construction of the fixture.
    private static int leaderClusterPort(EmberCluster running, String leaderId) {
        var info = running.getNodeInfos()
                          .stream()
                          .filter(candidate -> candidate.id()
                                                        .id()
                                                        .equals(leaderId))
                          .findFirst();

        return info.map(found -> found.address()
                                      .port())
                   .orElse(-1);
    }

    /// Leadership is not established at the instant `start()` returns (see
    /// `EmberBootstrapAdminKeyAuthTest`), so this polls rather than asserting immediately.
    private static Option<String> awaitLeader(EmberCluster running) {
        return IntStream.range(0, LEADER_ELECTION_POLLS)
                        .mapToObj(_ -> pollLeader(running))
                        .filter(Option::isPresent)
                        .findFirst()
                        .orElse(none());
    }

    private static Option<String> pollLeader(EmberCluster running) {
        return running.currentLeader()
                      .onEmpty(EmberClusterCurrentLeaderTest::sleepQuietly);
    }

    @SuppressWarnings("JBCT-EX-01")
    private static void sleepQuietly() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// The first candidate base whose whole block — cluster ports (QUIC, so UDP as well as TCP),
    /// management ports and app-HTTP ports — binds free right now. Same helper and rationale as
    /// `EmberClusterObservedNodeStateTest`, on a disjoint candidate range.
    private static int freeBasePort() {
        return IntStream.iterate(FIRST_CANDIDATE_BASE,
                                 base -> base <= LAST_CANDIDATE_BASE,
                                 base -> base + CANDIDATE_STEP)
                        .filter(EmberClusterCurrentLeaderTest::blockIsFree)
                        .findFirst()
                        .orElseGet(() -> fail("no free block of " + SLOTS
                                             + " consecutive ports found between " + FIRST_CANDIDATE_BASE
                                             + " and " + LAST_CANDIDATE_BASE
                                             + "; this box is too busy to run a cluster test"));
    }

    private static boolean blockIsFree(int base) {
        return IntStream.range(0, SLOTS).allMatch(slot -> udpFree(base + slot)
                                                          && tcpFree(base + slot)
                                                          && tcpFree(base + MGMT_OFFSET + slot)
                                                          && tcpFree(base + APP_HTTP_OFFSET + slot));
    }

    private static boolean tcpFree(int port) {
        try (var socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(loopback(port));

            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean udpFree(int port) {
        try (var channel = DatagramChannel.open()) {
            channel.socket().setReuseAddress(false);
            channel.bind(loopback(port));

            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static InetSocketAddress loopback(int port) {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    }
}
