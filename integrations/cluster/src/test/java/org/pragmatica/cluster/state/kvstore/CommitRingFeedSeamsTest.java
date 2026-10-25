// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.state.kvstore.CommitRing.CloseReason;
import org.pragmatica.cluster.state.kvstore.CommitRing.Granted;
import org.pragmatica.cluster.state.kvstore.CommitRing.QueuedEntry;
import org.pragmatica.cluster.state.kvstore.CommitRing.RefusalReason;
import org.pragmatica.cluster.state.kvstore.CommitRing.Refused;

import static org.assertj.core.api.Assertions.assertThat;

/// The two ring calls the replica feed server needs (W-OBS W2): a subscription at the frontier with no position (`LIVE_ONLY`) and the owner's
/// release of a subscription whose peer went away.
class CommitRingFeedSeamsTest {
    private static final IncarnationTag TAG = IncarnationTag.incarnationTag(1, "A");

    private static List<KVCommand<?>> effect(String key) {
        return List.of(new KVCommand.Put<>(new RingFixtures.Key(key), "v"));
    }

    private static CommitRing ring() {
        var ring = CommitRing.commitRing(CommitRing.DEFAULT_BUDGET_BYTES, ValueSizeTable.valueSizeTable()::estimate, Runnable::run);

        ring.invalidate(TAG, 0);

        return ring;
    }

    /// Mutation: have `subscribeLiveLocked` return `entriesFrom(0)` (replays the ring), or skip `subscriptions.add`.
    @Test
    void subscribeLive_registersAtTheFrontier_andDeliversOnlyLaterEntries() {
        var ring = ring();

        ring.append(TAG, 0, "id-0", effect("a"), 1);
        ring.append(TAG, 1, "id-1", effect("b"), 2);

        var subscription = CommitRing.subscription(_ -> {});

        assertThat(ring.subscriptionCount()).isZero();
        assertThat(ring.subscribeLive(subscription)).isInstanceOfSatisfying(Granted.class, granted -> {
            assertThat(granted.entries()).as("no catch-up").isEmpty();
            assertThat(granted.latest()).isEqualTo(2);
        });

        ring.append(TAG, 2, "id-2", effect("c"), 3);

        assertThat(ring.subscriptionCount()).isEqualTo(1);
        assertThat(subscription.poll().unwrap()).isInstanceOfSatisfying(QueuedEntry.class, queued -> assertThat(queued.entry().slot()).isEqualTo(2));
        assertThat(subscription.poll().isPresent()).as("nothing from before the call").isFalse();
    }

    /// Needs no position, so it is granted on a ring that never recorded an effect. Mutation: route it through `subscribeFrom` with the ring's
    /// own (empty) anchor, which refuses an unanchored position AHEAD.
    @Test
    void subscribeLive_isGranted_beforeAnyEffectWasRecorded() {
        var ring = ring();

        assertThat(ring.subscribeLive(CommitRing.subscription(_ -> {}))).isInstanceOf(Granted.class);
    }

    /// Mutation: drop the `tag.isNone()` refusal (a ring with no incarnation serves).
    @Test
    void subscribeLive_isRefusedNotServing_whileNoIncarnationIsCommitted() {
        var ring = CommitRing.commitRing(CommitRing.DEFAULT_BUDGET_BYTES, ValueSizeTable.valueSizeTable()::estimate, Runnable::run);

        assertThat(ring.subscribeLive(CommitRing.subscription(_ -> {}))).isInstanceOfSatisfying(Refused.class,
                                                                                                refused -> assertThat(refused.reason()).isEqualTo(RefusalReason.NOT_SERVING));
    }

    /// Mutation: `releaseLocked` leaves the subscription in the list (it is still offered to), or fires the close callback.
    @Test
    void unsubscribe_removesTheSubscription_marksItReleased_andFiresNoClose() {
        var ring = ring();
        var closes = new AtomicInteger();
        var subscription = CommitRing.subscription(_ -> closes.incrementAndGet());

        ring.subscribeLive(subscription);
        assertThat(ring.subscriptionCount()).isEqualTo(1);
        ring.unsubscribe(subscription);
        assertThat(ring.subscriptionCount()).as("the subscription left the ring").isZero();
        ring.append(TAG, 0, "id-0", effect("a"), 1);

        assertThat(subscription.isClosed()).isTrue();
        assertThat(subscription.closeReason()).isEqualTo(CloseReason.RELEASED);
        assertThat(subscription.poll().isPresent()).as("nothing is offered to a released subscription").isFalse();
        assertThat(closes.get()).as("a release by the owner is not a close").isZero();
    }
}
