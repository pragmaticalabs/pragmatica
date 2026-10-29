// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// See LICENSE in the repository root for full terms.
package org.pragmatica.swim;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;

/// #1200 — the seed-side operator event for a peer that gossips under a key this node does not hold, and its recovery.
class GossipDecryptObserverTest {
    private static final InetSocketAddress STRANDED = new InetSocketAddress("10.0.0.7", 7000);
    private static final InetSocketAddress STRANDED_OTHER_PORT = new InetSocketAddress("10.0.0.7", 7999);
    private static final InetSocketAddress OTHER = new InetSocketAddress("10.0.0.8", 7000);

    private final List<OperatorWarning> published = new CopyOnWriteArrayList<>();
    private final OperatorWarningSink sink = OperatorWarningSink.handingOffTo(published::add);
    private final GossipDecryptObserver observer = GossipDecryptObserver.reporting(() -> sink);

    @Test
    void unknownKey_raisesTheWarningOncePerSender_naming_theSenderNotTheKey() throws Exception {
        for (var i = 0; i < 20; i++) {
            observer.unknownKey(STRANDED, 20260914);
        }

        awaitPublished(1);

        assertThat(published).singleElement().satisfies(warning -> {
            assertThat(warning.code()).isEqualTo(OperatorWarningCode.GOSSIP_KEY_UNKNOWN_FROM_PEER);
            assertThat(warning.subject()).isEqualTo("10.0.0.7:7000");
            assertThat(warning.code().code()).as("wire string, as operators match on it").isEqualTo("gossip-key-unknown-from-peer");
            assertThat(warning.code().level()).isEqualTo(org.pragmatica.utility.warning.WarningLevel.WARNING);
            assertThat(warning.message()).contains("10.0.0.7:7000")
                                         .contains("20260914")
                                         .contains("gossip-key-peer-decrypted")
                                         .contains("peer list")
                                         .doesNotContain("AETHER_GOSSIP_KEY")
                                         .doesNotContain("cannot join")
                                         .doesNotContain("leader provision");
        });
    }

    @Test
    void unknownKey_threeSenders_threeWarnings_twoNodesOnOneHostAreTwoSenders() throws Exception {
        observer.unknownKey(STRANDED, 1);
        observer.unknownKey(STRANDED_OTHER_PORT, 1);
        observer.unknownKey(OTHER, 1);

        awaitPublished(3);

        assertThat(published).extracting(OperatorWarning::subject).containsExactlyInAnyOrder("10.0.0.7:7000", "10.0.0.7:7999", "10.0.0.8:7000");
    }

    /// Two nodes on one host (a dev machine, Ember): a decryptable datagram from one must not close the warning raised for the other.
    @Test
    void decrypted_fromAnotherPortOfTheSameHost_doesNotCloseTheOtherNodesWarning() throws Exception {
        observer.unknownKey(STRANDED, 1);
        observer.decrypted(STRANDED_OTHER_PORT);
        observer.decrypted(STRANDED);

        awaitPublished(2);
        Thread.sleep(200);

        assertThat(published).extracting(OperatorWarning::subject).containsExactly("10.0.0.7:7000", "10.0.0.7:7000");
        assertThat(published).extracting(OperatorWarning::code)
                             .containsExactly(OperatorWarningCode.GOSSIP_KEY_UNKNOWN_FROM_PEER, OperatorWarningCode.GOSSIP_KEY_PEER_DECRYPTED);
    }

