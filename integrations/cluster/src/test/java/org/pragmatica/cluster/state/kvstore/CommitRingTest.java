// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.state.kvstore.CommitRing.CloseReason;
import org.pragmatica.cluster.state.kvstore.CommitRing.Granted;
import org.pragmatica.cluster.state.kvstore.CommitRing.Message;
import org.pragmatica.cluster.state.kvstore.CommitRing.QueuedEntry;
import org.pragmatica.cluster.state.kvstore.CommitRing.QueuedHeartbeat;
import org.pragmatica.cluster.state.kvstore.CommitRing.RefusalReason;
import org.pragmatica.cluster.state.kvstore.CommitRing.Refused;
import org.pragmatica.cluster.state.kvstore.CommitRing.Subscribe;

import static org.assertj.core.api.Assertions.assertThat;

/// The commit ring on its own (no store): position bookkeeping, the byte bound, the resubscribe verdict, the sink rules. The store's use of it
/// is `KVStoreReplicaRingTest`; the lock order is `CommitRingLockOrderTest`.
///
/// Naming: NT-O.. identifiers are the design's test plan; each test names the invariant it pins and the mutation that turns it red.
class CommitRingTest {
    private static final IncarnationTag TAG = IncarnationTag.incarnationTag(1, "A");

    private static List<KVCommand<?>> effect(String key, String value) {
        return List.of(new KVCommand.Put<>(new RingFixtures.Key(key), value));
    }

    /// A ring whose subscriber closes run on the calling thread after the lock is released, so a test can assert them right after the call.
    private static CommitRing ring() {
        return ring(CommitRing.DEFAULT_BUDGET_BYTES);
    }

    private static CommitRing ring(long budgetBytes) {
        var ring = CommitRing.commitRing(budgetBytes, ValueSizeTable.valueSizeTable()::estimate, Runnable::run);

        ring.invalidate(TAG, 0);

        return ring;
    }

    /// Appends `count` effect batches at slots `from..from+count-1` with ids `id-<slot>`.
    private static void fill(CommitRing ring, int from, int count) {
        IntStream.range(from, from + count).forEach(slot -> ring.append(TAG, slot, "id-" + slot, effect("k" + slot, "v" + slot), slot + 1));
    }

    private static List<AnchorPair> pairs(int from, int count) {
        return IntStream.range(from, from + count).mapToObj(slot -> new AnchorPair(slot, "id-" + slot)).toList();
    }

    private static List<Message> drain(CommitRing.Subscription subscription) {
        var messages = new ArrayList<Message>();
        var next = subscription.poll();

        while (next.isPresent()) {
            messages.add(next.unwrap());
            next = subscription.poll();
        }

        return messages;
    }

    private static CommitRing.Subscription sink() {
        return CommitRing.subscription(_ -> {});
    }

    @Test
    void append_recordsEntriesInApplyOrder_withAContiguousFromChain() {
        var ring = ring();

        fill(ring, 0, 3);

        assertThat(ring.entries()).extracting(ReplicaEntry::slot).containsExactly(0L, 1L, 2L);
        assertThat(ring.entries()).extracting(ReplicaEntry::from).as("from = the previous entry's slot + 1").containsExactly(0L, 1L, 2L);
        assertThat(ring.nextSlot()).isEqualTo(3L);
    }

    @Test
    void anchor_keepsTheLastEightEffectPairs() {
        var ring = ring();

        fill(ring, 0, 12);

        assertThat(ring.anchor()).as("k = 8 pairs, oldest first").containsExactlyElementsOf(pairs(4, 8));
    }

    @Test
    void emptyEffects_extendTheOpenRange_andLeaveTheAnchorUntouched() {
        var ring = ring();

        fill(ring, 0, 1);
        ring.append(TAG, 1, "filtered", List.of(), 2);
        ring.advance(TAG, 5);

        assertThat(ring.entries()).as("a filtered batch and empty slots make no entry").hasSize(1);
        assertThat(ring.anchor()).containsExactlyElementsOf(pairs(0, 1));
        assertThat(ring.nextSlot()).isEqualTo(5L);
    }

