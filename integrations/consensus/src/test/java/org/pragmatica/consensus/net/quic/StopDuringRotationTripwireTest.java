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
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.AWAIT;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.clientSsl;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.serverSsl;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.startedNetwork;

/// Tripwire pair for the inventory row U-021 / A-10.2 (cluster T assessment, rc4 `7c786db0d3`): `stopAndRestartServer`
/// and `rebuildAndStart` never read `closed` / `isRunning`, so a rotation that is in flight when `stop()` completes assigns
/// a fresh server and client and binds the port: a stopped transport comes back to life. A rotation redesigned to avoid the
/// server restart must keep `stop()` terminal.
///
/// The rotation is held in flight by the old server's graceful event-loop shutdown (seconds), which `stop()` does not wait for.
@Timeout(60)
class StopDuringRotationTripwireTest {
    private final List<QuicClusterNetwork> networks = new ArrayList<>();

    @AfterEach
    void tearDown() throws ReflectiveOperationException {
        for (var network : networks) {
            network.stop().await(AWAIT);
            stopWhateverServerAndClientAreStillAssigned(network);
        }
        networks.clear();
    }

    /// `stop()` is idempotent and returns at its CAS once `isRunning` is false, so a transport resurrected by a late rotation
    /// can only be released by the step `stop()` normally ends with.
    @SuppressWarnings("unchecked")
    private static void stopWhateverServerAndClientAreStillAssigned(QuicClusterNetwork network) throws ReflectiveOperationException {
        Method stopServerAndClient = QuicClusterNetwork.class.getDeclaredMethod("stopServerAndClient", Unit.class);

        stopServerAndClient.setAccessible(true);
        ((Promise<Unit>) stopServerAndClient.invoke(network, Unit.unit())).await(AWAIT);
    }

    /// Whether the stopped node has a bound server port once the rotation that was in flight at `stop()` has finished.
    private boolean stoppedNodeIsBoundAfterALateRotation() {
        var network = startedNetwork(new TripwireTopology(new NodeId("rot-stop"), false, TimeSpan.timeSpan(30).seconds()),
                                     MessageRouter.mutable(),
                                     networks);
        var rotation = network.rotateCertificate(serverSsl("rotated-server"), clientSsl("rotated-client"));

        network.stop().await(AWAIT).onFailure(cause -> fail("control: stop must complete: " + cause.message()));
        rotation.await(AWAIT).onFailure(cause -> fail("control: the in-flight rotation is expected to run to completion: " + cause.message()));

        return network.boundPort().isPresent();
    }

    @Test
    void rotationInFlightAtStop_bindsThePortOfAStoppedTransport() {
        assertThat(stoppedNodeIsBoundAfterALateRotation())
            .as("TRIPWIRE U-021/A-10.2: after stop() completed, the rotation that was in flight rebuilt the server and bound the "
                + "port. stop() is now terminal. Delete this tripwire and enable "
                + "rotationInFlightAtStop_leavesTheStoppedTransportUnbound.")
            .isTrue();
    }

    @Disabled("U-021/A-10.2: enable when stop() is terminal against an in-flight rotation. Today red: the stopped transport is rebound.")
    @Test
    void rotationInFlightAtStop_leavesTheStoppedTransportUnbound() {
        assertThat(stoppedNodeIsBoundAfterALateRotation()).as("a stopped transport holds a bound port").isFalse();
    }
}
