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
import org.pragmatica.consensus.net.quic.PeerState.AttachResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// #2138 — a link carries a rank `(generation, dialer)` that both ends of a pair read identically, and an active
/// incumbent is arbitrated by that rank alone, whatever the ages and receipts at this end. Pure unit tests over the
/// real [PeerState] and [LinkRank]; no QUIC.
class PeerStateLinkRankTest {
    private static final NodeId PEER = new NodeId("peer-1");
    private static final NodeId LOW = new NodeId("aaa");
    private static final NodeId HIGH = new NodeId("zzz");
    private static final long T0 = 1_000_000_000L;
    private static final long LONG_AGO = TimeUnit.SECONDS.toNanos(60);

    private static PeerState state() {
        var state = PeerState.peerState(PEER, T0);

        state.beginConnecting(T0 + 1);

        return state;
    }

    private static QuicPeerConnection link(NodeId dialer, long generation) {
        var channel = mock(QuicChannel.class);

        when(channel.isActive()).thenReturn(true);

        return QuicPeerConnection.quicPeerConnection(PEER, dialer, generation, channel);
    }

    private static QuicPeerConnection unrankedLink(NodeId dialer) {
        var channel = mock(QuicChannel.class);

        when(channel.isActive()).thenReturn(true);

        return QuicPeerConnection.quicPeerConnection(PEER, dialer, channel);
    }

    private static QuicPeerConnection bound(PeerState state) {
        return state.activeConnection().or((QuicPeerConnection) null);
    }

    @Test
    void survivesOver_isATotalOrder_overRandomRanks() {
        var random = new Random(2138);
        var ids = List.of(LOW, HIGH, new NodeId("mmm"));

        for (int i = 0; i < 5_000; i++) {
            var a = new LinkRank(1 + random.nextInt(4), ids.get(random.nextInt(ids.size())));
            var b = new LinkRank(1 + random.nextInt(4), ids.get(random.nextInt(ids.size())));
            var c = new LinkRank(1 + random.nextInt(4), ids.get(random.nextInt(ids.size())));

            if (!a.equals(b)) {
                assertThat(a.survivesOver(b)).as("exactly one of %s, %s survives", a, b).isNotEqualTo(b.survivesOver(a));
            }
            if (a.survivesOver(b) && b.survivesOver(c)) {
                assertThat(a.survivesOver(c)).as("transitive %s > %s > %s", a, b, c).isTrue();
            }
        }
    }

    @Test
    void survivesOver_equalGenerations_goToTheLowerDialer() {
        assertThat(new LinkRank(5, LOW).survivesOver(new LinkRank(5, HIGH))).isTrue();
        assertThat(new LinkRank(5, HIGH).survivesOver(new LinkRank(5, LOW))).isFalse();
    }

    /// Two links of one dialer with one generation cannot occur (a dialer never reuses a generation); if a peer sends
    /// them anyway, neither outranks the other and the incumbent is kept.
    @Test
    void survivesOver_sameDialerSameGeneration_neitherSurvivesOverTheOther() {
        assertThat(new LinkRank(5, LOW).survivesOver(new LinkRank(5, LOW))).isFalse();
        assertThat(new LinkRank(5, HIGH).survivesOver(new LinkRank(5, HIGH))).isFalse();
    }

    @Test
    void attach_sameDialerSameGeneration_keepsTheIncumbent() {
        var s = state();
        var incumbent = link(LOW, 5);

        s.attach(incumbent, T0 + 2);

        assertThat(s.attach(link(LOW, 5), T0 + 2 + LONG_AGO).result()).isEqualTo(AttachResult.DUPLICATE);
        assertThat(bound(s)).isSameAs(incumbent);
    }

    @Test
    void survivesOver_greaterGeneration_beatsTheLowerDialer() {
        assertThat(new LinkRank(6, HIGH).survivesOver(new LinkRank(5, LOW))).isTrue();
        assertThat(new LinkRank(5, LOW).survivesOver(new LinkRank(6, HIGH))).isFalse();
    }

