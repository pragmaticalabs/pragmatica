// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/// #1667 acceptance: a port taken between the probe and the cluster's bind does not fail the test; the start moves
/// to a fresh block. The collision is made deterministic by taking a port of the probed block inside the factory,
/// after the probe and before `start()`.
class EmberTestPortsTest {
    private static final EmberTestPorts.Block BLOCK = new EmberTestPorts.Block(46100, 46900, 200, 3, 40, 80);
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();

    private static final Cause BIND_COLLISION = Causes.cause("Transport failure: java.net.BindException: Address already in use");
    private static final Cause STOP_REFUSED = Causes.cause("stop refused");

    private final List<ServerSocket> taken = new ArrayList<>();
    private EmberCluster cluster;

    @AfterEach
    void tearDown() throws IOException {
        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }

        for (var socket : taken) {
            socket.close();
        }
    }

    @Test
    @Timeout(300)
    void startedCluster_portTakenBetweenProbeAndStart_startsOnAFreshBlock() throws IOException {
        var bases = new ArrayList<Integer>();

        cluster = EmberTestPorts.startedCluster(BLOCK,
                                                base -> {
                                                    bases.add(base);
                                                    if (bases.size() == 1) {
                                                    taken.add(takeTcp(base + BLOCK.mgmtOffset()));
                                                }

                                                    return emberCluster(3,
                                                                        base,
                                                                        base + BLOCK.mgmtOffset(),
                                                                        base + BLOCK.appOffset(),
                                                                        "ports");
                                                },
                                                START_BOUND);
        assertThat(bases).as("control: the first probed block was taken before the cluster bound it, so a second "
                            + "block was needed")
                  .hasSize(2);
        assertThat(bases.get(1)).isNotEqualTo(bases.get(0));
        assertThat(cluster.nodeCount()).isEqualTo(3);
    }

    /// #1707 review: the retry's base choice is handed the base that lost its bind, so it is excluded even if its
    /// ports are free again by then. (Probing alone cannot pin this: the failed cluster's ports usually linger, so the
    /// probe skips that base with or without the exclusion.) The first cluster collides with itself: app = management.
    @Test
    @Timeout(300)
    void startedCluster_retry_excludesTheBaseThatLostItsBind() {
        var bases = new ArrayList<Integer>();
        var excludedPerChoice = new ArrayList<Set<Integer>>();

        cluster = EmberTestPorts.startedCluster(base -> {
                                                    bases.add(base);
                                                    var appBase = bases.size() == 1
                                                                  ? base + BLOCK.mgmtOffset()
                                                                  : base + BLOCK.appOffset();

                                                    return emberCluster(3,
                                                                        base,
                                                                        base + BLOCK.mgmtOffset(),
                                                                        appBase,
                                                                        "ports");
                                                },
                                                START_BOUND,
                                                excluded -> {
                                                    excludedPerChoice.add(excluded);

                                                    return EmberTestPorts.freeBase(BLOCK, excluded);
                                                });
        assertThat(bases).as("control: the self-colliding first cluster forced a retry").hasSize(2);
        assertThat(excludedPerChoice).containsExactly(Set.of(),
                                                      Set.of(bases.get(0)));
        assertThat(bases.get(1)).isNotEqualTo(bases.get(0));
    }

    /// #1707 review: a failed start whose cleanup also fails is reported, never retried beside a half-stopped cluster.
    /// Round 2: the start and stop outcomes are injected, so the test cannot depend on how fast a real stop is. The start
    /// fails with a bind collision, which would otherwise be retried; only the failed stop ends the loop.
    @Test
    void startedCluster_cleanupFails_failsWithTheCleanupError() {
        var built = new ArrayList<Integer>();

        assertThatThrownBy(() -> EmberTestPorts.startedCluster(base -> {
                                                                   built.add(base);
                                                                   return base;
                                                               },
                                                               _ -> BIND_COLLISION.<Unit>promise(),
                                                               _ -> STOP_REFUSED.<Unit>promise(),
                                                               START_BOUND,
                                                               attempted -> 46100 + 200 * attempted.size()))
            .hasMessageContaining("its cleanup failed too: " + STOP_REFUSED.message());
        assertThat(built).as("no second cluster was started beside the unstopped one").containsExactly(46100);
    }

    /// Control for the test above: the same injected bind collision with a clean stop IS retried, every attempt.
    @Test
    void startedCluster_cleanStopAfterBindCollision_retriesUpToTheBound() {
        var built = new ArrayList<Integer>();

        assertThatThrownBy(() -> EmberTestPorts.startedCluster(base -> {
                                                                   built.add(base);
                                                                   return base;
                                                               },
                                                               _ -> BIND_COLLISION.<Unit>promise(),
                                                               _ -> Promise.success(Unit.unit()),
                                                               START_BOUND,
                                                               attempted -> 46100 + 200 * attempted.size()))
            .hasMessageContaining("every one of " + EmberTestPorts.START_ATTEMPTS);
        assertThat(built).hasSize(EmberTestPorts.START_ATTEMPTS);
    }

    /// #1707 review: a base that lost a bind is excluded from the next attempt, even once the colliding port is free.
    @Test
    void freeBase_excludedBase_isNotChosenAgain() {
        var first = EmberTestPorts.freeBase(BLOCK);

        assertThat(EmberTestPorts.freeBase(BLOCK, Set.of(first))).isNotEqualTo(first);
    }

    /// #1707 review: a reserved extra port (EmberWorkerDeadSeedTest's dead seed) is part of the block's check.
    @Test
    void freeBase_reservedOffsetTaken_skipsThatBlock() throws IOException {
        var withDeadSeed = new EmberTestPorts.Block(BLOCK.first(),
                                                    BLOCK.last(),
                                                    BLOCK.step(),
                                                    BLOCK.slots(),
                                                    BLOCK.mgmtOffset(),
                                                    BLOCK.appOffset(),
                                                    List.of(30));
        var first = EmberTestPorts.freeBase(withDeadSeed);

        taken.add(takeTcp(first + 30));
        assertThat(EmberTestPorts.freeBase(withDeadSeed)).isNotEqualTo(first);
        assertThat(EmberTestPorts.freeBase(BLOCK)).as("control: without the reservation the block is free")
                  .isEqualTo(first);
    }

    @Test
    void isBindCollision_recognisesTheTransportsBindFailure_only() {
        assertThat(EmberTestPorts.isBindCollision("Transport failure: io.netty.channel.unix.Errors$NativeIoException: "
                                                 + "bind(..) failed: Address already in use")).isTrue();
        assertThat(EmberTestPorts.isBindCollision("Transport failure: java.net.BindException: Address already in use")).isTrue();
        assertThat(EmberTestPorts.isBindCollision("Cluster startup failed: quorum not reached")).isFalse();
    }

    private static ServerSocket takeTcp(int port) {
        try {
            // The wildcard address, as EmberClusterPartialStartFailureTest takes its ports: it collides with the
            // node's bind on any interface.
            return new ServerSocket(port);
        } catch (IOException e) {
            throw new AssertionError("could not take port " + port, e);
        }
    }
}
