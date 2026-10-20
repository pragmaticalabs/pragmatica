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
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// #2138 / A-02 / M-01 (cluster T assessment, rc4 `7c786db0d3`): two ends of one pair must keep the SAME link when
/// two live links reach them. Before #2138 each end decided independently, from its own phase age and receipt clock,
/// and the two disagreed in 168 of 400 model cells (28 of 80 with equal delay at both ends); the tripwires that
/// pinned that disagreement are deleted with the change. [PeerState] is the real class here — no QUIC, no network —
/// and each "end" is one instance.
///
/// The cell space is the one the assessment's model enumerated: the link dialed by the lower id (X) or by the higher
/// id (Y) is the incumbent at each end, the second attach arrives `age` after it (0.1, 0.6, 2, 5, 10 s), and the
/// peer was or was not heard from just before. 4 x 5 x 2 per end, 400 pairs of ends, repeated for each assignment
/// of link generations (the two dialers chose equal generations, or either one chose the greater). The cells are
/// not weighted by how often production presents them: the second review shows several are unreachable on a healthy
/// LAN (a handshake slower than 500 ms after the QUIC connect is needed), so the count is a measure of the rule,
/// not a frequency.
///
/// Every link carries a rank here, which is what a dial between two current nodes produces. A pair where either
/// link has no rank keeps the legacy rule and its window of disagreement (see `PeerStateTest`).
class PeerStateArbitrationAgreementTest {
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

    /// The generations the two dialers chose for their links: X (lower id) and Y (higher id).
    record Generations(long x, long y) {
        long of(Link link) {
            return link == Link.X
                   ? x
                   : y;
        }

        /// The link the rank order seats: the greater generation, equal generations to the lower dialer (X).
        Link winner() {
            return y > x
                   ? Link.Y
                   : Link.X;
        }
    }

    private static final List<Generations> GENERATIONS = List.of(new Generations(1, 1),
                                                                  new Generations(1, 2),
                                                                  new Generations(2, 1),
                                                                  new Generations(7, 8));

    record Cell(End first, End second, Generations generations) {
        boolean disagrees() {
            return survivor(first, generations) != survivor(second, generations);
        }

        boolean seatsAnotherLinkThanTheRankOrder() {
            return survivor(first, generations) != generations.winner() || survivor(second, generations) != generations.winner();
        }

        @Override
        public String toString() {
            return first + " | " + second + " | " + generations;
        }
    }

    @Test
    void attachArbitration_bothEndsKeepTheSameLink_inEveryCell() {
        var all = cells(false);
        var disagreeing = all.stream().filter(Cell::disagrees).toList();

        assertThat(all).hasSize(400 * GENERATIONS.size());
        assertThat(disagreeing).as("cells where the two ends keep different links").isEmpty();
    }

    @Test
    void attachArbitration_bothEndsKeepTheSameLink_whenBothEndsSeeEqualDelay() {
        var equalDelay = cells(true);
        var disagreeing = equalDelay.stream().filter(Cell::disagrees).toList();

        assertThat(equalDelay).hasSize(80 * GENERATIONS.size());
        assertThat(disagreeing).as("equal-delay cells where the two ends keep different links").isEmpty();
    }

    /// Agreement alone would also hold if both ends kept the wrong link; the link both keep is the one the rank
    /// order seats.
    @Test
    void attachArbitration_theLinkBothEndsKeep_isTheHighestRankedOne() {
        var wrong = cells(false).stream().filter(Cell::seatsAnotherLinkThanTheRankOrder).toList();

        assertThat(wrong).as("cells where an end keeps a link other than the highest-ranked one").isEmpty();
    }

    private static List<Cell> cells(boolean equalDelayOnly) {
        var result = new ArrayList<Cell>();

        for (var generations : GENERATIONS) {
            for (var firstLink : LINKS) {
                for (var secondLink : LINKS) {
                    addCellsForLinks(result, firstLink, secondLink, generations, equalDelayOnly);
                }
            }
        }

        return result;
    }

    private static void addCellsForLinks(List<Cell> into,
                                         Link firstLink,
                                         Link secondLink,
                                         Generations generations,
                                         boolean equalDelayOnly) {
        for (var age1 : AGES_SECONDS) {
            for (var age2 : AGES_SECONDS) {
                if (!equalDelayOnly || age1 == age2) {
                    addCellsForHeard(into, firstLink, age1, secondLink, age2, generations);
                }
            }
        }
    }

    private static void addCellsForHeard(List<Cell> into,
                                         Link firstLink,
                                         double age1,
                                         Link secondLink,
                                         double age2,
                                         Generations generations) {
        for (var heard1 : HEARD) {
            for (var heard2 : HEARD) {
                into.add(new Cell(new End(firstLink, age1, heard1), new End(secondLink, age2, heard2), generations));
            }
        }
    }

    /// One end: `incumbent` attached first, the other link attaches `ageSeconds` later; returns the link still bound.
    private static Link survivor(End end, Generations generations) {
        var state = PeerState.peerState(PEER, T0);
        var incumbent = connectionOf(end.incumbent(), generations);
        var challenger = connectionOf(end.incumbent().other(), generations);
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

    private static QuicPeerConnection connectionOf(Link link, Generations generations) {
        var channel = mock(QuicChannel.class);

        when(channel.isActive()).thenReturn(true);

        return QuicPeerConnection.quicPeerConnection(PEER, link.initiator, generations.of(link), channel);
    }
}
