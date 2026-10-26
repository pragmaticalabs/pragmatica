// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.replica;

import java.util.List;

import org.pragmatica.cluster.state.kvstore.AnchorPair;
import org.pragmatica.cluster.state.kvstore.CommitRing.RefusalReason;
import org.pragmatica.cluster.state.kvstore.IncarnationTag;
import org.pragmatica.cluster.state.kvstore.ReplicaEntry;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.serialization.Codec;


/// The replica feed: a core pushes the commit ring's entries and a heartbeat to a subscriber, and serves a filtered snapshot in pulled chunks.
/// Entries and heartbeats share one stream type so they stay ordered on a connection; snapshot chunks ride the sync stream. Every message of
/// one subscription echoes the subscriber's `requestId`.
@Codec
public sealed interface ReplicaFeedMessage extends ProtocolMessage {
    @Override
    default StreamType streamType() {
        return StreamType.CONTROL;
    }

    /// `LIVE_ONLY` registers at the frontier and delivers only later entries (subscribe first, snapshot second); `FROM_ANCHOR` resumes from a
    /// position the source verifies.
    @Codec
    enum SubscribeMode {
        LIVE_ONLY,
        FROM_ANCHOR,
        /// Decoded from an ordinal this build does not know (a newer peer): the server refuses it, never guesses a mode.
        UNKNOWN
    }

    /// `anchor` is up to the last 8 `(slot, batchId)` pairs the subscriber applied, oldest first; empty means unanchored.
    record ReplicaSubscribe(NodeId sender,
                            long requestId,
                            IncarnationTag tag,
                            long applied,
                            List<AnchorPair> anchor,
                            SubscribeMode mode) implements ReplicaFeedMessage {
        public ReplicaSubscribe {
            anchor = List.copyOf(anchor);
        }
    }

    /// Entries in apply order. Catch-up messages carry `subSeq = 0`; the live messages that follow carry the subscription's contiguous
    /// sequence, shared with the heartbeats. An empty list with `subSeq = 0` is the grant acknowledgement.
    record ReplicaEntries(NodeId sender, long requestId, long subSeq, List<ReplicaEntry> entries, long latest) implements ReplicaFeedMessage {
        public ReplicaEntries {
            entries = List.copyOf(entries);
        }
    }

    /// The subscription (or snapshot) was refused or ended. `EVICTED`, `INCARNATION_MISMATCH` and `AHEAD` all mean: discard the position and
    /// take a snapshot; `BUSY` and `NOT_SERVING` mean: use another source and back off.
    record ReplicaRefused(NodeId sender, long requestId, RefusalReason reason, long floor, IncarnationTag tag) implements ReplicaFeedMessage {}

    /// `revision` and `hash` are zero and empty on the first request of a snapshot; the answering chunk fixes them, and every further
    /// request of that transfer repeats them with the next `offset`.
    record ReplicaSnapshotRequest(NodeId sender,
                                  long requestId,
                                  IncarnationTag tag,
                                  long revision,
                                  String hash,
                                  long offset) implements ReplicaFeedMessage {
        @Override
        public StreamType streamType() {
            return StreamType.SYNC;
        }
    }

    /// One chunk of the encoded filtered snapshot taken at `(tag, revision)`; `total` is the whole length and `hash` the hex SHA-256 of the
    /// whole snapshot. `anchor` is the capturing source's last pairs (empty: unanchored).
    record ReplicaSnapshotChunk(NodeId sender,
                                long requestId,
                                IncarnationTag tag,
                                long revision,
                                List<AnchorPair> anchor,
                                String hash,
                                long total,
                                long offset,
                                byte[] bytes) implements ReplicaFeedMessage {
        public ReplicaSnapshotChunk {
            anchor = List.copyOf(anchor);
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public StreamType streamType() {
            return StreamType.SYNC;
        }
    }

    /// Liveness and lag: only entries move a subscriber's position, a heartbeat never does. `serving` is false while the source is not (or no
    /// longer) fit to serve; `appliedRevision` is the source's own applied position (a relay's, for a governor).
    record ReplicaHeartbeat(NodeId sender,
                            long requestId,
                            long subSeq,
                            IncarnationTag tag,
                            long nextSlot,
                            boolean serving,
                            long appliedRevision) implements ReplicaFeedMessage {}
}
