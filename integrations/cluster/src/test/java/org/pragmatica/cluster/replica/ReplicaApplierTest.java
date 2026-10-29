// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.replica;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.replica.ReplicaApplier.Step;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaEntries;
import org.pragmatica.cluster.state.kvstore.CommitRing;
import org.pragmatica.cluster.state.kvstore.IncarnationTag;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.ReplicaEntry;
import org.pragmatica.cluster.state.kvstore.ReplicaKeyFilter;
import org.pragmatica.cluster.state.kvstore.StructuredKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.cluster.replica.ClientFixtures.CLIENT;
import static org.pragmatica.cluster.replica.FeedFixtures.CORE;
import static org.pragmatica.cluster.replica.FeedFixtures.Key;
import static org.pragmatica.cluster.replica.FeedFixtures.TAG;

/// I-W4 at the replica: tag, `from` chain, `subSeq`, the skip, the buffer and the install. Each test names the mutation of `ReplicaApplier` that
/// turns it red.
class ReplicaApplierTest {
    private KVStore<StructuredKey, Object> store;
    private ReplicaApplier<StructuredKey, Object> applier;

    @BeforeEach
    void installedAtSlot2() {
        var source = ClientFixtures.store();

        FeedFixtures.commit(source, 0, ClientFixtures.incarnation(1, "A"));
        FeedFixtures.commit(source, 1, FeedFixtures.put("a", "1"));
        store = ClientFixtures.store();
        applier = ReplicaApplier.replicaApplier(store, 1 << 20);

        var snapshot = source.captureReplicaSnapshot(ReplicaKeyFilter.includeAll()).unwrap();

        applier.beginBuffering();
        applier.install(snapshot.bytes(), snapshot.tag(), snapshot.revision(), snapshot.anchor()).unwrap();
        applier.beginLive();
    }

    private static ReplicaEntry entry(IncarnationTag tag, long from, long slot, String key, String value) {
        return new ReplicaEntry(tag, from, slot, "b" + slot, List.of(new KVCommand.Put<>(new Key(key), value)));
    }

    private static ReplicaEntries live(long subSeq, ReplicaEntry... entries) {
        return new ReplicaEntries(CORE, 1, subSeq, List.of(entries), 100);
    }

    private String valueOf(String key) {
        return store.get(new Key(key)).map(String::valueOf).or("<absent>");
    }

    @Test
    void accept_appliesInOrder_andMovesAppliedAndTheAnchor() {
        assertThat(applier.accept(live(1, entry(TAG, 2, 2, "b", "2"), entry(TAG, 3, 3, "c", "3")))).isEqualTo(Step.APPLIED);

        assertThat(valueOf("c")).isEqualTo("3");
        assertThat(applier.position().applied()).isEqualTo(4);
        assertThat(applier.position().anchor()).extracting("slot").containsExactly(1L, 2L, 3L);
    }

    /// Mutation: compare the tag by incarnation number only (`entry.tag().incarnation() == held.tag().incarnation()`).
    @Test
    void accept_anotherIncarnationId_discardsThePosition_andWritesNothing() {
        var other = IncarnationTag.incarnationTag(1, "B");

        assertThat(applier.accept(live(1, entry(other, 2, 2, "b", "2")))).isEqualTo(Step.DISCARD_POSITION);

        assertThat(valueOf("b")).isEqualTo("<absent>");
    }

    /// Mutation: drop the `from > committedRevision` check.
    @Test
    void accept_aChainThatSkipsASlot_isAGap() {
        assertThat(applier.accept(live(1, entry(TAG, 5, 5, "e", "5")))).isEqualTo(Step.GAP);

        assertThat(valueOf("e")).isEqualTo("<absent>");
    }

    /// Mutation: drop the `subSeq` contiguity check in `sequence`.
    @Test
    void accept_aLiveSequenceThatSkips_isAGap() {
        assertThat(applier.accept(live(2, entry(TAG, 2, 2, "b", "2")))).isEqualTo(Step.GAP);
    }

    /// Mutation: let `sequence` move `applied` (a heartbeat advances the position).
    @Test
    void sequence_aHeartbeat_consumesTheSequence_butNeverMovesApplied() {
        var before = applier.position();

        assertThat(applier.sequence(1)).isEqualTo(Step.APPLIED);
        assertThat(applier.sequence(1)).as("the same number twice").isEqualTo(Step.GAP);

        assertThat(applier.position()).isEqualTo(before);
    }

