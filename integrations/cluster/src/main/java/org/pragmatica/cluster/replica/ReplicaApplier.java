// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.replica;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaEntries;
import org.pragmatica.cluster.state.kvstore.AnchorPair;
import org.pragmatica.cluster.state.kvstore.IncarnationTag;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.ReplicaEntry;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// The replica side of the feed (I-W4): applies the entries of ONE subscription to the local store and tracks the [ReplicaPosition].
///
/// An entry is applied iff its tag equals the followed tag, `from <= applied` (the chain is contiguous) and, for live messages, `subSeq` follows
/// the previous one. The writes go through [KVStore#applyEffects] and nowhere else; the `slot < applied` skip lives there, once, and this class
/// relies on it. Only entries move `applied`: a heartbeat never does, it only passes its `subSeq` through [#sequence].
///
/// Subscribe first, snapshot second (D4): between [#beginBuffering] and [#install] live messages are held in a bounded buffer and replayed after
/// the snapshot. Confined to the client's serial executor; [#position] is safe to read from any thread.
public final class ReplicaApplier<K extends StructuredKey, V> {
    /// What one message did. Everything but `APPLIED` and `BUFFERED` abandons the subscription; `DISCARD_POSITION`, `OVERFLOW` and `REJECTED`
    /// also make the held position unusable.
    public enum Step {
        APPLIED,
        BUFFERED,
        GAP,
        DISCARD_POSITION,
        OVERFLOW,
        REJECTED
    }

    private static final int MESSAGE_OVERHEAD_BYTES = 64;
    private static final int EFFECT_BYTES = 320;

    private final KVStore<K, V> store;
    private final long bufferLimitBytes;
    private final List<ReplicaEntries> buffer = new ArrayList<>();
    private volatile ReplicaPosition position = ReplicaPosition.none();
    private boolean buffering;
    private long lastSubSeq;
    private long bufferedBytes;

    private ReplicaApplier(KVStore<K, V> store, long bufferLimitBytes) {
        this.store = store;
        this.bufferLimitBytes = bufferLimitBytes;
    }

    public static <K extends StructuredKey, V> ReplicaApplier<K, V> replicaApplier(KVStore<K, V> store,
                                                                                   long bufferLimitBytes) {
        return new ReplicaApplier<>(store, bufferLimitBytes);
    }

    public ReplicaPosition position() {
        return position;
    }

    public long bufferedBytes() {
        return bufferedBytes;
    }

    /// The followed history is unusable: forget it. The store keeps its content (a reader keeps its last state); the next install replaces it.
    public ReplicaPosition discardPosition() {
        position = ReplicaPosition.none();

        return position;
    }

    /// A new subscription whose live messages wait for a snapshot.
    public Unit beginBuffering() {
        resetSequence();
        buffering = true;

        return Unit.unit();
    }

    /// A new subscription that resumes the held position: live messages apply at once.
    public Unit beginLive() {
        resetSequence();
        buffering = false;

        return Unit.unit();
    }

    private void resetSequence() {
        buffer.clear();
        bufferedBytes = 0L;
        lastSubSeq = 0L;
    }

    /// The subscription's sequence check, shared by entries and heartbeats: live messages (`subSeq > 0`) must arrive as 1, 2, 3, ...
    public Step sequence(long subSeq) {
        if (subSeq == 0L) {
            return Step.APPLIED;
        }

        if (subSeq != lastSubSeq + 1) {
            return Step.GAP;
        }

        lastSubSeq = subSeq;

        return Step.APPLIED;
    }

    /// One `ReplicaEntries` message. Catch-up messages (`subSeq == 0`) apply at once; live messages are sequence-checked, then applied or, while
    /// a snapshot is pending, buffered.
    public Step accept(ReplicaEntries message) {
        var sequenced = sequence(message.subSeq());

        if (sequenced != Step.APPLIED) {
            return sequenced;
        }

        return buffering && message.subSeq() > 0L
               ? hold(message)
               : applyAll(message);
    }

    private Step hold(ReplicaEntries message) {
        bufferedBytes += weightOf(message);
        if (bufferedBytes > bufferLimitBytes) {
            buffering = false;
            resetSequence();

            return Step.OVERFLOW;
        }

        buffer.add(message);

        return Step.BUFFERED;
    }

    private static long weightOf(ReplicaEntries message) {
        return message.entries()
                      .stream()
                      .mapToLong(entry -> MESSAGE_OVERHEAD_BYTES + (long) entry.effects()
                                                                               .size() * EFFECT_BYTES)
                      .sum() + MESSAGE_OVERHEAD_BYTES;
    }

    private Step applyAll(ReplicaEntries message) {
        for (var entry : message.entries()) {
            var step = applyEntry(entry);

            if (step != Step.APPLIED) {
                return step;
            }
        }

        return Step.APPLIED;
    }

    private Step applyEntry(ReplicaEntry entry) {
        var held = position;

        if (!held.isHeld() || !entry.tag().matches(held.tag())) {
            return Step.DISCARD_POSITION;
        }

        if (entry.from() > store.committedRevision()) {
            return Step.GAP;
        }

        var before = store.committedRevision();

        return store.applyEffects(entry)
                    .fold(_ -> Step.REJECTED,
                          _ -> record(held, entry, before));
    }

    /// The entry was applied or skipped as already applied (`slot < before`); only a newly applied effect entry joins the anchor.
    private Step record(ReplicaPosition held, ReplicaEntry entry, long before) {
        var applied = store.committedRevision();

        position = entry.isMarker() || entry.slot() < before
                   ? held.movedTo(applied)
                   : held.advancedBy(applied,
                                     new AnchorPair(entry.slot(), entry.batchId()));

        return Step.APPLIED;
    }

    /// Installs the filtered snapshot (`restoreCommittedSnapshot`, then the diff replay), makes `(tag, revision, anchor)` the position, and
    /// replays the buffered live messages in order. A failed install changes nothing about the position.
    public Result<Step> install(byte[] bytes, IncarnationTag tag, long revision, List<AnchorPair> anchor) {
        return store.restoreCommittedSnapshot(bytes, revision)
                    .map(_ -> adopt(tag, revision, anchor))
                    .map(_ -> drainBuffer());
    }

    private Unit adopt(IncarnationTag tag, long revision, List<AnchorPair> anchor) {
        store.replayNotifications();
        position = ReplicaPosition.replicaPosition(tag, revision, anchor);

        return Unit.unit();
    }

    private Step drainBuffer() {
        var held = List.copyOf(buffer);

        buffer.clear();
        bufferedBytes = 0L;
        buffering = false;
        for (var message : held) {
            var step = applyAll(message);

            if (step != Step.APPLIED) {
                return step;
            }
        }

        return Step.APPLIED;
    }
}