    @Test
    void linkRank_generationOutsideTheRange_hasNoRank() {
        assertThat(QuicPeerConnection.quicPeerConnection(PEER, LOW, 0L, mock(QuicChannel.class)).linkRank().isEmpty()).isTrue();
        assertThat(QuicPeerConnection.quicPeerConnection(PEER, LOW, -3L, mock(QuicChannel.class)).linkRank().isEmpty()).isTrue();
        assertThat(QuicPeerConnection.quicPeerConnection(PEER, LOW, LinkRank.MAX_GENERATION + 1, mock(QuicChannel.class))
                                     .linkRank()
                                     .isEmpty()).isTrue();
        assertThat(QuicPeerConnection.quicPeerConnection(PEER, LOW, Long.MAX_VALUE, mock(QuicChannel.class)).linkRank().isEmpty())
                .isTrue();
        assertThat(QuicPeerConnection.quicPeerConnection(PEER, LOW, 1L, mock(QuicChannel.class)).linkRank().isPresent()).isTrue();
        assertThat(QuicPeerConnection.quicPeerConnection(PEER, LOW, LinkRank.MAX_GENERATION, mock(QuicChannel.class))
                                     .linkRank()
                                     .isPresent()).isTrue();
    }

    @Test
    void linkRank_connectionWithoutADialer_hasNoRank() {
        assertThat(QuicPeerConnection.quicPeerConnection(PEER, mock(QuicChannel.class)).linkRank().isEmpty()).isTrue();
    }

    /// The M5 case: a lying-active incumbent (the dialer died, the link still reports active) against the redial
    /// of the other end. A higher generation supersedes it whatever its age and whatever was heard on it.
    @Test
    void attach_higherGeneration_supersedesAnOldHeardFromIncumbent() {
        var s = state();
        var incumbent = link(LOW, 1);
        var redial = link(HIGH, 2);

        s.attach(incumbent, T0 + 2);
        s.markInbound(T0 + LONG_AGO);

        var outcome = s.attach(redial, T0 + LONG_AGO + 1);

        assertThat(outcome.result()).isEqualTo(AttachResult.RECONNECTED);
        assertThat(outcome.superseded().or((QuicPeerConnection) null)).isSameAs(incumbent);
        assertThat(bound(s)).isSameAs(redial);
    }

    @Test
    void attach_higherGeneration_supersedesAnIncumbentOfTheSameDialerAtOnce() {
        var s = state();
        var incumbent = link(LOW, 1);
        var redial = link(LOW, 2);

        s.attach(incumbent, T0 + 2);

        assertThat(s.attach(redial, T0 + 3).result()).as("no 3 s age gate for ranked links").isEqualTo(AttachResult.RECONNECTED);
        assertThat(bound(s)).isSameAs(redial);
    }

    @Test
    void attach_lowerGeneration_neverSupersedes_whateverTheAge() {
        var s = state();
        var incumbent = link(HIGH, 3);

        s.attach(incumbent, T0 + 2);

        assertThat(s.attach(link(LOW, 2), T0 + 2 + LONG_AGO).result()).isEqualTo(AttachResult.DUPLICATE);
        assertThat(bound(s)).isSameAs(incumbent);
    }

    @Test
    void attach_equalGenerations_goToTheLowerDialer_whicheverArrivesFirst() {
        var lowFirst = state();
        var lowLink = link(LOW, 1);

        lowFirst.attach(lowLink, T0 + 2);

        assertThat(lowFirst.attach(link(HIGH, 1), T0 + 3).result()).isEqualTo(AttachResult.DUPLICATE);
        assertThat(bound(lowFirst)).isSameAs(lowLink);

        var highFirst = state();

        highFirst.attach(link(HIGH, 1), T0 + 2);

        var lowSecond = link(LOW, 1);

        assertThat(highFirst.attach(lowSecond, T0 + 3).result()).isEqualTo(AttachResult.RECONNECTED);
        assertThat(bound(highFirst)).isSameAs(lowSecond);
    }