    @Test
    void noneTag_recordsNothing_andServesNothing() {
        var ring = CommitRing.commitRing();

        ring.append(IncarnationTag.NONE, 0, "id-0", effect("k", "v"), 1);

        assertThat(ring.entries()).isEmpty();
        assertThat(ring.subscribeFrom(TAG, 0, pairs(0, 1), sink())).isInstanceOfSatisfying(Refused.class, refused -> assertThat(refused.reason()).isEqualTo(RefusalReason.NOT_SERVING));
    }

    // ---- I-W10: the byte bound ----

    @Test
    void ringNeverExceedsItsBudget_andEvictionRaisesTheFloor() {
        var ring = CommitRing.commitRing(2_000);

        ring.invalidate(TAG, 0);
        fill(ring, 0, 40);

        assertThat(ring.bytes()).as("I-W10 (mutation: drop the eviction loop, bytes grows past the budget)").isLessThanOrEqualTo(2_000);
        assertThat(ring.floor()).as("the floor rose past the evicted entries").isPositive();
        assertThat(ring.entries().getFirst().slot()).isEqualTo(ring.floor());
    }

    // ---- resubscribe verdict (section 2.4) ----

    @Test
    void subscribeFrom_returnsEveryEntryAfterAVerifiedAnchor() {
        var ring = ring();

        fill(ring, 0, 5);
        var result = ring.subscribeFrom(TAG, 3, pairs(0, 3), sink());

        assertThat(result).isInstanceOfSatisfying(Granted.class, granted -> {
            assertThat(granted.entries()).extracting(ReplicaEntry::slot).containsExactly(3L, 4L);
            assertThat(granted.latest()).isEqualTo(5L);
        });
    }

    @Test
    void subscribeFrom_refusesAMismatchedTag_andANoneTagMatchesNothing() {
        var ring = ring();

        fill(ring, 0, 2);

        assertThat(ring.subscribeFrom(IncarnationTag.incarnationTag(1, "OTHER"), 2, pairs(0, 2), sink()))
                .as("a re-minted incarnation 1 is a different life: tags compare by equality, not by number (mutation: compare by number only)")
                .isInstanceOfSatisfying(Refused.class, refused -> assertThat(refused.reason()).isEqualTo(RefusalReason.INCARNATION_MISMATCH));
        assertThat(ring.subscribeFrom(IncarnationTag.NONE, 2, pairs(0, 2), sink()))
                .isInstanceOfSatisfying(Refused.class, refused -> assertThat(refused.reason()).isEqualTo(RefusalReason.INCARNATION_MISMATCH));
        assertThat(IncarnationTag.NONE.matches(IncarnationTag.NONE)).as("two unrelated pre-genesis clusters must not compare equal").isFalse();
    }

    @Test
    void subscribeFrom_refusesAPositionAheadOfTheSource() {
        var ring = ring();

        fill(ring, 0, 2);

        assertThat(ring.subscribeFrom(TAG, 5, pairs(0, 2), sink())).isInstanceOfSatisfying(Refused.class, refused -> assertThat(refused.reason()).isEqualTo(RefusalReason.AHEAD));
    }

    @Test
    void subscribeFrom_refusesADifferentBatchAtTheAnchorSlot() {
        var ring = ring();

        fill(ring, 0, 3);

        assertThat(ring.subscribeFrom(TAG, 3, List.of(new AnchorPair(0, "id-0"), new AnchorPair(1, "id-1"), new AnchorPair(2, "REUSED")), sink()))
                .as("NT-O35, I-W12 (mutation: drop the batch-id comparison): the slot is present with a different batch, a reused slot")
                .isInstanceOfSatisfying(Refused.class, refused -> assertThat(refused.reason()).isEqualTo(RefusalReason.AHEAD));
    }

    @Test
    void subscribeFrom_refusesAnAnchorAbsentWhileAboveTheFloor() {
        var ring = ring();

        fill(ring, 0, 3);

        assertThat(ring.subscribeFrom(TAG, 3, List.of(new AnchorPair(7, "id-7")), sink())).isInstanceOfSatisfying(Refused.class, refused -> assertThat(refused.reason()).isEqualTo(RefusalReason.AHEAD));
    }

