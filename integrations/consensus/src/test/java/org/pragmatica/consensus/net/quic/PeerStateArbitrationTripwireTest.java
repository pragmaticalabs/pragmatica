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

import io.netty.handler.codec.quic.QuicChannel;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// Tripwire pair for the link-arbitration finding A-02 / M-01 (cluster T assessment, rc4 `7c786db0d3`): two ends of one
/// pair decide independently, from local phase age and a local receipt clock, which of two live links survives.
/// [PeerState] is the real class here — no QUIC, no network — and each "end" is one instance.
///
/// The cell space is the one the CTO's model enumerated (`t/evidence/lane-m/model.py`): the link dialed by the lower
/// id (X) or by the higher id (Y) is the incumbent at each end, the second attach arrives `age` after it (0.1, 0.6,
/// 2, 5, 10 s), and the peer was or was not heard from just before. 4 x 5 x 2 per end, 400 pairs of ends. The cells
/// are not weighted by how often production presents them: 1b-link shows several are unreachable on a healthy LAN
/// (a handshake slower than 500 ms after the QUIC connect is needed), so the count is a measure of the rule, not a
/// frequency.
///
/// Convention (workspace rule): the ENABLED tests assert today's disagreement and go red the moment arbitration is
/// fixed; the real invariant sits `@Disabled` beside them.
class PeerStateArbitrationTripwireTest {
    private static final NodeId PEER = new NodeId("peer-1");
    private static final NodeId LOW = new NodeId("aaa");
    private static final NodeId HIGH = new NodeId("zzz");
    private static final long T0 = 1_000_000_000L;
    private static final long HEARD_LEAD_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final double[] AGES_SECONDS = {0.1, 0.6, 2, 5, 10};
    private static final boolean[] HEARD = {false, true};
    private static final Link[] LINKS = Link.values();

    /// X = the physical link dialed by the lower id, Y = the one dialed by the higher id.
    enum Link {
        X(LOW),
        Y(HIGH);

        private final NodeId initiator;

        Link(NodeId initiator) {
            this.initiator = initiator;
        }

        Link other() {
            return this == X
                   ? Y
                   : X;
        }
    }

    record End(Link incumbent, double ageSeconds, boolean heard) {}

    record Cell(End first, End second) {
        boolean disagrees() {
            return survivor(first) != survivor(second);
        }

        @Override
        public String toString() {
            return first + " | " + second;
        }
    }

    /// The pinned cell of `PeerStateTest.attach_flippedArrivalOrderBothEndsSilent_bothEndsKeepTheLowerIdLink`
    /// is one of the AGREEING cells; the disagreeing ones are what no test pinned.
    @Test
    void attachArbitration_endsDisagreeOnTheSurvivingLink_inTodaysRule() {
        var all = cells(false);
        var disagreeing = all.stream().filter(Cell::disagrees).toList();

        assertThat(all).hasSize(400);
        assertThat(disagreeing).as("TRIPWIRE A-02/M-01: today's supersede rule leaves the two ends on DIFFERENT links in "
                                   + "168 of 400 model cells; arbitration has changed. Delete this tripwire and enable "
                                   + "attachArbitration_bothEndsKeepTheSameLink_inEveryCell and "
                                   + "attachArbitration_bothEndsKeepTheSameLink_whenBothEndsSeeEqualDelay.")
                               .hasSize(168);
    }

    @Test
    void attachArbitration_endsDisagreeOnTheSurvivingLink_whenBothEndsSeeEqualDelay() {
        var equalDelay = cells(true);
        var disagreeing = equalDelay.stream().filter(Cell::disagrees).toList();

        assertThat(equalDelay).hasSize(80);
        assertThat(disagreeing).as("TRIPWIRE A-02/M-01: 28 of 80 symmetric-network cells (same delay at both ends) still "
                                   + "disagree; arbitration has changed. Delete this tripwire and enable "
                                   + "attachArbitration_bothEndsKeepTheSameLink_whenBothEndsSeeEqualDelay.")
                                  .hasSize(28);
    }

