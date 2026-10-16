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
import org.pragmatica.consensus.topology.TransportObservation.SelfShutdown;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.AWAIT;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.startedNetwork;

/// Tripwire pair for A-01 (cluster T assessment, rc4 `7c786db0d3`), transport half: `QuicClusterNetwork.stop()` calls
/// `processViewChange(SHUTDOWN, self.id())`, but `processViewChange` returns first for the self id, so the
/// `TransportObservation.SelfShutdown` the SHUTDOWN branch builds is never routed. 1b-link CONFIRMED, with a second,
/// independent kill in `AetherNode.stop()` (the router is quiesced before the network stops): the wiring half is
/// `TransportWiringTripwireEmberTest`.
///
/// Fork for the overhaul spec: delete `SelfShutdown` (1b-link: the real self-departure signal is
/// `ClusterStateNotification.passive()`, delivered before the quiesce) or re-source it from the stop path before
/// `router.quiesce()`. This pair covers the second option; if the spec deletes the type, delete the pair with it.
@Timeout(60)
class SelfShutdownTripwireTest {
    private static final NodeId SELF = new NodeId("selfshutdown-node");

    private final List<QuicClusterNetwork> networks = new ArrayList<>();

    @AfterEach
    void tearDown() {
        networks.forEach(network -> network.stop().await(AWAIT));
        networks.clear();
    }

    /// SelfShutdown observations routed while a started network stops. A plain mutable router: nothing quiesces it.
    private List<SelfShutdown> observedAcrossAGracefulStop() {
        var observed = new CopyOnWriteArrayList<SelfShutdown>();
        var router = MessageRouter.mutable();

        router.addRoute(SelfShutdown.class, observed::add);
        var network = startedNetwork(new TripwireTopology(SELF, false, TimeSpan.timeSpan(30).seconds()), router, networks);

        network.stop().await(AWAIT);

        return List.copyOf(observed);
    }

    @Test
    void stop_routesNoSelfShutdown_thoughTheShutdownBranchExists() {
        assertThat(observedAcrossAGracefulStop())
            .as("TRIPWIRE A-01: a graceful stop of the network routes no SelfShutdown (the self guard in processViewChange "
                + "returns first); a SelfShutdown is now emitted. Delete this tripwire and enable "
                + "stop_routesOneSelfShutdownForTheStoppingNode.")
            .isEmpty();
    }

    @Disabled("A-01: enable if the overhaul re-sources SelfShutdown from the stop path; delete the pair if the spec removes the type. "
              + "Today red: no SelfShutdown is routed on a graceful stop.")
    @Test
    void stop_routesOneSelfShutdownForTheStoppingNode() {
        var observed = observedAcrossAGracefulStop();

        assertThat(observed).hasSize(1);
        assertThat(observed.getFirst().nodeId()).isEqualTo(SELF);
    }
}
