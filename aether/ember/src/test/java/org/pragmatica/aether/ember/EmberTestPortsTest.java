// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;

import org.pragmatica.aether.ember.EmberPorts.PortLease;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/// #1667 acceptance on the lease-based allocator: a port taken after the claim does not fail the test, the start moves to
/// the next sub-window. The start and stop outcomes are injected, so the tests depend on no real cluster.
class EmberTestPortsTest {
    private static final EmberTestPorts.Block BLOCK = new EmberTestPorts.Block(3, 40, 80);
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final Cause BIND_COLLISION = Causes.cause("Transport failure: java.net.BindException: Address already in use");
    private static final Cause STOP_REFUSED = Causes.cause("stop refused");
    private static final Cause OTHER_FAILURE = Causes.cause("Transport failure: handshake refused");

    @Test
    void startedCluster_bindCollision_retriesOnTheNextSubWindow() {
        var leases = new ArrayList<PortLease>();

        var started = EmberTestPorts.startedCluster(() -> EmberTestPorts.claim(BLOCK),
                                                    lease -> {
                                                        leases.add(lease);
                                                        return lease;
                                                    },
                                                    _ -> leases.size() == 1
                                                         ? BIND_COLLISION.<Unit>promise()
                                                         : Promise.success(Unit.unit()),
                                                    _ -> Promise.success(Unit.unit()),
                                                    START_BOUND);

        try {
            assertThat(leases).as("control: the first start collided, so a retry happened").hasSize(2);
            assertThat(leases.get(1).base()).as("the retry runs on a different sub-window").isNotEqualTo(leases.get(0).base());
            assertThat(started).isSameAs(leases.get(1));
        } finally {
            leases.forEach(PortLease::close);
        }
    }

    /// A failed start whose cleanup also fails is reported, never retried beside a half-stopped cluster.
    @Test
    void startedCluster_cleanupFails_failsWithTheCleanupError() {
        var leases = new ArrayList<PortLease>();

        try {
            assertThatThrownBy(() -> EmberTestPorts.startedCluster(() -> EmberTestPorts.claim(BLOCK),
                                                                   lease -> {
                                                                       leases.add(lease);
                                                                       return lease;
                                                                   },
                                                                   _ -> BIND_COLLISION.<Unit>promise(),
                                                                   _ -> STOP_REFUSED.<Unit>promise(),
                                                                   START_BOUND))
                .hasMessageContaining("its cleanup failed too: " + STOP_REFUSED.message());
            assertThat(leases).as("no second cluster was started beside the unstopped one").hasSize(1);
        } finally {
            leases.forEach(PortLease::close);
        }
    }

    /// Control for the test above: the same injected bind collision with a clean stop IS retried, every attempt.
    @Test
    void startedCluster_cleanStopAfterBindCollision_retriesUpToTheBound() {
        var leases = new ArrayList<PortLease>();

        try {
            assertThatThrownBy(() -> EmberTestPorts.startedCluster(() -> EmberTestPorts.claim(BLOCK),
                                                                   lease -> {
                                                                       leases.add(lease);
                                                                       return lease;
                                                                   },
                                                                   _ -> BIND_COLLISION.<Unit>promise(),
                                                                   _ -> Promise.success(Unit.unit()),
                                                                   START_BOUND))
                .hasMessageContaining("every one of " + EmberTestPorts.START_ATTEMPTS);
            assertThat(leases).hasSize(EmberTestPorts.START_ATTEMPTS);
        } finally {
            leases.forEach(PortLease::close);
        }
    }

    @Test
    void startedCluster_failureThatIsNotABindCollision_failsAtOnce() {
        var leases = new ArrayList<PortLease>();

        try {
            assertThatThrownBy(() -> EmberTestPorts.startedCluster(() -> EmberTestPorts.claim(BLOCK),
                                                                   lease -> {
                                                                       leases.add(lease);
                                                                       return lease;
                                                                   },
                                                                   _ -> OTHER_FAILURE.<Unit>promise(),
                                                                   _ -> Promise.success(Unit.unit()),
                                                                   START_BOUND))
                .hasMessageContaining(OTHER_FAILURE.message());
            assertThat(leases).as("not retried").hasSize(1);
        } finally {
            leases.forEach(PortLease::close);
        }
    }

    @Test
    void isBindCollision_recognisesTheTransportsBindFailure_only() {
        assertThat(EmberTestPorts.isBindCollision(BIND_COLLISION.message())).isTrue();
        assertThat(EmberTestPorts.isBindCollision("QuicTransportError.BindFailed: Address already in use")).isTrue();
        assertThat(EmberTestPorts.isBindCollision(OTHER_FAILURE.message())).isFalse();
    }

    @Test
    void hold_bindsThePortsOnALease_andFreesThemOnClose() throws IOException {
        int base;

        try (var held = EmberTestPorts.hold(BLOCK, List.of(EmberTestPorts.Hold.tcp(30), EmberTestPorts.Hold.udp(31)))) {
            base = held.base();
            assertThatThrownBy(() -> new ServerSocket(base + 30)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> new DatagramSocket(base + 31)).isInstanceOf(IOException.class);
        }

        try (var tcp = new ServerSocket(base + 30); var udp = new DatagramSocket(base + 31)) {
            assertThat(tcp.isBound() && udp.isBound()).as("the held ports are reclaimable after close").isTrue();
        }
    }
}