    @Test
    void decrypted_afterAWarning_raisesTheRecoveryOnce_forTheSameSubject() throws Exception {
        observer.unknownKey(STRANDED, 1);
        observer.decrypted(STRANDED);
        observer.decrypted(STRANDED);

        awaitPublished(2);

        assertThat(published).extracting(OperatorWarning::code)
                             .containsExactly(OperatorWarningCode.GOSSIP_KEY_UNKNOWN_FROM_PEER, OperatorWarningCode.GOSSIP_KEY_PEER_DECRYPTED);
        assertThat(published).extracting(OperatorWarning::subject).containsOnly("10.0.0.7:7000");
        assertThat(published.getLast().code().code()).isEqualTo("gossip-key-peer-decrypted");
        assertThat(published.getLast().code().level()).isEqualTo(org.pragmatica.utility.warning.WarningLevel.INFO);
        assertThat(OperatorWarningCode.GOSSIP_KEY_PEER_DECRYPTED.recoveryOf().or(OperatorWarningCode.JSON_CONFIG_APPLIED))
            .as("the recovery is declared as the pair of the warning, which is what lets the event layer close it")
            .isEqualTo(OperatorWarningCode.GOSSIP_KEY_UNKNOWN_FROM_PEER);
    }

    /// A healthy peer's every datagram reports `decrypted`; it must publish nothing unless that sender was warned about.
    @Test
    void decrypted_withoutAWarning_publishesNothing() throws Exception {
        for (var i = 0; i < 20; i++) {
            observer.decrypted(OTHER);
        }
        // control: the sink works (a warning raised afterwards is published), so an empty list above is a real absence
        observer.unknownKey(STRANDED, 1);
        awaitPublished(1);

        assertThat(published).singleElement().extracting(OperatorWarning::code).isEqualTo(OperatorWarningCode.GOSSIP_KEY_UNKNOWN_FROM_PEER);
    }

    /// A sender is warned about again after it recovered and diverged again.
    @Test
    void unknownKey_afterRecovery_raisesAgain() throws Exception {
        observer.unknownKey(STRANDED, 1);
        observer.decrypted(STRANDED);
        observer.unknownKey(STRANDED, 2);

        awaitPublished(3);

        assertThat(published).extracting(OperatorWarning::code)
                             .containsExactly(OperatorWarningCode.GOSSIP_KEY_UNKNOWN_FROM_PEER,
                                              OperatorWarningCode.GOSSIP_KEY_PEER_DECRYPTED,
                                              OperatorWarningCode.GOSSIP_KEY_UNKNOWN_FROM_PEER);
    }

    /// An unknown key id needs no key and a UDP source address is spoofable, so 10,000 distinct senders cost the cluster no more than the window
    /// budget of events, however many arrive, plus ONE summary naming how many were suppressed; and the observer's memory stays within its caps.
    @Test
    void unknownKey_tenThousandSpoofedSenders_raiseOnlyTheWindowBudget_thenOneSummary() throws Exception {
        var clock = new AtomicLong(1_000_000L);
        var flooded = GossipDecryptObserver.reporting(() -> sink, clock::get);

        for (var i = 0; i < 10_000; i++) {
            flooded.unknownKey(spoofed(i), 1);
        }

        awaitPublished(GossipDecryptObserver.Reporting.MAX_NEW_SENDERS_PER_WINDOW);
        Thread.sleep(300);

        assertThat(published).as("the window budget, not 10,000")
                             .hasSize(GossipDecryptObserver.Reporting.MAX_NEW_SENDERS_PER_WINDOW)
                             .extracting(OperatorWarning::code)
                             .containsOnly(OperatorWarningCode.GOSSIP_KEY_UNKNOWN_FROM_PEER);
        assertThat(((GossipDecryptObserver.Reporting) flooded).reportedCount()).as("remembered senders stay within the cap")
                                                                                .isLessThanOrEqualTo(GossipDecryptObserver.Reporting.MAX_REPORTED);

        clock.addAndGet(GossipDecryptObserver.Reporting.WINDOW_MILLIS + 1);
        flooded.unknownKey(spoofed(20_000), 1);
        awaitPublished(GossipDecryptObserver.Reporting.MAX_NEW_SENDERS_PER_WINDOW + 2);

        var summaries = published.stream()
                                 .filter(warning -> warning.code() == OperatorWarningCode.GOSSIP_KEY_UNKNOWN_SENDERS_SUPPRESSED)
                                 .toList();

        assertThat(summaries).as("exactly one aggregate event").hasSize(1);
        assertThat(summaries.getFirst().subject()).isEqualTo(GossipDecryptObserver.Reporting.SUPPRESSED_SUBJECT);
        assertThat(summaries.getFirst().code().code()).isEqualTo("gossip-key-unknown-senders-suppressed");
        assertThat(summaries.getFirst().code().level()).isEqualTo(org.pragmatica.utility.warning.WarningLevel.INFO);
        assertThat(summaries.getFirst().message()).as("a lower bound, since the tracking set is capped").startsWith("At least " + GossipDecryptObserver.Reporting.MAX_SUPPRESSED_TRACKED);
        assertThat(published).as("the sender that opened the new window is reported in it")
                             .extracting(OperatorWarning::subject)
                             .contains(subjectOf(spoofed(20_000)));
    }

