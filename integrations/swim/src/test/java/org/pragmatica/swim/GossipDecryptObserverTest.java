// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// See LICENSE in the repository root for full terms.
package org.pragmatica.swim;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

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
            assertThat(warning.subject()).isEqualTo("10.0.0.7");
            assertThat(warning.message()).contains("10.0.0.7").contains("20260914").contains("AETHER_GOSSIP_KEY");
        });
    }

    @Test
    void unknownKey_twoSenders_twoWarnings_andAPortChangeIsTheSameSender() throws Exception {
        observer.unknownKey(STRANDED, 1);
        observer.unknownKey(STRANDED_OTHER_PORT, 1);
        observer.unknownKey(OTHER, 1);

        awaitPublished(2);

        assertThat(published).extracting(OperatorWarning::subject).containsExactlyInAnyOrder("10.0.0.7", "10.0.0.8");
    }

    @Test
    void decrypted_afterAWarning_raisesTheRecoveryOnce_forTheSameSubject() throws Exception {
        observer.unknownKey(STRANDED, 1);
        observer.decrypted(STRANDED);
        observer.decrypted(STRANDED);

        awaitPublished(2);

        assertThat(published).extracting(OperatorWarning::code)
                             .containsExactly(OperatorWarningCode.GOSSIP_KEY_UNKNOWN_FROM_PEER, OperatorWarningCode.GOSSIP_KEY_PEER_DECRYPTED);
        assertThat(published).extracting(OperatorWarning::subject).containsOnly("10.0.0.7");
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

    /// Source addresses of UDP datagrams are spoofable; the set of reported senders is bounded rather than growing with the flood.
    @Test
    void unknownKey_flood_isBoundedInMemory_andEveryDistinctSenderStillGetsItsWarning() throws Exception {
        var distinct = GossipDecryptObserver.Reporting.MAX_REPORTED + 50;

        for (var i = 0; i < distinct; i++) {
            observer.unknownKey(new InetSocketAddress("10." + (i / 256) + "." + (i % 256) + ".1", 7000), 1);
        }

        awaitPublished(Math.min(distinct, OperatorWarningSink.HAND_OFF_CAPACITY));

        assertThat(published.size()).as("each distinct sender raised, none twice").isLessThanOrEqualTo(distinct);
        assertThat(published).extracting(OperatorWarning::subject).doesNotHaveDuplicates();
    }

    private void awaitPublished(int expected) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (published.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertThat(published.size()).as("published within the bound").isGreaterThanOrEqualTo(expected);
    }
}
