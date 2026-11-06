// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.replica;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.cluster.replica.ClientFixtures.Net;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaEntries;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaHeartbeat;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaRefused;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaSnapshotChunk;
import org.pragmatica.cluster.state.kvstore.CommitRing.RefusalReason;
import org.pragmatica.cluster.state.kvstore.IncarnationTag;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.ReplicaEntry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.cluster.replica.FeedFixtures.Key;
import static org.pragmatica.cluster.replica.FeedFixtures.TAG;
import static org.pragmatica.cluster.replica.FeedFixtures.put;

/// The client's guards, one mutation each (each test names it): session isolation, silence bookkeeping, readiness bookkeeping across a source
/// change, the snapshot transfer's integrity checks and the overflow streak.
@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ReplicaFeedClientGuardsTest {
    private static ReplicaEntry effectAt(long slot, String key) {
        return new ReplicaEntry(TAG, slot, slot, "ghost-" + key, List.of(new KVCommand.Put<>(new Key(key), "x")));
    }

    private static Net joinedOnTwoCores() {
        var net = new Net(2);

        net.genesis();
        net.commitAll(1, put("a", "1"));
        net.step();

        return net;
    }

    /// Takes the first core down, waits out the silence and lets the client open its second session (on the other core).
    private static void failOver(Net net) {
        net.core(0).up.set(false);
        net.advance(3_500);
        net.step();
        net.advance(300);
    }

    private static ReplicaSnapshotChunk withTag(ReplicaSnapshotChunk c, IncarnationTag tag, long total, String hash) {
        return new ReplicaSnapshotChunk(c.sender(), c.requestId(), tag, c.revision(), c.anchor(), hash, total, c.offset(), c.bytes());
    }

    /// Mutation: drop the `open.id == requestId` check in `handle` (a stale grant or chunk of the previous request would enter the new session).
    @Test
    void aMessageOfAnEarlierRequestId_isDiscarded_evenFromTheCurrentSource() {
        var net = joinedOnTwoCores();
        var oldId = net.subscribes().getFirst().requestId();

        failOver(net);
        net.client.tick();
        net.pump();

        var newId = net.subscribes().getLast().requestId();
        var applied = net.clientStore.committedRevision();

        assertThat(newId).isNotEqualTo(oldId);
        net.client.onEntries(new ReplicaEntries(net.core(1).id, oldId, 0, List.of(effectAt(applied, "g1")), applied + 1));
        net.pump();

        assertThat(net.valueOf("g1")).isEqualTo("<absent>");
    }

    /// Mutation: drop the `open.source.equals(sender)` check in `handle` (the right request id from the wrong node would be applied).
    @Test
    void aMessageFromAnotherNode_isDiscarded_evenWithTheCurrentRequestId() {
        var net = joinedOnTwoCores();

        failOver(net);
        net.client.tick();
        net.pump();

        var newId = net.subscribes().getLast().requestId();
        var applied = net.clientStore.committedRevision();

        net.client.onEntries(new ReplicaEntries(net.core(0).id, newId, 0, List.of(effectAt(applied, "g2")), applied + 1));
        net.pump();

        assertThat(net.valueOf("g2")).isEqualTo("<absent>");
    }

    /// Mutation: drop `open.lastHeardNanos = nanos.getAsLong()` in `touch` (a source that keeps talking would be abandoned after 3 s).
    @Test
    void aSourceThatKeepsTalking_isNeverAbandonedForSilence() {
        var net = joinedOnTwoCores();

        for (var i = 0; i < 6; i++) {
            net.advance(1_000);
            net.step();
        }

        assertThat(net.subscribes()).hasSize(1);
        assertThat(net.client.counters().get("replica_silence_switch_total")).isZero();
    }

    private static boolean readyAfterResumeGrantingLatest(long latest) {
        var net = joinedOnTwoCores();

        net.core(0).up.set(false);
        net.advance(3_500);
        net.step();
        net.advance(300);
        net.core(1).up.set(false);
        net.client.tick();
        net.pump();

        var id = net.subscribes().getLast().requestId();
        var applied = net.clientStore.committedRevision();

        net.client.onEntries(new ReplicaEntries(net.core(1).id, id, 0, List.of(), latest));
        net.client.onHeartbeat(new ReplicaHeartbeat(net.core(1).id, id, 1, TAG, latest, true, latest));
        net.pump();
        net.advance(5_100);

        return net.readiness.ready();
    }

    /// (c), on a resumed session: the new source granted a frontier far past what the replica holds and says so in its heartbeat; once that heartbeat
    /// is 5 s old the replica must have caught up to it. Mutation: drop the lag test in `ReplicaReadiness.isCaughtUpToLag`.
    @Test
    void aResumedSession_isNotReady_whenItStaysBehindTheGrantedFrontierPast5Seconds() {
        assertThat(readyAfterResumeGrantingLatest(100)).isFalse();
    }

    @Test
    void aResumedSession_isReady_whenItHoldsTheGrantedFrontier() {
        assertThat(readyAfterResumeGrantingLatest(2)).as("control: the same session with nothing missing is ready").isTrue();
    }

    /// A resume to another source keeps the replica's place: the heartbeats already counted stay true of it and age out under (b), so a re-parent
    /// does not by itself drop readiness; with no heartbeat from the new source they are stale after 30 s (the bound S). Mutation: call
    /// `readiness.sourceChanged()` on a resume in `subscribeTo`, or drop `caughtUp(false)`'s resumable test in `retire` (readiness drops at once).
    @Test
    void afterAResumeToAnotherSource_theReplicaStaysReady_untilTheCountedHeartbeatsAreStale() {
        var net = joinedOnTwoCores();

        failOver(net);
        net.client.tick();
        net.pump();

        assertThat(net.readiness.ready()).as("re-parented from its anchor: still ready").isTrue();
        net.advance(30_001);
        assertThat(net.readiness.ready()).as("no heartbeat from the new source in 30 s: not ready").isFalse();
    }

    /// A re-based replica (the position was discarded, a snapshot follows) does not keep the old source's heartbeats. Mutation: drop
    /// `readiness.sourceChanged()` in the non-resume branch of `subscribeTo`.
    @Test
    void afterAnAbandonedPosition_theOldHeartbeats_doNotCount() {
        var net = joinedOnTwoCores();

        net.applier.discardPosition();
        failOver(net);
        net.client.tick();
        net.pump();

        assertThat(net.readiness.ready()).as("a snapshot is coming: the old source's heartbeats say nothing about it").isFalse();
    }

    /// An AHEAD heartbeat (behind the replica under an equal tag) makes the position unusable. Mutation: drop the `readiness.isAhead` branch in `heartbeat`.
    @Test
    void anAheadHeartbeat_discardsThePosition() {
        var net = joinedOnTwoCores();
        var id = net.subscribes().getFirst().requestId();

        net.client.onHeartbeat(new ReplicaHeartbeat(net.core(0).id, id, 0, TAG, 1, true, 1));
        net.pump();

        assertThat(net.applier.position().isHeld()).isFalse();
    }

    private static Net heldSnapshot() {
        var net = new Net(1);

        net.genesis();
        net.commitAll(1, put("a", "1"));
        net.holdChunks.set(true);
        net.step();

        return net;
    }

    /// Mutation: process a chunk outside the SNAPSHOT phase (drop the phase test in `chunk`): a replayed chunk would install a second time.
    @Test
    void aChunkOutsideTheSnapshotPhase_isDropped() {
        var net = heldSnapshot();
        var chunk = net.heldChunks.getFirst();

        net.releaseChunks();
        net.pump();
        assertThat(net.installs.get()).isEqualTo(1);

        net.client.onSnapshotChunk(chunk);
        net.pump();

        assertThat(net.installs.get()).isEqualTo(1);
    }

    /// Mutation: accept a snapshot whose tag is `NONE` (drop `message.tag().isNone()` in `startTransfer`).
    @Test
    void aSnapshotWithoutATag_isNeverInstalled() {
        var net = heldSnapshot();
        var chunk = net.heldChunks.removeFirst();

        net.client.onSnapshotChunk(withTag(chunk, IncarnationTag.NONE, chunk.total(), chunk.hash()));
        net.pump();

        assertThat(net.installs.get()).isZero();
    }

    /// Mutation: drop the `Integer.MAX_VALUE` guard in `startTransfer`: a length that wraps to the real one would install.
    @Test
    void anAnnouncedLengthBeyondTheIntRange_isNeverInstalled() {
        var net = heldSnapshot();
        var chunk = net.heldChunks.removeFirst();

        net.client.onSnapshotChunk(withTag(chunk, chunk.tag(), (1L << 32) + chunk.total(), chunk.hash()));
        net.pump();

        assertThat(net.installs.get()).isZero();
    }

    /// An abandoned transfer discards the position (X9). Mutation: drop `applier.discardPosition()` in `abandonTransfer`. The unanchored position
    /// survives the disconnect, so the second session is a snapshot session over a still-held position.
    @Test
    void anAbandonedTransfer_discardsTheHeldPosition() {
        var net = new Net(2);

        net.genesis();
        net.step();
        assertThat(net.applier.position().isHeld()).isTrue();

        net.holdChunks.set(true);
        net.core(0).up.set(false);
        net.advance(3_500);
        net.step();
        net.advance(300);
        net.step();

        var chunk = net.heldChunks.removeFirst();

        net.client.onSnapshotChunk(withTag(chunk, chunk.tag(), chunk.total(), "0".repeat(64)));
        net.pump();

        assertThat(net.applier.position().isHeld()).isFalse();
    }

    /// A buffered entry that does not chain after the install is a gap (X8). Mutation: ignore the drain result in `installed`.
    @Test
    void aFailedBufferDrain_endsTheSession() {
        var net = heldSnapshot();
        var id = net.subscribes().getFirst().requestId();

        net.client.onEntries(new ReplicaEntries(net.core(0).id, id, 2, List.of(effectAt(50, "far")), 51));
        net.pump();
        net.releaseChunks();
        net.pump();

        assertThat(net.client.counters().get("replica_gap_total")).isEqualTo(1L);
    }

    /// The overflow streak resets on a successful join (X6). Mutation: drop `consecutiveOverflows = 0` in `checkCaughtUp`.
    @Test
    void theOverflowStreak_resetsAfterASuccessfulJoin() {
        var net = new Net(1, new ReplicaClientLimits(30_000, 5_000, 3_000, 400, 250), ReplicaFeedLimits.DEFAULT);

        net.genesis();
        net.holdChunks.set(true);
        for (var round = 0; round < 2; round++) {
            net.advance(300);
            net.step();
            net.commitAll(1 + round, put("k" + round, "v"));
            net.step();
        }

        net.holdChunks.set(false);
        net.heldChunks.clear();
        net.advance(300);
        net.step();
        assertThat(net.installs.get()).as("a join succeeded between the overflows").isEqualTo(1);

        var id = net.subscribes().getLast().requestId();

        net.client.onRefused(new ReplicaRefused(net.core(0).id, id, RefusalReason.EVICTED, 0, TAG));
        net.pump();
        net.holdChunks.set(true);
        net.advance(300);
        net.step();
        net.commitAll(3, put("k9", "v"));
        net.step();

        assertThat(net.client.counters().get("replica_buffer_overflow_total")).isEqualTo(3L);
        assertThat(net.client.counters().get("replica_snapshot_loop_total")).as("two, a success, one: no streak of three").isZero();
    }

    /// The streak resets when the loop signal fires (X6b): four overflows in a row raise it once, not twice. Mutation: drop `consecutiveOverflows = 0`
    /// in the loop branch of `overflowed`.
    @Test
    void afterTheLoopSignal_theStreakStartsOver() {
        var net = new Net(1, new ReplicaClientLimits(30_000, 5_000, 3_000, 400, 250), ReplicaFeedLimits.DEFAULT);

        net.genesis();
        net.holdChunks.set(true);
        for (var round = 0; round < 3; round++) {
            net.advance(300);
            net.step();
            net.commitAll(1 + round, put("k" + round, "v"));
            net.step();
        }

        assertThat(net.client.counters().get("replica_snapshot_loop_total")).isEqualTo(1L);

        net.advance(31_000);
        net.step();
        net.commitAll(4, put("k4", "v"));
        net.step();

        assertThat(net.client.counters().get("replica_buffer_overflow_total")).isEqualTo(4L);
        assertThat(net.client.counters().get("replica_snapshot_loop_total")).as("a streak of one, not four").isEqualTo(1L);
    }
}