    @Test
    void subscribeFrom_refusesEvicted_whenEveryAnchorPairIsBelowTheFloor_orThePositionIsBelowIt() {
        var ring = ring(1_000);

        fill(ring, 0, 40);
        var floor = ring.floor();

        assertThat(floor).isPositive();
        assertThat(ring.subscribeFrom(TAG, floor, List.of(new AnchorPair(0, "id-0")), sink())).as("every pair below the floor: nothing can be verified")
                                                                                              .isInstanceOfSatisfying(Refused.class, refused -> assertThat(refused.reason()).isEqualTo(RefusalReason.EVICTED));
        assertThat(ring.subscribeFrom(TAG, 0, pairs(0, 1), sink())).as("a position below the floor needs entries the ring no longer holds")
                                                                  .isInstanceOfSatisfying(Refused.class, refused -> assertThat(refused.reason()).isEqualTo(RefusalReason.EVICTED));
        assertThat(ring.subscribeFrom(TAG, 0, List.of(), sink())).as("an unanchored position below the floor is EVICTED as well")
                                                                .isInstanceOfSatisfying(Refused.class, refused -> assertThat(refused.reason()).isEqualTo(RefusalReason.EVICTED));
    }

    /// Section 2.4: "the anchor is absent while `applied >= floor`" is `AHEAD`. An unanchored position is never resumable; every replica
    /// handles `AHEAD` and `EVICTED` alike (discard, snapshot), so only the reason differs. Mutation: answer `EVICTED`, and this goes red.
    @Test
    void subscribeFrom_refusesAnUnanchoredPositionAtOrAboveTheFloor_asAhead() {
        var ring = ring(1_000);

        fill(ring, 0, 40);
        var floor = ring.floor();

        assertThat(floor).isPositive();
        assertThat(ring.subscribeFrom(TAG, floor, List.of(), sink())).as("unanchored, applied == floor")
                                                                    .isInstanceOfSatisfying(Refused.class, refused -> assertThat(refused.reason()).isEqualTo(RefusalReason.AHEAD));
        assertThat(ring.subscribeFrom(TAG, ring.nextSlot(), List.of(), sink())).as("unanchored, applied == the frontier")
                                                                              .isInstanceOfSatisfying(Refused.class, refused -> assertThat(refused.reason()).isEqualTo(RefusalReason.AHEAD));
    }

    /// I-W12 hardening: a position whose anchor pairs all verify, but whose `applied` lies past effect entries the replica never received (here
    /// slots 1 and 2, between its newest pair at 0 and its `applied` of 3). Granting it would hand over nothing and the replica would miss both
    /// slots silently. Mutation: drop the between-the-newest-pair-and-applied check, and the position is granted with zero entries.
    @Test
    void subscribeFrom_refusesAPositionThatSkipsEffectsAfterItsNewestPair() {
        var ring = ring();

        fill(ring, 0, 3);

        assertThat(ring.subscribeFrom(TAG, 3, pairs(0, 1), sink())).as("effects at 1 and 2 lie between the newest pair (0) and applied (3)")
                                                                  .isInstanceOfSatisfying(Refused.class, refused -> assertThat(refused.reason()).isEqualTo(RefusalReason.AHEAD));
        assertThat(ring.subscribeFrom(TAG, 3, pairs(0, 3), sink())).as("control: the same position anchored at its last effect is granted")
                                                                  .isInstanceOf(Granted.class);
        ring.advance(TAG, 6);
        assertThat(ring.subscribeFrom(TAG, 6, pairs(0, 3), sink())).as("control: slots with no effect between the newest pair and applied are no gap")
                                                                  .isInstanceOf(Granted.class);
    }

    /// A position is decoded from the network, so a malformed one (an anchor pair at or above its own `applied`) must still get a verdict, not
    /// an exception from the ordered-index lookup. Mutation: drop the `newest < applied` guard, and the lookup throws.
    @Test
    void subscribeFrom_answersAMalformedPosition_withoutThrowing() {
        var ring = ring();

        fill(ring, 0, 3);

        assertThat(ring.subscribeFrom(TAG, 1, pairs(0, 3), sink())).as("pairs up to slot 2 with applied 1").isNotNull();
    }

