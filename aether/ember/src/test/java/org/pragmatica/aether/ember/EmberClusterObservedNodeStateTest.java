// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #727 review B1 — the POSITIVE control for [EmberCluster#status]'s node state.
///
/// `toNodeStatus` used to pass the string literal `"healthy"` into every `NodeStatus`, so every status
/// answer, every `/api/nodes/status` body and every failing test's state dump reported three healthy
/// nodes however broken the cluster was. It is now read from the node
/// (`AetherNode.isReady()` — consensus-active).
///
/// This class is one half of the pin and cannot stand alone. It shows that a cluster that really did
/// form reports [EmberCluster#STATE_ACTIVE], which rules out a replacement that always answers
/// "inactive" — a fabrication in the opposite direction, and one that a broken-cluster test alone
/// would never catch. The other half is `EmberClusterPartialStartFailureTest`, which shows a cluster
/// that did NOT form reports no active node at all. Reverting `observedState` to the old literal turns
/// that one red; replacing it with a constant `inactive` turns this one red. Neither test alone closes
/// the defect, which is exactly why the stream's healthy-cluster-only probes could not.
///
/// Ports are chosen at run time rather than hardcoded. A first version fixed them at 25700+ and failed
/// on its first run because port 25702 was held by another tenant of this shared box — a failure that
/// says nothing about the node state this class exists to pin. `EmberClusterPartialStartFailureTest`
/// keeps its fixed ports because it must pre-bind two of them on purpose.
class EmberClusterObservedNodeStateTest {
    private static final int CLUSTER_SIZE = 3;
    /// `EmberCluster.start` builds a slot pool of `2 * clusterSize`, so a block must cover twice the
    /// node count even though only [#CLUSTER_SIZE] slots are used here.
    private static final int SLOTS = 2 * CLUSTER_SIZE;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;
    private static final int FIRST_CANDIDATE_BASE = 25700;
    private static final int LAST_CANDIDATE_BASE = 27500;
    private static final int CANDIDATE_STEP = 200;
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped"))
                .describedAs("cluster stop must complete within %s", STOP_BOUND)
                .isEqualTo("stopped");
        }
    }

    @Test
    @Timeout(240)
    void everyNodeOfAFormedCluster_reportsItsObservedStateAsActive() {
        var basePort = freeBasePort();

        cluster = emberCluster(CLUSTER_SIZE,
                               basePort,
                               basePort + MGMT_OFFSET,
                               basePort + APP_HTTP_OFFSET,
                               "obs");

        assertThat(cluster.start().await(START_BOUND).fold(Cause::message, _ -> "started"))
            .describedAs("a three-node cluster on a verified-free port block at %d must form within %s",
                         basePort,
                         START_BOUND)
            .isEqualTo("started");

        var nodes = cluster.status().nodes();

        assertThat(nodes).hasSize(CLUSTER_SIZE);
        assertThat(nodes).allSatisfy(node -> assertThat(node.state())
            .describedAs("node %s of a formed cluster", node.id())
            .isEqualTo(EmberCluster.STATE_ACTIVE));
        assertThat(cluster.lastStartFailure().isEmpty())
            .describedAs("a start that succeeded must leave no start-failure snapshot behind")
            .isTrue();
    }

    /// The first candidate base whose whole block — cluster ports (QUIC, so UDP as well as TCP),
    /// management ports and app-HTTP ports — binds free right now. Not a guarantee: another tenant can
    /// take a port between this probe and the node's own bind. It removes the standing collision with
    /// whatever else is running on the box, which is the failure mode actually observed here, and a
    /// residual race would surface as the named bind failure the start assertion prints.
    private static int freeBasePort() {
        for (int base = FIRST_CANDIDATE_BASE; base <= LAST_CANDIDATE_BASE; base += CANDIDATE_STEP) {
            if (blockIsFree(base)) {
                return base;
            }
        }
        throw new AssertionError("no free block of " + SLOTS + " consecutive ports found between "
                                 + FIRST_CANDIDATE_BASE + " and " + LAST_CANDIDATE_BASE
                                 + "; this box is too busy to run a cluster test");
    }

    private static boolean blockIsFree(int base) {
        for (int slot = 0; slot < SLOTS; slot++) {
            if (!udpFree(base + slot)
                || !tcpFree(base + slot)
                || !tcpFree(base + MGMT_OFFSET + slot)
                || !tcpFree(base + APP_HTTP_OFFSET + slot)) {
                return false;
            }
        }
        return true;
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
        try (var socket = new DatagramSocket(null)) {
            socket.setReuseAddress(false);
            socket.bind(loopback(port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static InetSocketAddress loopback(int port) {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    }
}
