// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.replica;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.cluster.replica.FeedFixtures.Harness;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaEntries;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaHeartbeat;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaRefused;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaSnapshotChunk;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaSnapshotRequest;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaSubscribe;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.SubscribeMode;
import org.pragmatica.cluster.state.kvstore.AnchorPair;
import org.pragmatica.cluster.state.kvstore.CommitRing.RefusalReason;
import org.pragmatica.cluster.state.kvstore.IncarnationTag;
import org.pragmatica.cluster.state.kvstore.ReplicaEntry;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.cluster.replica.FeedFixtures.CORE;
import static org.pragmatica.cluster.replica.FeedFixtures.OTHER;
import static org.pragmatica.cluster.replica.FeedFixtures.PEER;
import static org.pragmatica.cluster.replica.FeedFixtures.TAG;
import static org.pragmatica.cluster.replica.FeedFixtures.THIRD;
import static org.pragmatica.cluster.replica.FeedFixtures.put;

/// The core side of the replica feed: subscribe, the catch-up and live push, refusals, the snapshot serve, the serve cap, the cache, the serve
/// executor and the bounds (I-W11). NT-O13..O20 name the groups; each test names the mutation of `ReplicaFeedServer` that turns it red.
@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ReplicaFeedServerTest {
    private static ReplicaSubscribe fromAnchor(NodeId peer, long requestId, long applied, List<AnchorPair> anchor) {
        return new ReplicaSubscribe(peer, requestId, TAG, applied, anchor, SubscribeMode.FROM_ANCHOR);
    }

    private static ReplicaSubscribe liveOnly(NodeId peer, long requestId) {
        return new ReplicaSubscribe(peer, requestId, IncarnationTag.NONE, 0, List.of(), SubscribeMode.LIVE_ONLY);
    }

    private static ReplicaSnapshotRequest firstRequest(NodeId peer, long requestId) {
        return new ReplicaSnapshotRequest(peer, requestId, IncarnationTag.NONE, 0, "", 0);
    }

    private static AnchorPair anchorAt(Harness harness, long slot) {
        return harness.store.replicaRing()
                            .entries()
                            .stream()
                            .filter(entry -> entry.slot() == slot && !entry.isMarker())
                            .map(entry -> new AnchorPair(entry.slot(), entry.batchId()))
                            .findFirst()
                            .orElseThrow();
    }

    private static List<Long> slotsOf(List<ReplicaEntries> messages) {
        return messages.stream()
                       .flatMap(message -> message.entries().stream())
                       .map(ReplicaEntry::slot)
                       .toList();
    }

    /// Everything the harness sent to `peer`, in order, as the message classes' simple names.
    private static List<String> kinds(Harness harness, NodeId peer) {
        return harness.sent.stream()
                           .filter(sent -> sent.peer().equals(peer))
                           .map(sent -> sent.message().getClass().getSimpleName())
                           .toList();
    }

    @Nested
    class SubscribeAndPush {
        /// NT-O13. Mutation: set `served.ready` before `sendCatchUp` (the drain tick can overtake the catch-up), or send the catch-up to the wrong
        /// slot range (`entriesFrom` by `>` instead of `>=`).
        @Test
        void subscribe_sendsCatchUpFirst_thenLiveEntriesWithContiguousSequence() {
            var harness = new Harness();

            harness.commit(1, put("a", "1"));
            harness.commit(2, put("b", "2"));
            harness.commit(3, put("c", "3"));
            harness.server.onSubscribe(fromAnchor(PEER, 7, 2, List.of(anchorAt(harness, 1))));

            var catchUp = harness.sentTo(PEER, ReplicaEntries.class);

            assertThat(slotsOf(catchUp)).as("the entries from the position's applied slot on, in apply order").containsExactly(2L, 3L);
            assertThat(catchUp).as("catch-up carries sequence 0 and echoes the request id and the frontier")
                               .allSatisfy(message -> {
                                   assertThat(message.subSeq()).isZero();
                                   assertThat(message.requestId()).isEqualTo(7);
                                   assertThat(message.latest()).isEqualTo(4);
                               });

            harness.commit(4, put("d", "4"));
            harness.server.heartbeat();
            harness.commit(5, put("e", "5"));
            harness.server.tick();

            var live = harness.sent.stream().skip(catchUp.size()).map(FeedFixtures.Sent::message).toList();

            assertThat(live).as("live traffic after the catch-up: entry, heartbeat, entry").hasSize(3);
            assertThat(live.get(0)).isInstanceOfSatisfying(ReplicaEntries.class, entries -> {
                assertThat(entries.subSeq()).isEqualTo(1);
                assertThat(slotsOf(List.of(entries))).containsExactly(4L);
            });
            assertThat(live.get(1)).isInstanceOfSatisfying(ReplicaHeartbeat.class, beat -> {
                assertThat(beat.subSeq()).as("a heartbeat shares the subscription's sequence").isEqualTo(2);
                assertThat(beat.serving()).isTrue();
                assertThat(beat.tag()).isEqualTo(TAG);
                assertThat(beat.nextSlot()).isEqualTo(5);
            });
            assertThat(live.get(2)).isInstanceOfSatisfying(ReplicaEntries.class, entries -> assertThat(entries.subSeq()).isEqualTo(3));
        }

        /// NT-O13. A write that commits while the catch-up is still being sent is queued behind it: the drain tick skips a subscription until its
        /// catch-up is out. Mutation: set `served.ready` before `sendCatchUp` in `admit`.
        @Test
        void aLiveEntryAppendedWhileTheCatchUpIsBeingSent_neverOvertakesIt() {
            var harness = new Harness(new ReplicaFeedLimits(64 * 1024, 2, 5_000, 2, 1 << 20, 8, 30_000, 256, 2));
            var interleaved = new java.util.concurrent.atomic.AtomicBoolean();

            harness.commit(1, put("a", "1"));
            harness.commit(2, put("b", "2"));
            harness.commit(3, put("c", "3"));
            harness.onSend.set(_ -> interleave(harness, interleaved));
            harness.server.onSubscribe(fromAnchor(PEER, 7, 2, List.of(anchorAt(harness, 1))));
            harness.server.tick();

            assertThat(harness.sentTo(PEER, ReplicaEntries.class)).extracting(ReplicaEntries::subSeq)
                                                                  .as("two catch-up messages (sequence 0), then the live entry (sequence 1)")
                                                                  .containsExactly(0L, 0L, 1L);
            assertThat(slotsOf(harness.sentTo(PEER, ReplicaEntries.class))).containsExactly(2L, 3L, 4L);
        }

        private static void interleave(Harness harness, java.util.concurrent.atomic.AtomicBoolean done) {
            if (done.compareAndSet(false, true)) {
                harness.commit(4, put("d", "4"));
                harness.server.tick();
            }
        }

        /// NT-O13. A grant with nothing to catch up still answers, so the subscriber learns it is registered and what the frontier is.
        /// Mutation: send the catch-up only `if (!batch.isEmpty())`.
        @Test
        void subscribe_acknowledgesAnEmptyGrant_withTheFrontier() {
            var harness = new Harness();

            harness.commit(1, put("a", "1"));
            harness.server.onSubscribe(fromAnchor(PEER, 9, 2, List.of(anchorAt(harness, 1))));

            assertThat(harness.sentTo(PEER, ReplicaEntries.class)).singleElement().satisfies(ack -> {
                assertThat(ack.entries()).isEmpty();
                assertThat(ack.subSeq()).isZero();
                assertThat(ack.latest()).isEqualTo(2);
            });
        }

        /// NT-O15. Mutation: have `LIVE_ONLY` call `subscribeFrom` with an empty anchor (refused AHEAD on an unanchored ring), or deliver the
        /// existing entries.
        @Test
        void liveOnly_registersAtTheFrontier_andDeliversOnlyLaterEntries() {
            var harness = new Harness();

            harness.commit(1, put("a", "1"));
            harness.server.onSubscribe(liveOnly(PEER, 3));
            harness.commit(2, put("b", "2"));
            harness.server.tick();

            var messages = harness.sentTo(PEER, ReplicaEntries.class);

            assertThat(messages).as("the grant acknowledgement, then only the later entry").hasSize(2);
            assertThat(messages.get(0).entries()).as("nothing from before the subscription").isEmpty();
            assertThat(slotsOf(messages)).containsExactly(2L);
        }

        /// NT-O15. LIVE_ONLY needs no position, so it registers even where no effect was ever recorded (an unanchored ring).
        @Test
        void liveOnly_isGranted_beforeAnyEffectWasRecorded() {
            var harness = new Harness();

            harness.server.onSubscribe(liveOnly(PEER, 3));

            assertThat(harness.sentTo(PEER, ReplicaRefused.class)).isEmpty();
            assertThat(harness.sentTo(PEER, ReplicaEntries.class)).singleElement().satisfies(ack -> assertThat(ack.latest()).isEqualTo(1));
        }

        /// NT-O15. A LIVE_ONLY subscriber that already holds a life is checked against the ring's tag. Mutation: pass `IncarnationTag.NONE` instead of
        /// `request.tag()` to `subscribeLive` in `subscribe` (a replica of another life is granted).
        @Test
        void liveOnly_fromAnotherLife_isRefusedIncarnationMismatch_andNothingIsRegistered() {
            var harness = new Harness();
            var otherLife = IncarnationTag.incarnationTag(TAG.incarnation(), TAG.incarnationId() + "-other");

            harness.server.onSubscribe(new ReplicaSubscribe(PEER, 3, otherLife, 0, List.of(), SubscribeMode.LIVE_ONLY));

            assertThat(harness.sentTo(PEER, ReplicaRefused.class)).singleElement()
                                                                  .satisfies(refused -> assertThat(refused.reason()).isEqualTo(RefusalReason.INCARNATION_MISMATCH));
            assertThat(harness.sentTo(PEER, ReplicaEntries.class)).isEmpty();
            assertThat(harness.store.replicaRing().subscriptionCount()).isZero();
        }

        /// NT-O13. A catch-up longer than the configured weight is split into several messages, each within it (an entry weighs its effects plus
        /// one). Mutation: drop the flush in `sendCatchUp` (one message), change the weight formula, or skip `batch.clear()`/`weight = 0`.
        @Test
        void catchUp_isSplitIntoMessagesOfBoundedWeight() {
            var harness = new Harness(new ReplicaFeedLimits(64 * 1024, 2, 5_000, 2, 1 << 20, 8, 30_000, 256, 4));

            for (var slot = 1; slot <= 5; slot++) {
                harness.commit(slot, put("k" + slot, "v"));
            }

            harness.server.onSubscribe(fromAnchor(PEER, 7, 2, List.of(anchorAt(harness, 1))));

            var catchUp = harness.sentTo(PEER, ReplicaEntries.class);

            assertThat(catchUp.stream().map(message -> message.entries().stream().map(ReplicaEntry::slot).toList()).toList())
                .as("four entries of weight two, at most four per message").containsExactly(List.of(2L, 3L), List.of(4L, 5L));
        }

        /// NT-O13. One tick sends at most the configured number of messages to a subscriber; the rest wait for the next tick, so one busy
        /// subscriber cannot starve the others, and no message is lost at the cap. Red on the head before the fix: the drain loop polled one message past the cap and dropped it
        /// (3rd tick saw 4 of 5). Mutation: drop the `remaining > 0` bound in `drainUpTo`, or off by one.
        @Test
        void tick_sendsAtMostTheDrainCapPerSubscriber_andTheRestOnTheNextTick() {
            var harness = new Harness(new ReplicaFeedLimits(64 * 1024, 2, 5_000, 2, 1 << 20, 8, 30_000, 2, 256));

            harness.server.onSubscribe(liveOnly(PEER, 1));
            for (var slot = 1; slot <= 5; slot++) {
                harness.commit(slot, put("k" + slot, "v"));
            }

            var live = (java.util.function.Supplier<Long>) () -> harness.sentTo(PEER, ReplicaEntries.class).stream().filter(message -> message.subSeq() > 0).count();

            harness.server.tick();
            assertThat(live.get()).as("first tick").isEqualTo(2L);
            harness.server.tick();
            assertThat(live.get()).as("second tick").isEqualTo(4L);
            harness.server.tick();
            assertThat(live.get()).as("third tick drains the rest").isEqualTo(5L);
        }

        /// The counters the server publishes count what happened. Mutation: drop the increment in `admit` (subscribed) or in `refuse` (refused).
        @Test
        void counters_countSubscriptionsAndRefusals() {
            var harness = new Harness();

            harness.server.onSubscribe(liveOnly(PEER, 1));
            harness.server.onSubscribe(liveOnly(OTHER, 2));
            harness.serving.set(false);
            harness.server.onSubscribe(liveOnly(THIRD, 3));
            harness.server.onSubscribe(liveOnly(THIRD, 4));

            assertThat(harness.server.counters().get("replica_subscribed_total")).isEqualTo(2L);
            assertThat(harness.server.counters().get("replica_refused_total")).isEqualTo(2L);
        }

        /// NT-O20. Mutation: drop the `previous != null` release in `admit` (the first subscription keeps receiving).
        @Test
        void subscribe_releasesThePeersEarlierSubscription() {
            var harness = new Harness();

            harness.server.onSubscribe(liveOnly(PEER, 1));
            harness.server.onSubscribe(liveOnly(PEER, 2));
            harness.commit(1, put("a", "1"));
            harness.server.tick();

            var live = harness.sentTo(PEER, ReplicaEntries.class).stream().filter(message -> message.subSeq() > 0).toList();

            assertThat(live).as("one live copy, on the second request").singleElement().satisfies(message -> assertThat(message.requestId()).isEqualTo(2));
            assertThat(harness.server.counters().get("replica_subscribers")).isEqualTo(1L);
            assertThat(harness.store.replicaRing().subscriptionCount()).as("the first subscription left the ring, it is not an orphan the ring keeps filling")
                                                                       .isEqualTo(1);
        }
    }

    @Nested
    class Refusals {
        /// NT-O14. Mutation: answer the ring's refusal with a different reason, or drop the floor/tag from the refusal.
        @Test
        void subscribe_relaysTheRingsVerdict_asARefusal() {
            var harness = new Harness();

            harness.commit(1, put("a", "1"));
            harness.server.onSubscribe(new ReplicaSubscribe(PEER, 1, IncarnationTag.incarnationTag(2, "B"), 2, List.of(anchorAt(harness, 1)), SubscribeMode.FROM_ANCHOR));
            harness.server.onSubscribe(fromAnchor(OTHER, 2, 99, List.of(anchorAt(harness, 1))));
            harness.server.onSubscribe(fromAnchor(THIRD, 3, 0, List.of(new AnchorPair(0, "old"))));

            assertThat(harness.sentTo(PEER, ReplicaRefused.class)).singleElement().satisfies(refused -> {
                assertThat(refused.reason()).isEqualTo(RefusalReason.INCARNATION_MISMATCH);
                assertThat(refused.tag()).as("the source's tag, so the subscriber can tell").isEqualTo(TAG);
            });
            assertThat(harness.sentTo(OTHER, ReplicaRefused.class)).singleElement()
                                                                   .satisfies(refused -> assertThat(refused.reason()).isEqualTo(RefusalReason.AHEAD));
            assertThat(harness.sentTo(THIRD, ReplicaRefused.class)).singleElement().satisfies(refused -> {
                assertThat(refused.reason()).isEqualTo(RefusalReason.EVICTED);
                assertThat(refused.floor()).as("the source's floor").isEqualTo(1);
            });
            assertThat(harness.server.counters().get("replica_subscribers")).as("a refused subscriber is not registered").isZero();
            assertThat(harness.store.replicaRing().subscriptionCount()).isZero();
        }

        /// NT-O14. Mutation: drop the `serving` check in `serveSubscribe` / `serveSnapshot`, or the `eligible` check.
        @Test
        void requests_areRefusedNotServing_whenTheGateIsClosed_orThePeerIsNotEligible() {
            var harness = new Harness();

            harness.serving.set(false);
            harness.server.onSubscribe(liveOnly(PEER, 1));
            harness.server.onSnapshotRequest(firstRequest(PEER, 2));
            harness.serving.set(true);
            harness.eligible.remove(OTHER);
            harness.server.onSubscribe(liveOnly(OTHER, 3));
            harness.server.onSnapshotRequest(firstRequest(OTHER, 4));

            assertThat(harness.sentTo(PEER, ReplicaRefused.class)).extracting(ReplicaRefused::reason)
                                                                  .containsExactly(RefusalReason.NOT_SERVING, RefusalReason.NOT_SERVING);
            assertThat(harness.sentTo(OTHER, ReplicaRefused.class)).extracting(ReplicaRefused::reason)
                                                                   .containsExactly(RefusalReason.NOT_SERVING, RefusalReason.NOT_SERVING);
            assertThat(harness.sentTo(PEER, ReplicaSnapshotChunk.class)).isEmpty();
        }

        /// NT-O14. A mode this build does not know is refused, never guessed. Mutation: map `UNKNOWN` to `FROM_ANCHOR`.
        @Test
        void subscribe_refusesAnUnknownMode() {
            var harness = new Harness();

            harness.server.onSubscribe(new ReplicaSubscribe(PEER, 1, TAG, 1, List.of(), SubscribeMode.UNKNOWN));

            assertThat(harness.sentTo(PEER, ReplicaRefused.class)).singleElement()
                                                                  .satisfies(refused -> assertThat(refused.reason()).isEqualTo(RefusalReason.NOT_SERVING));
            assertThat(harness.server.counters().get("replica_subscribers")).isZero();
        }

        /// NT-O14. Before genesis (tag NONE) nothing is served.
        @Test
        void requests_areRefusedNotServing_whileNoIncarnationIsCommitted() {
            var harness = new Harness();
            var empty = FeedFixtures.store();
            var bare = ReplicaFeedServer.replicaFeedServer(CORE,
                                                           empty,
                                                           org.pragmatica.cluster.state.kvstore.ReplicaKeyFilter.includeAll(),
                                                           (peer, message) -> harness.sent.add(new FeedFixtures.Sent(peer, message)),
                                                           () -> true,
                                                           _ -> true,
                                                           _ -> true,
                                                           Runnable::run,
                                                           harness.nanos::get,
                                                           ReplicaFeedLimits.DEFAULT);

            bare.onSubscribe(liveOnly(PEER, 1));
            bare.onSnapshotRequest(firstRequest(PEER, 2));

            assertThat(harness.sentTo(PEER, ReplicaRefused.class)).extracting(ReplicaRefused::reason)
                                                                  .containsExactly(RefusalReason.NOT_SERVING, RefusalReason.NOT_SERVING);
        }
    }

    @Nested
    class SnapshotServe {
        private static byte[] reassemble(List<ReplicaSnapshotChunk> chunks) {
            var out = new ByteArrayOutputStream();

            chunks.forEach(chunk -> out.writeBytes(chunk.bytes()));

            return out.toByteArray();
        }

        /// Pulls one whole snapshot the way a client does: the first request, then repeats with the chunk's revision, hash and the next offset.
        private static List<ReplicaSnapshotChunk> pull(Harness harness, NodeId peer, long requestId) {
            harness.server.onSnapshotRequest(firstRequest(peer, requestId));

            var chunks = new ArrayList<>(harness.sentTo(peer, ReplicaSnapshotChunk.class));

            var pulls = 0;

            while (!chunks.isEmpty() && chunks.getLast().offset() + chunks.getLast().bytes().length < chunks.getLast().total()) {
                assertThat(++pulls).as("a snapshot transfer finishes in a bounded number of pulls").isLessThan(10_000);

                var last = chunks.getLast();

                harness.server.onSnapshotRequest(new ReplicaSnapshotRequest(peer, requestId, last.tag(), last.revision(), last.hash(), last.offset() + last.bytes().length));
                chunks = new ArrayList<>(harness.sentTo(peer, ReplicaSnapshotChunk.class));
            }

            return chunks;
        }

        /// NT-O16. Mutation: slice `end` one byte short or long (the exact-size assertion goes red for short), or compute the hash over anything but the whole snapshot.
        @Test
        void snapshot_isServedInOrderedChunks_thatReassembleToTheCapturedImage() throws Exception {
            var harness = new Harness(new ReplicaFeedLimits(16, 2, 5_000, 2, 1 << 20, 8, 30_000, 256, 256));

            harness.commit(1, put("alpha", "one"));
            harness.commit(2, put("beta", "two"));

            var chunks = pull(harness, PEER, 5);
            var whole = reassemble(chunks);
            var expected = harness.store.captureReplicaSnapshot(_ -> false).unwrap();

            assertThat(chunks.size()).as("more than one chunk at 16 bytes").isGreaterThan(1);
            assertThat(chunks).extracting(ReplicaSnapshotChunk::offset).isSorted();
            assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.bytes().length).isLessThanOrEqualTo(16));
            assertThat(chunks.subList(0, chunks.size() - 1)).as("every chunk but the last is exactly the configured size").allSatisfy(chunk -> assertThat(chunk.bytes().length).isEqualTo(16));
            assertThat(whole).isEqualTo(expected.bytes());
            assertThat(chunks).allSatisfy(chunk -> {
                assertThat(chunk.hash()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected.bytes())));
                assertThat(chunk.tag()).isEqualTo(TAG);
                assertThat(chunk.revision()).isEqualTo(expected.revision());
                assertThat(chunk.total()).isEqualTo(expected.bytes().length);
                assertThat(chunk.anchor()).as("the capturing source's last effect pairs").isEqualTo(expected.anchor());
            });
        }

        /// NT-O16. A continuation that names a transfer the server does not hold, or a different image, restarts: refused `EVICTED`.
        /// Mutation: serve the continuation from the live store.
        @Test
        void snapshot_continuationOfAnUnknownTransfer_isRefusedEvicted() {
            var harness = new Harness();

            harness.server.onSnapshotRequest(new ReplicaSnapshotRequest(PEER, 8, TAG, 1, "no-such-hash", 64));

            assertThat(harness.sentTo(PEER, ReplicaRefused.class)).singleElement()
                                                                  .satisfies(refused -> assertThat(refused.reason()).isEqualTo(RefusalReason.EVICTED));
        }

        /// NT-O16. A continuation that repeats another image's hash or revision is refused, not served from the transfer it names.
        /// Mutation: drop the `sameImage` filter in `continueTransfer`.
        @Test
        void snapshot_continuationOfADifferentImage_isRefusedEvicted() {
            var harness = new Harness(new ReplicaFeedLimits(8, 2, 5_000, 2, 1 << 20, 8, 30_000, 256, 256));

            harness.commit(1, put("a", "1"));
            harness.server.onSnapshotRequest(firstRequest(PEER, 5));

            var first = harness.sentTo(PEER, ReplicaSnapshotChunk.class).getFirst();

            harness.server.onSnapshotRequest(new ReplicaSnapshotRequest(PEER, 5, TAG, first.revision(), "not-the-hash", 8));
            harness.server.onSnapshotRequest(new ReplicaSnapshotRequest(PEER, 5, TAG, first.revision() + 1, first.hash(), 8));

            assertThat(harness.sentTo(PEER, ReplicaRefused.class)).extracting(ReplicaRefused::reason).containsExactly(RefusalReason.EVICTED, RefusalReason.EVICTED);
            assertThat(harness.sentTo(PEER, ReplicaSnapshotChunk.class)).as("nothing more was served").hasSize(1);
        }

        /// NT-O16. An offset past the end of the image is a restart too. Mutation: drop the `request.offset() <` filter.
        @Test
        void snapshot_continuationPastTheEnd_isRefusedEvicted() {
            var harness = new Harness(new ReplicaFeedLimits(8, 2, 5_000, 2, 1 << 20, 8, 30_000, 256, 256));

            harness.commit(1, put("a", "1"));
            harness.server.onSnapshotRequest(firstRequest(PEER, 5));

            var first = harness.sentTo(PEER, ReplicaSnapshotChunk.class).getFirst();

            harness.server.onSnapshotRequest(new ReplicaSnapshotRequest(PEER, 5, TAG, first.revision(), first.hash(), first.total() + 1));

            assertThat(harness.sentTo(PEER, ReplicaRefused.class)).extracting(ReplicaRefused::reason).containsExactly(RefusalReason.EVICTED);
        }

        /// NT-O17. Mutation: drop the TTL check in `SnapshotCache.dropExpired`, or key the cache by tag only (a new revision hits).
        @Test
        void snapshot_isReusedWithinTheCacheWindow_andRecapturedAfterItOrAfterAWrite() {
            var harness = new Harness();

            harness.commit(1, put("a", "1"));
            pull(harness, PEER, 1);
            pull(harness, OTHER, 2);

            assertThat(harness.server.counters().get("replica_snapshots_captured_total")).as("two askers, one capture").isEqualTo(1L);
            assertThat(harness.server.counters().get("replica_snapshot_cache_hits_total")).isEqualTo(1L);

            harness.commit(2, put("b", "2"));
            pull(harness, THIRD, 3);

            assertThat(harness.server.counters().get("replica_snapshots_captured_total")).as("a new revision is a new image").isEqualTo(2L);

            harness.advanceMillis(5_001);
            pull(harness, PEER, 4);

            assertThat(harness.server.counters().get("replica_snapshots_captured_total")).as("past the window the image is encoded again").isEqualTo(3L);
        }

        /// NT-O18. Two transfers in flight; a third is answered BUSY and counted; finishing one frees its slot. Mutation: drop the
        /// `serves.tryAcquire()` check, or release the permit twice (the cap would then be 3).
        @Test
        void snapshot_capsConcurrentTransfers_answersBusy_andFreesTheSlotOnFinish() {
            var harness = new Harness(new ReplicaFeedLimits(8, 2, 5_000, 2, 1 << 20, 8, 30_000, 256, 256));

            harness.commit(1, put("a", "1"));
            harness.server.onSnapshotRequest(firstRequest(PEER, 1));
            harness.server.onSnapshotRequest(firstRequest(OTHER, 2));
            harness.server.onSnapshotRequest(firstRequest(THIRD, 3));

            assertThat(harness.sentTo(THIRD, ReplicaRefused.class)).singleElement()
                                                                   .satisfies(refused -> assertThat(refused.reason()).isEqualTo(RefusalReason.BUSY));
            assertThat(harness.server.counters().get("replica_busy_total")).isEqualTo(1L);
            assertThat(harness.server.counters().get("replica_transfers")).isEqualTo(2L);

            pull(harness, PEER, 1);
            harness.server.onSnapshotRequest(firstRequest(THIRD, 4));

            assertThat(harness.sentTo(THIRD, ReplicaSnapshotChunk.class)).as("PEER's transfer finished, so its slot is free").isNotEmpty();
        }

        /// NT-O18. A peer that restarts the SAME `(peer, requestId)` twice holds one serve slot, not three. Mutation: drop the `finish(key, old)`
        /// on restart in `beginTransfer` (each restart leaks a permit, the old transfer leaves the map and idle abandonment never frees it).
        @Test
        void snapshot_restartOfTheSameRequest_releasesTheEarlierPermit() {
            var harness = new Harness(new ReplicaFeedLimits(8, 2, 5_000, 2, 1 << 20, 8, 30_000, 256, 256));

            harness.commit(1, put("a", "1"));
            harness.server.onSnapshotRequest(firstRequest(PEER, 1));
            harness.server.onSnapshotRequest(firstRequest(PEER, 1));
            harness.server.onSnapshotRequest(firstRequest(PEER, 1));
            harness.server.onSnapshotRequest(firstRequest(OTHER, 2));

            assertThat(harness.sentTo(PEER, ReplicaRefused.class)).as("the restarts are served").isEmpty();
            assertThat(harness.sentTo(OTHER, ReplicaRefused.class)).as("one slot held by PEER, one free for OTHER").isEmpty();
            assertThat(harness.sentTo(OTHER, ReplicaSnapshotChunk.class)).isNotEmpty();
            assertThat(harness.server.counters().get("replica_transfers")).isEqualTo(2L);
        }

        /// NT-O18. A capture that fails (no incarnation committed yet) gives its serve permit back: after two failures a request that can be served
        /// is not `BUSY`. Mutation: drop `serves.release()` in `abandonStart`.
        @Test
        void snapshot_failedCapture_releasesItsPermit() {
            var harness = new Harness();
            var empty = FeedFixtures.store();
            var bare = ReplicaFeedServer.replicaFeedServer(CORE,
                                                           empty,
                                                           org.pragmatica.cluster.state.kvstore.ReplicaKeyFilter.includeAll(),
                                                           (peer, message) -> harness.sent.add(new FeedFixtures.Sent(peer, message)),
                                                           () -> true,
                                                           _ -> true,
                                                           _ -> true,
                                                           Runnable::run,
                                                           harness.nanos::get,
                                                           new ReplicaFeedLimits(8, 1, 5_000, 2, 1 << 20, 8, 30_000, 256, 256));

            bare.onSnapshotRequest(firstRequest(PEER, 1));
            bare.onSnapshotRequest(firstRequest(PEER, 2));
            FeedFixtures.commit(empty, 0, new org.pragmatica.cluster.state.kvstore.KVCommand.Put<>(FeedFixtures.IncKey.INSTANCE, new FeedFixtures.IncValue(1, "A")));
            bare.onSnapshotRequest(firstRequest(PEER, 3));

            assertThat(harness.sentTo(PEER, ReplicaRefused.class)).extracting(ReplicaRefused::reason)
                                                                  .as("the two failed captures, and no BUSY from a leaked permit")
                                                                  .containsExactly(RefusalReason.NOT_SERVING, RefusalReason.NOT_SERVING);
            assertThat(harness.sentTo(PEER, ReplicaSnapshotChunk.class)).isNotEmpty();
        }

        /// NT-O16. A continuation at exactly the end offset has nothing to send and is refused `EVICTED`. Mutation: `<` to `<=` in `continueTransfer`.
        @Test
        void snapshot_continuationAtExactlyTheEndOffset_isRefusedEvicted() {
            var harness = new Harness(new ReplicaFeedLimits(8, 2, 5_000, 2, 1 << 20, 8, 30_000, 256, 256));

            harness.commit(1, put("a", "1"));
            harness.server.onSnapshotRequest(firstRequest(PEER, 1));

            var first = harness.sentTo(PEER, ReplicaSnapshotChunk.class).getFirst();

            harness.server.onSnapshotRequest(new ReplicaSnapshotRequest(PEER, 1, first.tag(), first.revision(), first.hash(), first.total()));

            assertThat(harness.sentTo(PEER, ReplicaRefused.class)).singleElement()
                                                                  .satisfies(refused -> assertThat(refused.reason()).isEqualTo(RefusalReason.EVICTED));
        }

        /// NT-O18. A transfer nobody pulls from is abandoned and frees its slot. Mutation: drop `abandonIdleTransfers` from `tick`.
        @Test
        void snapshot_idleTransferIsAbandoned_andFreesItsSlot() {
            var harness = new Harness(new ReplicaFeedLimits(8, 1, 5_000, 2, 1 << 20, 8, 30_000, 256, 256));

            harness.commit(1, put("a", "1"));
            harness.server.onSnapshotRequest(firstRequest(PEER, 1));
            harness.server.onSnapshotRequest(firstRequest(OTHER, 2));

            assertThat(harness.sentTo(OTHER, ReplicaRefused.class)).as("cap of one").hasSize(1);

            harness.advanceMillis(30_001);
            harness.server.tick();
            harness.server.onSnapshotRequest(firstRequest(OTHER, 3));

            assertThat(harness.sentTo(OTHER, ReplicaSnapshotChunk.class)).isNotEmpty();
        }
    }

    @Nested
    class ServeExecutor {
        /// NT-O19. The handler returns at once: nothing is captured or sent on the calling (network) thread; the work runs on the serve executor.
        /// Mutation: run the work inline in `hand`.
        @Test
        void serve_runsOnTheServeExecutor_notOnTheCallingThread() throws Exception {
            var harness = new Harness();
            var queued = new CopyOnWriteArrayList<Runnable>();
            var ranOn = new CopyOnWriteArrayList<String>();

            harness.commit(1, put("a", "1"));
            harness.executor.set(queued::add);
            harness.server.onSnapshotRequest(firstRequest(PEER, 1));
            harness.server.onSubscribe(liveOnly(OTHER, 2));

            assertThat(harness.sent).as("nothing sent from the calling thread").isEmpty();
            assertThat(harness.server.counters().get("replica_snapshots_captured_total")).as("nothing captured there either").isZero();
            assertThat(queued).hasSize(2);

            var worker = new Thread(() -> {
                ranOn.add(Thread.currentThread().getName());
                queued.forEach(Runnable::run);
            }, "serve-test-thread");

            worker.start();
            worker.join();

            assertThat(ranOn).containsExactly("serve-test-thread");
            assertThat(harness.sentTo(PEER, ReplicaSnapshotChunk.class)).isNotEmpty();
            assertThat(harness.sentTo(OTHER, ReplicaEntries.class)).hasSize(1);
        }

        /// NT-O19 / I-W11. A saturated serve pool answers BUSY instead of queueing without bound. Mutation: swallow the rejection.
        @Test
        void serve_answersBusy_whenTheServePoolIsSaturated() {
            var harness = new Harness();

            harness.executor.set(task -> {
                throw new RejectedExecutionException("saturated");
            });
            harness.server.onSubscribe(liveOnly(PEER, 1));
            harness.server.onSnapshotRequest(firstRequest(OTHER, 2));

            assertThat(harness.sentTo(PEER, ReplicaRefused.class)).extracting(ReplicaRefused::reason).containsExactly(RefusalReason.BUSY);
            assertThat(harness.sentTo(OTHER, ReplicaRefused.class)).extracting(ReplicaRefused::reason).containsExactly(RefusalReason.BUSY);
            assertThat(harness.server.counters().get("replica_busy_total")).isEqualTo(2L);
        }
    }

    @Nested
    class HeartbeatAndLiveness {
        /// NT-O20. The heartbeat's `serving` follows the gate at send time, so a subscriber notices a source that stopped serving.
        /// Mutation: hard-code `serving = true` in `push`.
        @Test
        void heartbeat_reportsServingFalse_whenTheGateCloses() {
            var harness = new Harness();

            harness.server.onSubscribe(liveOnly(PEER, 1));
            harness.server.heartbeat();
            harness.server.tick();
            harness.serving.set(false);
            harness.server.heartbeat();
            harness.server.tick();

            assertThat(harness.sentTo(PEER, ReplicaHeartbeat.class)).extracting(ReplicaHeartbeat::serving).containsExactly(true, false);
        }

        /// W4 (relay, true-to-false edge): a node that stops serving (a governor deposed, or one that fell behind its core) tells every subscriber
        /// `NOT_SERVING` and releases it at the next tick, so members leave at once instead of waiting out the silence. A queued heartbeat still goes
        /// out first. Mutation: drop `dismissWhenNotServing` from `tick`, or send the refusal without releasing.
        @Test
        void tick_dismissesEverySubscriber_whenTheNodeStopsServing() {
            var harness = new Harness();

            harness.server.onSubscribe(liveOnly(PEER, 1));
            harness.server.onSubscribe(liveOnly(OTHER, 2));
            harness.server.tick();
            harness.serving.set(false);
            harness.server.tick();

            assertThat(harness.sentTo(PEER, ReplicaRefused.class)).extracting(ReplicaRefused::reason).containsExactly(RefusalReason.NOT_SERVING);
            assertThat(harness.sentTo(OTHER, ReplicaRefused.class)).extracting(ReplicaRefused::reason).containsExactly(RefusalReason.NOT_SERVING);
            assertThat(harness.server.counters().get("replica_subscribers")).isZero();
            assertThat(harness.store.replicaRing().subscriptionCount()).isZero();

            harness.server.tick();

            assertThat(harness.sentTo(PEER, ReplicaRefused.class)).as("once").hasSize(1);
        }

        /// NT-O20. A peer whose link is gone (or that is no longer eligible) is released at the next tick, so the ring stops offering to it.
        /// Mutation: drop `releaseGone` from `tick`, or drop the `unsubscribe` call in `release` (the ring keeps filling an orphan subscription).
        @Test
        void tick_releasesTheSubscriptionOfAPeerThatWentAway() {
            var harness = new Harness();

            harness.server.onSubscribe(liveOnly(PEER, 1));
            harness.server.onSubscribe(liveOnly(OTHER, 2));
            harness.connected.remove(PEER);
            harness.eligible.remove(OTHER);
            harness.server.tick();
            harness.commit(1, put("a", "1"));
            harness.server.tick();

            assertThat(harness.server.counters().get("replica_subscribers")).isZero();
            assertThat(harness.store.replicaRing().subscriptionCount()).as("the ring no longer offers to the gone peers' subscriptions").isZero();
            assertThat(harness.sentTo(PEER, ReplicaEntries.class).stream().filter(message -> message.subSeq() > 0)).isEmpty();
            assertThat(harness.sentTo(OTHER, ReplicaEntries.class).stream().filter(message -> message.subSeq() > 0)).isEmpty();
        }

        /// Spec section 5 row 17 (W4): a source that loses its incarnation (reset: it is stopping, or restarting empty) tells its subscribers
        /// `NOT_SERVING`, not `INCARNATION_MISMATCH`, so a member keeps its position and resumes from its anchor elsewhere instead of re-taking
        /// a snapshot. Mutation: map every incarnation close to `INCARNATION_MISMATCH` (drop `mismatchOrNotServing`).
        @Test
        void aSubscriptionClosedByAReset_isToldNotServing_notIncarnationMismatch() {
            var harness = new Harness();

            harness.server.onSubscribe(liveOnly(PEER, 1));
            harness.store.reset();

            assertThat(harness.sentTo(PEER, ReplicaRefused.class)).singleElement().satisfies(refused -> {
                assertThat(refused.reason()).isEqualTo(RefusalReason.NOT_SERVING);
                assertThat(refused.requestId()).isEqualTo(1);
            });
            assertThat(harness.server.counters().get("replica_subscribers")).isZero();
        }

        /// NT-O20. A subscription the ring closes (here: the store jumps its floor) is dropped from the table and the subscriber is told, so it
        /// resubscribes. Mutation: ignore the close callback (no refusal is sent).
        @Test
        void aSubscriptionTheRingCloses_isReportedToTheSubscriber() {
            var harness = new Harness();

            harness.server.onSubscribe(liveOnly(PEER, 1));
            harness.commit(1, new org.pragmatica.cluster.state.kvstore.KVCommand.Put<>(FeedFixtures.IncKey.INSTANCE, new FeedFixtures.IncValue(2, "B")));

            assertThat(harness.sentTo(PEER, ReplicaRefused.class)).singleElement().satisfies(refused -> {
                assertThat(refused.reason()).isEqualTo(RefusalReason.INCARNATION_MISMATCH);
                assertThat(refused.requestId()).isEqualTo(1);
            });
            assertThat(harness.server.counters().get("replica_subscribers")).isZero();
        }
    }

    @Nested
    class Bounds {
        /// I-W11 (subscriber queue). A subscriber that stops being drained overflows its byte bound and is closed and told; the writer never
        /// blocks. Mutation: remove the bound in `Subscription.offer`.
        @Test
        void aStalledSubscriber_isClosedAtItsQueueBound_andToldToResubscribe() {
            var harness = new Harness(new ReplicaFeedLimits(64 * 1024, 2, 5_000, 2, 150, 8, 30_000, 256, 256));

            harness.server.onSubscribe(liveOnly(PEER, 1));
            harness.commit(1, put("a", "1"));
            harness.commit(2, put("b", "2"));
            harness.commit(3, put("c", "3"));

            assertThat(harness.sentTo(PEER, ReplicaRefused.class)).singleElement()
                                                                  .satisfies(refused -> assertThat(refused.reason()).isEqualTo(RefusalReason.EVICTED));
            assertThat(harness.server.counters().get("replica_subscribers")).isZero();
        }

        /// I-W11 (subscription count). Mutation: drop the `maxSubscribers` check.
        @Test
        void subscribe_answersBusy_beyondTheSubscriberCap() {
            var harness = new Harness(new ReplicaFeedLimits(64 * 1024, 2, 5_000, 2, 1 << 20, 2, 30_000, 256, 256));

            harness.server.onSubscribe(liveOnly(PEER, 1));
            harness.server.onSubscribe(liveOnly(OTHER, 2));
            harness.server.onSubscribe(liveOnly(THIRD, 3));
            harness.server.onSubscribe(liveOnly(PEER, 4));

            assertThat(harness.sentTo(THIRD, ReplicaRefused.class)).extracting(ReplicaRefused::reason).containsExactly(RefusalReason.BUSY);
            assertThat(harness.sentTo(PEER, ReplicaRefused.class)).as("a resubscribe of a held peer is not a new subscriber").isEmpty();
        }

        /// I-W11 (snapshot cache). Mutation: drop the eviction loop in `SnapshotCache.put`.
        @Test
        void snapshotCache_neverHoldsMoreThanItsBound_andDropsTheOldest() {
            var now = new java.util.concurrent.atomic.AtomicLong();
            var cache = new SnapshotCache(2, 1_000L, now::get);

            for (var revision = 1; revision <= 3; revision++) {
                cache.put(cached(revision, now.get()));
            }

            assertThat(cache.size()).isEqualTo(2);
            assertThat(cache.get(TAG, 1).isPresent()).as("the oldest was dropped").isFalse();
            assertThat(cache.get(TAG, 3).isPresent()).isTrue();

            now.set(2_000L);

            assertThat(cache.get(TAG, 3).isPresent()).as("an entry past its age is never served").isFalse();
            assertThat(cache.size()).isZero();
        }

        /// I-W11 (snapshot cache). `size()` counts only entries within their age. Mutation: drop `dropExpired` from `size`. (The `dropExpired` in
        /// `put` is equivalent: expired entries are always the oldest, so the bound loop drops them first anyway.)
        @Test
        void snapshotCache_doesNotCountExpiredEntries() {
            var now = new java.util.concurrent.atomic.AtomicLong();
            var cache = new SnapshotCache(2, 1_000L, now::get);

            cache.put(cached(1, now.get()));

            assertThat(cache.size()).isEqualTo(1);

            now.set(2_000L);

            assertThat(cache.size()).as("an expired entry is not counted").isZero();
        }

        private static SnapshotCache.Cached cached(long revision, long storedAt) {
            return new SnapshotCache.Cached(new org.pragmatica.cluster.state.kvstore.ReplicaSnapshot(new byte[0], TAG, revision, List.of()), "h", storedAt);
        }
    }
}
