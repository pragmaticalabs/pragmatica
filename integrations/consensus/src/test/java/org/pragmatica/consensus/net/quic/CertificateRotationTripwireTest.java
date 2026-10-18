/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package org.pragmatica.consensus.net.quic;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.quic.TransportTripwireFixtures.TripwireTopology;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.messaging.StreamType;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.AWAIT;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.awaitTrue;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.clientSsl;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.connected;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.holdsFor;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.infoOf;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.serverSsl;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.startedNetwork;

/// Tripwire pairs for D-01 and D-02 (cluster T assessment, rc4 `7c786db0d3`).
///
/// D-01: `rotateCertificate` is a server restart (`stopAndRestartServer`: stop the old server and shut its event-loop
/// group down, build a new one on the same port), and every connection a node ACCEPTED lives on that socket. The code
/// comment says "existing connections drain naturally" and `tls-certificates.md` says they are "unaffected ... sub-second".
///
/// D-02 (second review: mechanism CONFIRMED, severity lowered): `rebuildAndStart` assigns the new server object before
/// `start(port)`; if the bind then fails, `boundPort()` is empty and every later rotation fails with "Server not
/// running". The bind failure is forced here by taking the port in the window between the old server closing it and
/// the new one binding (the old event-loop group's graceful shutdown keeps that window open for seconds).
///
/// How D-01 is fixed (for instance rotation without a server restart) is left to the overhaul; the
/// real assertions state only the operator-visible claim: an accepted link survives a rotation.
@Timeout(120)
class CertificateRotationTripwireTest {
    private static final NodeId LOW = new NodeId("aaa-rot");
    private static final NodeId HIGH = new NodeId("zzz-rot");
    private static final TimeSpan WINDOW = TimeSpan.timeSpan(3).seconds();
    private static final TimeSpan FAST_PING = TimeSpan.timeSpan(30).seconds();

    private final List<QuicClusterNetwork> networks = new ArrayList<>();
    private final List<LaneProbe> deliveredToHigh = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        networks.forEach(network -> network.stop().await(AWAIT));
        networks.clear();
    }

    private record Pair(QuicClusterNetwork low, QuicClusterNetwork high) {}

    private Pair connectedPair() {
        var highRouter = MessageRouter.mutable();

        highRouter.addRoute(LaneProbe.class, deliveredToHigh::add);
        var low = startedNetwork(new TripwireTopology(LOW, false, FAST_PING), MessageRouter.mutable(), networks);
        var high = startedNetwork(new TripwireTopology(HIGH, false, FAST_PING), highRouter, networks);

        low.dialForTests(infoOf(HIGH, high), false);
        awaitTrue(() -> connected(low, HIGH) && connected(high, LOW), AWAIT, "both ends CONNECTED");

        return new Pair(low, high);
    }

    /// True when the acceptor kept the very same connection to the dialer for the whole window after a rotation.
    private boolean acceptedLinkSurvivesARotation() {
        var pair = connectedPair();
        var before = pair.high().activeConnectionForTests(LOW).or((QuicPeerConnection) null);

        assertThat(before).as("control: the acceptor holds a connection to the dialer before the rotation").isNotNull();
        pair.high()
            .rotateCertificate(serverSsl("rotated-server"), clientSsl("rotated-client"))
            .await(AWAIT)
            .onFailure(cause -> org.junit.jupiter.api.Assertions.fail("control: the rotation itself must succeed: " + cause.message()));

        return holdsFor(() -> pair.high().activeConnectionForTests(LOW).or((QuicPeerConnection) null) == before, WINDOW);
    }

    @Test
    void rotateCertificate_dropsTheLinksTheNodeAccepted() {
        assertThat(acceptedLinkSurvivesARotation())
            .as("TRIPWIRE D-01: after rotating the acceptor's certificate, the connection it had accepted is gone within "
                + "3 s (the rotation restarts the server and shuts its event-loop group down). Accepted links now "
                + "survive a rotation. Delete this tripwire and enable "
                + "rotateCertificate_keepsTheLinksTheNodeAccepted.")
            .isFalse();
    }

    @Disabled("D-01: enable when certificate rotation no longer restarts the server (links accepted before the rotation survive it). "
              + "Today red: the accepted connection is closed.")
    @Test
    void rotateCertificate_keepsTheLinksTheNodeAccepted() {
        assertThat(acceptedLinkSurvivesARotation()).as("accepted link survives the rotation").isTrue();
    }

    /// Rotation 1 loses the port race and fails; the port is then released; returns whether a second rotation succeeds and
    /// the node accepts a fresh inbound link afterwards.
    private boolean nodeRecoversFromAFailedRotation(AtomicReference<String> secondRotationFailure) {
        var high = startedNetwork(new TripwireTopology(HIGH, false, FAST_PING), MessageRouter.mutable(), networks);
        var low = startedNetwork(new TripwireTopology(LOW, false, FAST_PING), MessageRouter.mutable(), networks);
        var port = high.boundPort().or(-1);
        var rotation = high.rotateCertificate(serverSsl("rotated-server"), clientSsl("rotated-client"));
        var thief = takePortAsSoonAsItIsFree(port);

        rotation.await(AWAIT)
                .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("control: the first rotation was expected to lose the port race"));
        assertThat(high.boundPort().isEmpty()).as("control: after the failed bind the node has no bound port").isTrue();
        thief.close();
        high.rotateCertificate(serverSsl("rotated-again-server"), clientSsl("rotated-again-client"))
            .await(AWAIT)
            .onFailure(cause -> secondRotationFailure.set(cause.message()));
        if (secondRotationFailure.get() != null) {
            return false;
        }
        low.dialForTests(infoOf(HIGH, high), false);

        return awaitConnected(low, high);
    }

    private static boolean awaitConnected(QuicClusterNetwork low, QuicClusterNetwork high) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (System.nanoTime() < deadline) {
            if (connected(low, HIGH) && connected(high, LOW)) {
                return true;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }

        return false;
    }

    /// Spins until the cluster port can be bound exclusively (the old server has closed it), then holds it.
    private static DatagramSocket takePortAsSoonAsItIsFree(int port) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);

        while (System.nanoTime() < deadline) {
            try {
                var socket = new DatagramSocket(null);

                socket.setReuseAddress(false);
                socket.bind(new InetSocketAddress(port));

                return socket;
            } catch (java.io.IOException stillHeldByTheOldServer) {
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
        }

        return org.junit.jupiter.api.Assertions.fail("the old server never released port " + port);
    }

    @Test
    void rotateCertificate_afterAFailedRestart_failsForeverWithServerNotRunning() {
        var failure = new AtomicReference<String>();
        var recovered = nodeRecoversFromAFailedRotation(failure);

        assertThat(recovered).as("TRIPWIRE D-02: the node did not recover").isFalse();
        assertThat(failure.get()).as("TRIPWIRE D-02: a later rotation, with the port free again, fails because boundPort() is "
                                     + "empty after the failed restart. The node now recovers. Delete this tripwire and "
                                     + "enable rotateCertificate_afterAFailedRestart_recovers.")
                                 .contains("Server not running");
    }

    @Disabled("D-02: enable when a failed restart leaves a recoverable node (rollback to the old server, or a retry that rebinds). "
              + "Today red: every later rotation fails with 'Server not running' and the node accepts no inbound link.")
    @Test
    void rotateCertificate_afterAFailedRestart_recovers() {
        var failure = new AtomicReference<String>();

        assertThat(nodeRecoversFromAFailedRotation(failure)).as("node accepts inbound again; second rotation failed with: %s", failure.get())
                                                            .isTrue();
    }
}