    /// I-W3: the answer and the registration are one critical section. A subscriber thread subscribes 2,000 times from the ring's own latest
    /// anchor while a writer appends 20,000 effect batches; for every grant, the granted entries followed by the first entry the subscription
    /// receives live must form one contiguous chain from `applied`, with no gap and no duplicate. Mutation: register the subscription in a
    /// second lock acquisition after the answer, and an entry appended between the two is lost (or delivered twice).
    @Test
    void subscribeFrom_answersAndRegistersAtomically_underConcurrentAppends() throws InterruptedException {
        var ring = ring();
        var violations = new CopyOnWriteArrayList<String>();
        var stop = new AtomicBoolean();
        var grants = new java.util.concurrent.atomic.AtomicInteger();

        ring.append(TAG, 0, "id-0", effect("k0", "v0"), 1);
        var writer = new Thread(() -> fill(ring, 1, 20_000));
        var subscriber = new Thread(() -> {
            for (var round = 0; round < 2_000 && !stop.get(); round++) {
                checkOneGrant(ring, violations, grants);
            }
        });

        writer.start();
        subscriber.start();
        writer.join();
        stop.set(true);
        subscriber.join();

        assertThat(grants.get()).as("control: the race produced grants").isPositive();
        assertThat(violations).as("I-W3 (mutation: register in a second lock acquisition)").isEmpty();
    }

    private static void checkOneGrant(CommitRing ring, List<String> violations, java.util.concurrent.atomic.AtomicInteger grants) {
        var anchor = ring.anchor();
        var applied = anchor.getLast().slot() + 1;
        var subscription = CommitRing.subscription(2_000, _ -> {});

        if (!(ring.subscribeFrom(TAG, applied, anchor, subscription) instanceof Granted granted)) {
            return;
        }

        grants.incrementAndGet();
        var chain = new ArrayList<>(granted.entries());

        while (chain.size() == granted.entries().size()) {
            var next = subscription.poll();

            if (next.isPresent() && next.unwrap() instanceof QueuedEntry queued) {
                chain.add(queued.entry());
            } else if (next.isEmpty() && (subscription.isClosed() || ring.nextSlot() > 20_000)) {
                break;
            }
        }

        var expected = applied;

        for (var entry : chain) {
            if (entry.from() != expected || entry.slot() != expected) {
                violations.add("applied " + applied + ": expected slot " + expected + " but got from " + entry.from() + " slot " + entry.slot());

                return;
            }
            expected = entry.slot() + 1;
        }
    }

    /// NT-O42, the collision arm: the replica's history differs from the source's, and the newest batch is byte-identical (same id at the same
    /// slot). A single-pair anchor accepts it; the 8-pair anchor refuses it while the divergence is inside the window, and accepts it (the admitted
    /// residual) once 8 identical batches follow the divergence. Mutation: verify only the newest pair, and the in-window arm turns red.
    @Test
    void kPairAnchor_refusesADivergenceInsideTheWindow_andAdmitsOneBelowIt() {
        var ring = ring();

        ring.append(TAG, 0, "source-0", effect("k0", "source"), 1);
        IntStream.range(1, 9).forEach(slot -> ring.append(TAG, slot, "same-" + slot, effect("k" + slot, "same"), slot + 1));
        var diverged = new ArrayList<AnchorPair>();

        diverged.add(new AnchorPair(0, "replica-0"));
        IntStream.range(1, 9).forEach(slot -> diverged.add(new AnchorPair(slot, "same-" + slot)));
        var window = diverged.subList(diverged.size() - CommitRing.ANCHOR_PAIRS, diverged.size());

        assertThat(ring.subscribeFrom(TAG, 9, List.of(diverged.getLast()), sink())).as("single pair: the newest batch is identical, so it is accepted").isInstanceOf(Granted.class);
        assertThat(ring.subscribeFrom(TAG, 9, window, sink())).as("8 pairs, divergence 9 batches deep (outside the window): admitted residual").isInstanceOf(Granted.class);
        assertThat(ring.subscribeFrom(TAG, 9, diverged.subList(0, 8), sink())).as("a window that reaches the divergent slot is refused (mutation: verify only the newest pair)")
                                                                              .isInstanceOfSatisfying(Refused.class, refused -> assertThat(refused.reason()).isEqualTo(RefusalReason.AHEAD));
    }