    /// Mixed pair: either link without a rank is arbitrated by the legacy rule, unchanged. A ranked challenger that
    /// rank order would seat loses to a younger lower-id incumbent by the legacy window, and a ranked incumbent is
    /// replaced by an old-enough unranked challenger by the legacy age rule.
    @Test
    void attach_oneLinkUnranked_theLegacyRuleDecides() {
        var legacyKeeps = state();
        var unrankedLowIncumbent = unrankedLink(LOW);

        legacyKeeps.attach(unrankedLowIncumbent, T0 + 2);

        assertThat(legacyKeeps.attach(link(HIGH, 9), T0 + 3).result()).as("rank would seat generation 9").isEqualTo(AttachResult.DUPLICATE);
        assertThat(bound(legacyKeeps)).isSameAs(unrankedLowIncumbent);

        var legacySwaps = state();
        var rankedHighIncumbent = link(HIGH, 1);

        legacySwaps.attach(rankedHighIncumbent, T0 + 2);

        var unrankedLowChallenger = unrankedLink(LOW);

        assertThat(legacySwaps.attach(unrankedLowChallenger, T0 + 2 + LONG_AGO).result()).as("legacy: lower id beats an old silent higher-id link")
                                                                                            .isEqualTo(AttachResult.RECONNECTED);
        assertThat(bound(legacySwaps)).isSameAs(unrankedLowChallenger);
    }

    @Test
    void nextDialGeneration_isStrictlyGreaterThanEveryDialedAndAttachedGeneration() {
        var s = state();

        assertThat(s.nextDialGeneration()).isEqualTo(1L);
        assertThat(s.nextDialGeneration()).isEqualTo(2L);

        s.attach(link(HIGH, 40), T0 + 2);

        assertThat(s.nextDialGeneration()).isEqualTo(41L);
    }

    @Test
    void attach_aLinkThatLosesStillRaisesTheHighestGeneration() {
        var s = state();

        s.attach(link(LOW, 50), T0 + 2);

        assertThat(s.attach(link(HIGH, 20), T0 + 3).result()).isEqualTo(AttachResult.DUPLICATE);
        assertThat(s.nextDialGeneration()).isEqualTo(51L);
    }

    @Test
    void nextDialGeneration_concurrentCallers_getDistinctValues() throws Exception {
        var s = state();
        var threads = 8;
        var perThread = 500;
        var values = java.util.Collections.synchronizedList(new ArrayList<Long>());
        var go = new AtomicBoolean();
        var workers = IntStream.range(0, threads)
                               .mapToObj(_ -> new Thread(() -> collect(s, perThread, go, values)))
                               .toList();

        workers.forEach(Thread::start);
        go.set(true);
        for (var worker : workers) {
            worker.join();
        }

        assertThat(values).hasSize(threads * perThread).doesNotHaveDuplicates();
        assertThat(values).containsAll(IntStream.rangeClosed(1, threads * perThread).mapToObj(Long::valueOf).toList());
    }

    private static void collect(PeerState state, int count, AtomicBoolean go, List<Long> into) {
        while (!go.get()) {
            Thread.onSpinWait();
        }
        for (int i = 0; i < count; i++) {
            into.add(state.nextDialGeneration());
        }
    }

    @Test
    void nextDialGeneration_claimedGenerationAtTheCeiling_returnsNoGenerationAndNeverOverflows() {
        var s = state();

        s.attach(link(HIGH, LinkRank.MAX_GENERATION), T0 + 2);

        assertThat(s.nextDialGeneration()).as("a dial past the ceiling carries no rank").isZero();
        assertThat(s.nextDialGeneration()).isZero();
    }

    @Test
    void attach_aClaimedGenerationAboveTheCeiling_doesNotRaiseTheCounter() {
        var s = state();

        s.attach(link(HIGH, Long.MAX_VALUE), T0 + 2);

        assertThat(s.nextDialGeneration()).isEqualTo(1L);
    }
}