    /// A witness that needs no model: both ends silent (no receipt at either), the same incumbent link, and the
    /// second link landing 2 s later at one end and 5 s later at the other. One end keeps it, the other swaps.
    @Test
    void attachArbitration_endsDisagree_whenBothEndsAreSilentAndTheSecondLinkLandsAtDifferentAges() {
        var atEnd1 = survivor(new End(Link.X, 2, false));
        var atEnd2 = survivor(new End(Link.X, 5, false));

        assertThat(atEnd1).as("TRIPWIRE A-02/M-01 witness: 2 s after the incumbent, silent: lower-id link kept")
                          .isEqualTo(Link.X);
        assertThat(atEnd2).as("TRIPWIRE A-02/M-01 witness: 5 s after the incumbent, silent: swapped to the other link; "
                              + "arbitration has changed, delete this tripwire and enable the real pair")
                          .isEqualTo(Link.Y);
    }

    @Disabled("A-02/M-01: enable when PeerState.attach arbitration is a function both ends share (connection generation in Hello)."
              + " Today red: 168 of 400 cells leave the two ends on different links.")
    @Test
    void attachArbitration_bothEndsKeepTheSameLink_inEveryCell() {
        var disagreeing = cells(false).stream().filter(Cell::disagrees).toList();

        assertThat(disagreeing).as("cells where the two ends keep different links").isEmpty();
    }

    @Disabled("A-02/M-01: enable with the arbitration overhaul. Today red: 28 of 80 equal-delay cells disagree.")
    @Test
    void attachArbitration_bothEndsKeepTheSameLink_whenBothEndsSeeEqualDelay() {
        var disagreeing = cells(true).stream().filter(Cell::disagrees).toList();

        assertThat(disagreeing).as("equal-delay cells where the two ends keep different links").isEmpty();
    }

    private static List<Cell> cells(boolean equalDelayOnly) {
        var result = new ArrayList<Cell>();

        for (var firstLink : LINKS) {
            for (var secondLink : LINKS) {
                addCellsForLinks(result, firstLink, secondLink, equalDelayOnly);
            }
        }

        return result;
    }

    private static void addCellsForLinks(List<Cell> into, Link firstLink, Link secondLink, boolean equalDelayOnly) {
        for (var age1 : AGES_SECONDS) {
            for (var age2 : AGES_SECONDS) {
                if (!equalDelayOnly || age1 == age2) {
                    addCellsForHeard(into, firstLink, age1, secondLink, age2);
                }
            }
        }
    }

    private static void addCellsForHeard(List<Cell> into, Link firstLink, double age1, Link secondLink, double age2) {
        for (var heard1 : HEARD) {
            for (var heard2 : HEARD) {
                into.add(new Cell(new End(firstLink, age1, heard1), new End(secondLink, age2, heard2)));
            }
        }
    }

    /// One end: `incumbent` attached first, the other link attaches `ageSeconds` later; returns the link still bound.
    private static Link survivor(End end) {
        var state = PeerState.peerState(PEER, T0);
        var incumbent = connectionOf(end.incumbent());
        var challenger = connectionOf(end.incumbent().other());
        var arrival = T0 + 2 + TimeUnit.MILLISECONDS.toNanos(Math.round(end.ageSeconds() * 1000));

        state.beginConnecting(T0 + 1);
        state.attach(incumbent, T0 + 2);
        if (end.heard()) {
            state.markInbound(arrival - HEARD_LEAD_NANOS);
        }
        state.attach(challenger, arrival);

        return state.activeConnection().or((QuicPeerConnection) null) == incumbent
               ? end.incumbent()
               : end.incumbent().other();
    }

    private static QuicPeerConnection connectionOf(Link link) {
        var channel = mock(QuicChannel.class);

        when(channel.isActive()).thenReturn(true);

        return QuicPeerConnection.quicPeerConnection(PEER, link.initiator, channel);
    }
}