    // ---- NT-O40: floor jump with live sinks ----

    @Test
    void invalidate_clearsTheRing_andClosesEverySubscriberEvicted() {
        var ring = ring();
        var closed = new CopyOnWriteArrayList<CloseReason>();

        fill(ring, 0, 3);
        var subscription = CommitRing.subscription(closed::add);

        ring.subscribeFrom(TAG, 3, pairs(0, 3), subscription);
        ring.invalidate(TAG, 10);

        assertThat(closed).as("I-W1 (mutation: skip the invalidate in restoreSnapshot): the live sink is closed, not left to receive a gap").containsExactly(CloseReason.EVICTED);
        assertThat(subscription.isClosed()).isTrue();
        assertThat(ring.entries()).isEmpty();
        assertThat(ring.floor()).isEqualTo(10L);
        assertThat(ring.nextSlot()).isEqualTo(10L);
        assertThat(ring.anchor()).isEmpty();
    }

    @Test
    void aNewTag_clearsTheRing_andClosesSubscribersWithIncarnationMismatch() {
        var ring = ring();
        var closed = new CopyOnWriteArrayList<CloseReason>();

        fill(ring, 0, 2);
        ring.subscribeFrom(TAG, 2, pairs(0, 2), CommitRing.subscription(closed::add));
        ring.append(IncarnationTag.incarnationTag(2, "B"), 2, "bump", effect("inc", "2"), 3);

        assertThat(closed).containsExactly(CloseReason.INCARNATION_MISMATCH);
        assertThat(ring.tag()).isEqualTo(IncarnationTag.incarnationTag(2, "B"));
        assertThat(ring.entries()).as("the bump batch is not delivered").isEmpty();
        assertThat(ring.floor()).isEqualTo(3L);
    }

    // ---- I-W11: bounded queue, close outside the lock ----

    @Test
    void aSubscriberQueueOverflow_closesTheSubscription_andNeverBlocksTheAppend() {
        var ring = ring();
        var lockHeldDuringClose = new AtomicBoolean(true);
        var closed = new CopyOnWriteArrayList<CloseReason>();
        var small = CommitRing.subscription(300, reason -> {
            lockHeldDuringClose.set(ring.isLockHeldByCurrentThread());
            closed.add(reason);
        });

        fill(ring, 0, 1);
        ring.subscribeFrom(TAG, 1, pairs(0, 1), small);
        fill(ring, 1, 10);

        assertThat(closed).as("I-W11 (mutation: remove the queue bound, the sink never closes)").containsExactly(CloseReason.OVERFLOW);
        assertThat(lockHeldDuringClose.get()).as("the transport close runs after the ring lock is released").isFalse();
        assertThat(ring.nextSlot()).as("the apply went on").isEqualTo(11L);
    }

    /// I-W11: heartbeats count against the byte bound. A subscriber that stopped reading on an idle cluster (no batch, a heartbeat per flush)
    /// is closed `OVERFLOW` after heartbeats alone. Mutation: enqueue a heartbeat without counting it, and the queue grows without limit.
    @Test
    void aStalledSubscriber_isClosedOverflow_byHeartbeatsAlone() {
        var ring = ring();
        var closed = new CopyOnWriteArrayList<CloseReason>();
        var stalled = CommitRing.subscription(1_000, closed::add);

        fill(ring, 0, 1);
        ring.subscribeFrom(TAG, 1, pairs(0, 1), stalled);
        IntStream.range(0, 100).forEach(_ -> ring.flush());

        assertThat(closed).as("closed by heartbeats alone").containsExactly(CloseReason.OVERFLOW);
        assertThat(stalled.closeReason()).isEqualTo(CloseReason.OVERFLOW);
    }