    /// Mutation: record an anchor pair for a skipped (already applied) entry; or drop the skip in `KVStore.applyEffects`.
    @Test
    void accept_anAlreadyAppliedEntry_isSkipped_revisionAndAnchorStay() {
        applier.accept(live(1, entry(TAG, 2, 2, "b", "2"), entry(TAG, 3, 3, "c", "3")));

        var before = applier.position();

        assertThat(applier.accept(live(2, entry(TAG, 2, 2, "b", "OLD")))).isEqualTo(Step.APPLIED);

        assertThat(valueOf("b")).isEqualTo("2");
        assertThat(applier.position()).isEqualTo(before);
    }

    /// Mutation: a marker records an anchor pair (`entry.isMarker()` ignored in `record`).
    @Test
    void accept_aRangeMarker_movesApplied_andLeavesTheAnchor() {
        var anchor = applier.position().anchor();
        var marker = new ReplicaEntry(TAG, 2, 6, ReplicaEntry.NONE, List.of());

        assertThat(applier.accept(live(1, marker))).isEqualTo(Step.APPLIED);

        assertThat(applier.position().applied()).isEqualTo(7);
        assertThat(applier.position().anchor()).isEqualTo(anchor);
    }

    /// Mutation: skip the plain-effect check in `KVStore.applyEffects` (a network-decoded `Get` would be applied).
    @Test
    void accept_anEntryHoldingANonPlainCommand_isRejected() {
        var bad = new ReplicaEntry(TAG, 2, 2, "b2", List.of(new KVCommand.Get<>(new Key("a"))));

        assertThat(applier.accept(live(1, bad))).isEqualTo(Step.REJECTED);
    }

    /// Mutation: keep more than 8 pairs, or fewer (`ANCHOR_PAIRS`).
    @Test
    void accept_keepsTheLastEightAnchorPairs() {
        IntStream.range(2, 20).forEach(slot -> applier.accept(live(slot - 1, entry(TAG, slot, slot, "k" + slot, "v"))));

        assertThat(applier.position().anchor()).extracting("slot").containsExactly(12L, 13L, 14L, 15L, 16L, 17L, 18L, 19L);
    }

    /// Mutation: apply while buffering (`buffering` ignored in `accept`); or drop the drain in `install`.
    @Test
    void whileBuffering_liveEntriesWait_andDrainAfterTheInstall() {
        var fresh = ClientFixtures.store();
        var buffered = ReplicaApplier.replicaApplier(fresh, 1 << 20);
        var source = ClientFixtures.store();

        FeedFixtures.commit(source, 0, ClientFixtures.incarnation(1, "A"));
        FeedFixtures.commit(source, 1, FeedFixtures.put("a", "1"));

        var snapshot = source.captureReplicaSnapshot(ReplicaKeyFilter.includeAll()).unwrap();

        buffered.beginBuffering();

        assertThat(buffered.accept(live(1, entry(TAG, 2, 2, "b", "2")))).isEqualTo(Step.BUFFERED);
        assertThat(fresh.get(new Key("b")).isPresent()).isFalse();

        var drained = buffered.install(snapshot.bytes(), snapshot.tag(), snapshot.revision(), snapshot.anchor()).unwrap();

        assertThat(drained).isEqualTo(Step.APPLIED);
        assertThat(fresh.get(new Key("b")).map(String::valueOf).or("<absent>")).isEqualTo("2");
        assertThat(buffered.position().applied()).isEqualTo(3);
    }

    /// Mutation: drop the byte bound in `hold`.
    @Test
    void whileBuffering_pastTheByteBound_isAnOverflow() {
        var small = ReplicaApplier.replicaApplier(ClientFixtures.store(), 100);

        small.beginBuffering();

        assertThat(small.accept(live(1, entry(TAG, 2, 2, "b", "2")))).isEqualTo(Step.OVERFLOW);
        assertThat(small.bufferedBytes()).isZero();
    }

