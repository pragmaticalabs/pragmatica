// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #1667 acceptance: a port taken between the probe and the cluster's bind does not fail the test; the start moves
/// to a fresh block. The collision is made deterministic by taking a port of the probed block inside the factory,
/// after the probe and before `start()`.
class EmberTestPortsTest {
    private static final EmberTestPorts.Block BLOCK = new EmberTestPorts.Block(46100, 46900, 200, 3, 40, 80);
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();

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

        cluster = EmberTestPorts.startedCluster(BLOCK, base -> {
            bases.add(base);
            if (bases.size() == 1) {
                taken.add(takeTcp(base + BLOCK.mgmtOffset()));
            }
            return emberCluster(3, base, base + BLOCK.mgmtOffset(), base + BLOCK.appOffset(), "ports");
        }, START_BOUND);

        assertThat(bases).as("control: the first probed block was taken before the cluster bound it, so a second "
                             + "block was needed").hasSize(2);
        assertThat(bases.get(1)).isNotEqualTo(bases.get(0));
        assertThat(cluster.nodeCount()).isEqualTo(3);
    }

    @Test
    void isBindCollision_recognisesTheTransportsBindFailure_only() {
        assertThat(EmberTestPorts.isBindCollision("Transport failure: io.netty.channel.unix.Errors$NativeIoException: "
                                                  + "bind(..) failed: Address already in use")).isTrue();
        assertThat(EmberTestPorts.isBindCollision("Transport failure: java.net.BindException: Address already in use"))
            .isTrue();
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