    /// The bound is on what is queued, not on what was ever sent: a subscriber that keeps up gets its bytes back on every poll and is never
    /// closed, however much passes through it. Mutation: `poll` keeps the bytes, and the subscriber is closed `OVERFLOW` after a cumulative
    /// limit.
    @Test
    void aSubscriberThatKeepsUp_isNeverClosed_whateverPassesThroughIt() {
        var ring = ring();
        var closed = new CopyOnWriteArrayList<CloseReason>();
        var keepsUp = CommitRing.subscription(1_000, closed::add);

        fill(ring, 0, 1);
        ring.subscribeFrom(TAG, 1, pairs(0, 1), keepsUp);
        for (var slot = 1; slot < 200; slot++) {
            fill(ring, slot, 1);
            ring.flush();
            drain(keepsUp);
        }

        assertThat(keepsUp.isClosed()).as("about 80 KB passed through a 1,000-byte queue that was drained after every batch").isFalse();
        assertThat(closed).isEmpty();
    }

    /// Eviction removes the entry from the slot index as well as from the ring, so the index stays bounded by the budget too. Mutation: keep
    /// the index entry, and the index holds every effect ever appended.
    @Test
    void eviction_dropsTheEntryFromTheSlotIndex() {
        var ring = ring(2_000);

        fill(ring, 0, 40);

        assertThat(ring.floor()).as("precondition: entries were evicted").isPositive();
        assertThat(ring.indexedEffectCount()).as("the index holds exactly the effect entries the ring still holds").isEqualTo(ring.entryCount());
    }

    /// The ring never appends behind its frontier: an entry for an older slot would reorder the chain and move `coveredTo` back. Mutation: drop
    /// the guard, and slot 5 lands after slot 9.
    @Test
    void append_refusesASlotBehindTheFrontier() {
        var ring = ring();

        ring.append(TAG, 5, "id-5", effect("k", "5"), 6);
        ring.append(TAG, 9, "id-9", effect("k", "9"), 10);
        ring.append(TAG, 5, "id-5", effect("k", "5"), 6);

        assertThat(ring.entries()).extracting(ReplicaEntry::slot).as("apply order intact").containsExactly(5L, 9L);
        assertThat(ring.nextSlot()).isEqualTo(10L);
        assertThat(ring.anchor()).containsExactly(new AnchorPair(5, "id-5"), new AnchorPair(9, "id-9"));
    }

    // ---- R-07: the byte measure ----

    private record TextKey(String id) implements StructuredKey {
        @Override
        public String asString() {
            return "text/" + id;
        }
    }

    /// The estimate is 64 per entry plus, per effect, the length of the key's `asString()`, 16, and the value's constant from the per-class
    /// table (256 for a class the table does not name). Mutations: size the key by `toString()`, or drop the value term, and this goes red.
    @Test
    void estimatedBytes_isTheKeyTextLength_plusTheValueClassConstant() {
        var sizes = ValueSizeTable.valueSizeTable(java.util.Map.of(String.class, 1_000));
        var key = new TextKey("abc");
        var known = new ReplicaEntry(TAG, 0, 0, "id-0", List.of(new KVCommand.Put<>(key, "value")));
        var unknown = new ReplicaEntry(TAG, 0, 0, "id-0", List.of(new KVCommand.Put<>(key, 7L)));
        var removal = new ReplicaEntry(TAG, 0, 0, "id-0", List.of(new KVCommand.Remove<>(key)));

        assertThat(key.asString()).as("precondition: the key's text differs from its record text").isNotEqualTo(key.toString());
        assertThat(CommitRing.estimatedBytes(known, sizes::estimate)).as("64 + |text/abc| + 16 + 1,000").isEqualTo(64 + 8 + 16 + 1_000);
        assertThat(CommitRing.estimatedBytes(unknown, sizes::estimate)).as("a class the table does not name: 256").isEqualTo(64 + 8 + 16 + 256);
        assertThat(CommitRing.estimatedBytes(removal, sizes::estimate)).as("a Remove has no value term").isEqualTo(64 + 8 + 16);

        var plain = new ReplicaEntry(TAG, 0, 0, "id-0", List.of(new KVCommand.Put<>(new RingFixtures.Key("abc"), 7L)));

        assertThat(CommitRing.estimatedBytes(plain, sizes::estimate)).as("a key family with no canonical text is sized by its record text")
                                                                     .isEqualTo(64 + "Key[id=abc]".length() + 16 + 256);
    }

