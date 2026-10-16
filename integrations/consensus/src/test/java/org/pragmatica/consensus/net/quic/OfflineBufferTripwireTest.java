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
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.net.quic.TransportTripwireFixtures.TripwireTopology;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.messaging.StreamType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.addressOf;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.startedNetwork;

/// Tripwire pairs for C-03 (cluster T assessment, rc4 `7c786db0d3`): the offline buffer of a peer that is not
/// connected but is still a listed member is bounded by frame COUNT only (10,000), and a frame's expiry is checked
/// only when a drain runs at attach, so nothing purges it while the peer stays away. 1b-perf-ops corrected the
/// magnitude (the lane's 640 MB assumed 64 KiB frames; content chunks default to 4 MiB) and left the mechanism
/// CONFIRMED.
///
/// Two cells: time (the expiry never fires without an attach) and bytes (no byte accounting exists). The byte cell
/// uses a nominal 1 MiB payload per frame — one shared `String` instance, so the test holds the count, not the
/// memory. `[guess]` for the real byte assertion: the overhaul chooses the cap; any cap at or below the 256 MiB
/// used here satisfies it.
@Timeout(60)
class OfflineBufferTripwireTest {
    private static final NodeId SELF = new NodeId("zzz-self");
    private static final NodeId MEMBER = new NodeId("aaa-member");
    private static final int NOMINAL_FRAME_BYTES = 1 << 20;
    private static final int FRAMES_OFFERED = 300;
    private static final long BYTE_CAP_GUESS = 256L << 20;
    private static final int TIME_FRAMES = 50;
    private static final TimeSpan CAP = TimeSpan.timeSpan(100).millis();

    private final List<QuicClusterNetwork> networks = new ArrayList<>();

    @AfterEach
    void tearDown() {
        networks.forEach(network -> network.stop().await(TransportTripwireFixtures.AWAIT));
        networks.clear();
    }

    /// A higher-id node whose lower-id member is listed but never connects: the higher-id side waits for inbound,
    /// so the peer stays in INIT and every unicast to it is buffered.
    private QuicClusterNetwork nodeWithAnAbsentMember() {
        var topology = new TripwireTopology(SELF, false, TimeSpan.timeSpan(1).seconds());

        topology.addPeer(NodeInfo.nodeInfo(MEMBER, addressOf("127.0.0.1", 1)));

        return startedNetwork(topology, MessageRouter.mutable(), networks);
    }

    private static void offer(QuicClusterNetwork network, int frames, String marker) {
        for (var i = 0; i < frames; i++) {
            network.send(MEMBER, LaneProbe.laneProbe(SELF, StreamType.CONSENSUS, marker));
        }
    }

    private int bufferedAfterTheCapElapsed() {
        var network = nodeWithAnAbsentMember();

        network.setOfflineBufferCap(CAP);
        offer(network, TIME_FRAMES, "held");
        assertThat(network.offlineBufferSizeForTests(MEMBER)).as("control: the frames are buffered").isEqualTo(TIME_FRAMES);
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(CAP.millis() * 5));
        network.reconcileMissingPeersTick();
        network.reconcileMissingPeersTick();

        return network.offlineBufferSizeForTests(MEMBER);
    }

    private long nominalBytesHeldAfterOffering() {
        var network = nodeWithAnAbsentMember();

        offer(network, FRAMES_OFFERED, "x".repeat(NOMINAL_FRAME_BYTES));

        return (long) network.offlineBufferSizeForTests(MEMBER) * NOMINAL_FRAME_BYTES;
    }

    @Test
    void offlineBuffer_keepsFramesPastTheirExpiry_untilAnAttachDrainsThem() {
        assertThat(bufferedAfterTheCapElapsed())
            .as("TRIPWIRE C-03: 5 caps and 2 reconcile ticks after buffering, all 50 expired frames are still held; "
                + "expiry is evaluated only at drain. The buffer is now purged by time. Delete this tripwire and "
                + "enable offlineBuffer_dropsExpiredFrames_withoutAnAttach.")
            .isEqualTo(TIME_FRAMES);
    }

    @Disabled("C-03: enable when expired frames are purged without an attach (purge-on-tick). "
              + "Today red: 50 expired frames remain buffered for an absent member.")
    @Test
    void offlineBuffer_dropsExpiredFrames_withoutAnAttach() {
        assertThat(bufferedAfterTheCapElapsed()).as("frames still held after the cap elapsed").isZero();
    }

    @Test
    void offlineBuffer_holdsThreeHundredMebibytesNominal_forOneAbsentMember() {
        assertThat(nominalBytesHeldAfterOffering())
            .as("TRIPWIRE C-03: 300 frames of 1 MiB nominal are all held for one absent member; the buffer has no byte "
                + "bound. A byte cap exists now. Delete this tripwire and enable "
                + "offlineBuffer_boundsNominalBytes_forOneAbsentMember.")
            .isEqualTo((long) FRAMES_OFFERED * NOMINAL_FRAME_BYTES);
    }

    @Disabled("C-03: enable when the offline buffer has a byte bound. [guess] the cap is at most 256 MiB nominal. "
              + "Today red: 300 MiB nominal held (count bound 10,000 frames is the only bound).")
    @Test
    void offlineBuffer_boundsNominalBytes_forOneAbsentMember() {
        assertThat(nominalBytesHeldAfterOffering()).as("nominal bytes held for one absent member")
                                                   .isLessThanOrEqualTo(BYTE_CAP_GUESS);
    }
}