    /// TW-O16 / NT-O41, the real ring feeding the real applier: a heartbeat enqueued after a batch follows that batch's entry (the offer is inside
    /// the append), and a heartbeat never moves `applied`, so no entry is skipped. Mutation: let `sequence` move `applied` to a heartbeat's
    /// `nextSlot` (the withdrawn draft-2 rule), or offer after the store monitor is released (`CommitRing.append`).
    @Test
    void realRingAndApplier_aHeartbeatBetweenEntries_neverSkipsAnEntry() {
        var source = ClientFixtures.store();

        FeedFixtures.commit(source, 0, ClientFixtures.incarnation(1, "A"));
        FeedFixtures.commit(source, 1, FeedFixtures.put("a", "1"));

        var snapshot = source.captureReplicaSnapshot(ReplicaKeyFilter.includeAll()).unwrap();
        var replicaStore = ClientFixtures.store();
        var replica = ReplicaApplier.replicaApplier(replicaStore, 1 << 20);
        var subscription = CommitRing.subscription(1 << 20, _ -> {});

        replica.beginBuffering();
        replica.install(snapshot.bytes(), snapshot.tag(), snapshot.revision(), snapshot.anchor()).unwrap();
        replica.beginLive();
        source.replicaRing().subscribeLive(snapshot.tag(), subscription);
        FeedFixtures.commit(source, 2, FeedFixtures.put("b", "2"));
        source.replicaRing().flush();
        FeedFixtures.commit(source, 3, FeedFixtures.put("c", "3"));
        source.replicaRing().flush();

        var delivered = new java.util.ArrayList<String>();

        while (subscription.poll().fold(() -> false, message -> deliver(replica, message, delivered))) {
            // drain
        }

        assertThat(delivered).as("entry, heartbeat, entry, heartbeat: the heartbeat of a batch follows its entry").containsExactly("entry", "beat", "entry", "beat");
        assertThat(replicaStore.get(new Key("b")).map(String::valueOf).or("<absent>")).isEqualTo("2");
        assertThat(replicaStore.get(new Key("c")).map(String::valueOf).or("<absent>")).isEqualTo("3");
        assertThat(replica.position().applied()).isEqualTo(4);
    }

    private static boolean deliver(ReplicaApplier<StructuredKey, Object> replica, CommitRing.Message message, List<String> delivered) {
        switch (message) {
            case CommitRing.QueuedEntry queued -> {
                delivered.add("entry");
                assertThat(replica.accept(live(queued.subSeq(), queued.entry()))).isEqualTo(Step.APPLIED);
            }
            case CommitRing.QueuedHeartbeat beat -> {
                delivered.add("beat");
                assertThat(replica.sequence(beat.subSeq())).isEqualTo(Step.APPLIED);
            }
        }

        return true;
    }

    /// Spec 3.2 step 2: the installed state is replayed to the store's listeners as synthetic puts. Mutation: drop `store.replayNotifications()` in
    /// `ReplicaApplier.adopt`.
    @Test
    void install_replaysTheInstalledStateToTheListeners() {
        var router = org.pragmatica.messaging.MessageRouter.mutable();
        var seen = new java.util.ArrayList<Object>();
        var source = ClientFixtures.store();

        FeedFixtures.commit(source, 0, ClientFixtures.incarnation(1, "A"));
        FeedFixtures.commit(source, 1, FeedFixtures.put("a", "1"));
        router.addRoute(org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut.class,
                        (java.util.function.Consumer<org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut<?, ?>>) put -> seen.add(put.cause().key()));

        var listened = ReplicaApplier.replicaApplier(ClientFixtures.store(router), 1 << 20);
        var snapshot = source.captureReplicaSnapshot(ReplicaKeyFilter.includeAll()).unwrap();

        listened.beginBuffering();
        listened.install(snapshot.bytes(), snapshot.tag(), snapshot.revision(), snapshot.anchor()).unwrap();

        assertThat(seen).contains(new Key("a"));
    }

    @Test
    void discardPosition_forgetsTheHistory_butNotTheStore() {
        applier.discardPosition();

        assertThat(applier.position().isHeld()).isFalse();
        assertThat(valueOf("a")).isEqualTo("1");
        assertThat(applier.accept(live(1, entry(TAG, 2, 2, "b", "2")))).as("without a position nothing applies").isEqualTo(Step.DISCARD_POSITION);
    }
}