    /// The ring's own factories default to the async close executor as well: a close never runs on the thread that decided it. Mutation:
    /// default the factory to a synchronous executor, and the close runs on the caller's thread.
    @Test
    void commitRing_defaultsToTheAsyncCloseExecutor() throws InterruptedException {
        var ring = CommitRing.commitRing();
        var closeThread = new AtomicReference<Thread>();
        var closed = new java.util.concurrent.CountDownLatch(1);

        ring.invalidate(TAG, 0);
        fill(ring, 0, 1);
        ring.subscribeFrom(TAG, 1, pairs(0, 1), CommitRing.subscription(_ -> recordClose(closeThread, closed)));
        ring.invalidate(TAG, 5);

        assertThat(closed.await(5, java.util.concurrent.TimeUnit.SECONDS)).as("the close ran").isTrue();
        assertThat(closeThread.get()).as("on another thread").isNotSameAs(Thread.currentThread());
    }

    private static void recordClose(AtomicReference<Thread> closeThread, java.util.concurrent.CountDownLatch closed) {
        closeThread.set(Thread.currentThread());
        closed.countDown();
    }

    // ---- flush: marker and heartbeat ----

    @Test
    void flush_closesTheOpenRangeIntoAMarker_thenEnqueuesAHeartbeatAfterIt() {
        var ring = ring();
        var subscription = sink();

        fill(ring, 0, 1);
        ring.subscribeFrom(TAG, 1, pairs(0, 1), subscription);
        ring.advance(TAG, 6);
        ring.flush();

        var messages = drain(subscription);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).isInstanceOfSatisfying(QueuedEntry.class, queued -> {
            assertThat(queued.entry().isMarker()).isTrue();
            assertThat(queued.entry().from()).isEqualTo(1L);
            assertThat(queued.entry().slot()).as("slot = to - 1").isEqualTo(5L);
        });
        assertThat(messages.get(1)).isInstanceOfSatisfying(QueuedHeartbeat.class, heartbeat -> assertThat(heartbeat.nextSlot()).isEqualTo(6L));
        assertThat(ring.anchor()).as("a marker does not change the anchor").containsExactlyElementsOf(pairs(0, 1));
    }

    // ---- NT-O41 / I-W9: no heartbeat between the revision bump and the entry offer ----

    /// A flusher races an append: the append's hook runs between the frontier bump and the offer, and starts the flusher there. The flusher must
    /// not get in: the heartbeat is enqueued strictly after the entry. Mutation (positive control): defer the offer to after the lock is released
    /// through the close executor, and the heartbeat overtakes the entry (this test goes red).
    @Test
    void aHeartbeat_isNeverEnqueuedBetweenTheBumpAndTheOffer() throws InterruptedException {
        var ring = CommitRing.commitRing(CommitRing.DEFAULT_BUDGET_BYTES, _ -> 256, task -> {
            sleepQuietly(150);
            task.run();
        });
        var subscription = sink();

        ring.invalidate(TAG, 0);
        ring.append(TAG, 0, "id-0", effect("k0", "v0"), 1);
        ring.subscribeFrom(TAG, 1, pairs(0, 1), subscription);
        var flusher = new AtomicReference<Thread>();

        ring.betweenBumpAndOffer(() -> {
            var thread = new Thread(ring::flush);

            flusher.set(thread);
            thread.start();
            sleepQuietly(100);
        });
        ring.append(TAG, 1, "id-1", effect("k1", "v1"), 2);
        flusher.get().join();

        var order = drain(subscription).stream().map(message -> message instanceof QueuedEntry ? "entry" : "heartbeat").toList();

        assertThat(order).as("I-W9: the entry, then the heartbeat that carries its nextSlot").containsExactly("entry", "heartbeat");
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