    @Test
    void unknownKey_aWindowWithinBudget_raisesNoSummary() throws Exception {
        var clock = new AtomicLong(1_000_000L);
        var quiet = GossipDecryptObserver.reporting(() -> sink, clock::get);

        for (var i = 0; i < 5; i++) {
            quiet.unknownKey(spoofed(i), 1);
        }

        clock.addAndGet(GossipDecryptObserver.Reporting.WINDOW_MILLIS + 1);
        quiet.unknownKey(spoofed(100), 1);
        awaitPublished(6);
        Thread.sleep(300);

        assertThat(published).hasSize(6).extracting(OperatorWarning::code).containsOnly(OperatorWarningCode.GOSSIP_KEY_UNKNOWN_FROM_PEER);
    }

    /// The reported-sender set evicts its eldest beyond [Reporting#MAX_REPORTED]; an evicted sender that keeps sending is reported again.
    @Test
    void unknownKey_moreSendersThanTheSetHolds_evictsTheEldest_whichIsThenReportedAgain() throws Exception {
        var clock = new AtomicLong(1_000_000L);
        var bounded = GossipDecryptObserver.reporting(() -> sink, clock::get);
        var windows = GossipDecryptObserver.Reporting.MAX_REPORTED / GossipDecryptObserver.Reporting.MAX_NEW_SENDERS_PER_WINDOW + 4;
        var next = 0;

        for (var window = 0; window < windows; window++) {
            for (var i = 0; i < GossipDecryptObserver.Reporting.MAX_NEW_SENDERS_PER_WINDOW; i++) {
                bounded.unknownKey(spoofed(next++), 1);
            }

            clock.addAndGet(GossipDecryptObserver.Reporting.WINDOW_MILLIS + 1);
        }

        assertThat(((GossipDecryptObserver.Reporting) bounded).reportedCount()).as("the set holds exactly its cap").isEqualTo(GossipDecryptObserver.Reporting.MAX_REPORTED);

        var before = windows * GossipDecryptObserver.Reporting.MAX_NEW_SENDERS_PER_WINDOW;

        awaitPublished(Math.min(before, OperatorWarningSink.HAND_OFF_CAPACITY));
        Thread.sleep(300);
        var raised = published.size();

        bounded.unknownKey(spoofed(0), 1);
        awaitPublished(raised + 1);

        assertThat(published.getLast().subject()).as("the evicted eldest sender is reported again").isEqualTo(subjectOf(spoofed(0)));
    }

    private static String subjectOf(InetSocketAddress address) {
        return address.getAddress().getHostAddress() + ":" + address.getPort();
    }

    private static InetSocketAddress spoofed(int i) {
        try {
            return new InetSocketAddress(java.net.InetAddress.getByAddress(new byte[]{10, (byte) (i >> 16), (byte) (i >> 8), (byte) i}), 7000);
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private void awaitPublished(int expected) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (published.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertThat(published.size()).as("published within the bound").isGreaterThanOrEqualTo(expected);
    }
}
