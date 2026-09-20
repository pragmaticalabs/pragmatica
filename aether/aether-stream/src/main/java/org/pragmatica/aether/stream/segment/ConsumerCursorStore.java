// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.RewindEpoch;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;


/// Where a consumer's committed offset lives.
///
/// Extracted from [CursorStore] (#488) so the consumer runtime can be handed something other than
/// the node-local disk store. [CursorStore] itself remains the node-local implementation; the node
/// composes it with a consensus-KV cursor so a declarative consumer resumes after its partition's
/// owner changes — the local store is unreadable from the node that takes over, since its ref index
/// is per-node and never replicated.
///
/// `fetch` returning [Option#empty] means "no cursor recorded", which callers read as
/// create-from-earliest (offset 0), NOT as "start at the head".
///
/// #1271: a consumer admitted under a committed assignment commits and fetches with that assignment's
/// [Epoch] — [#commit(String, String, int, long, Epoch)] / [#fetch(String, String, int, Epoch)].
/// #1333: such a consumer also carries the [RewindEpoch] it was committed under, and a rewind epoch never
/// travels without an assignment (only a managed, fenced group can be rewound by a projection rebuild) —
/// so the rewind-aware pair, [#commit(String, String, int, long, Epoch, RewindEpoch)] and
/// [#fetchCursor], EXTENDS the fenced pair. The plain pair stays as the contract of a store with no
/// notion of either (a test double, the pull API's unfenced cursor); the defaults drop what a store
/// cannot record and report [RewindEpoch#NONE] on fetch. A store that persists both overrides the
/// rewind-aware pair and derives the others.
public interface ConsumerCursorStore {
    Promise<CommitOutcome> commit(String consumerGroup, String streamName, int partition, long offset);
    Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition);

    /// #1271: a commit made under the committed consumer assignment `assignmentEpoch`. A store that
    /// guards the cursor by assignment (the node's cluster-aware store) admits it only while that
    /// assignment is still committed and reports a refusal as [CommitOutcome.Fenced]; a store with no
    /// notion of assignment ignores the epoch.
    default Promise<CommitOutcome> commit(String consumerGroup,
                                          String streamName,
                                          int partition,
                                          long offset,
                                          Epoch assignmentEpoch) {
        return commit(consumerGroup, streamName, partition, offset);
    }

    /// #1333: a fenced commit that also records the [RewindEpoch] the consumer runs under. The default
    /// discards the rewind epoch — correct only for a store that cannot be rewound.
    default Promise<CommitOutcome> commit(String consumerGroup,
                                          String streamName,
                                          int partition,
                                          long offset,
                                          Epoch assignmentEpoch,
                                          RewindEpoch rewindEpoch) {
        return commit(consumerGroup, streamName, partition, offset, assignmentEpoch);
    }

    /// #1271: the cursor as seen by a consumer admitted under `assignmentEpoch`. A store that records
    /// the epoch with the offset returns only a cursor written under THAT epoch — a node that regains a
    /// partition must not resume from a cursor it wrote in an earlier tenure, which can be ahead of the
    /// committed cursor its successor left behind.
    default Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition, Epoch assignmentEpoch) {
        return fetch(consumerGroup, streamName, partition);
    }

    /// #1333: the cursor a consumer admitted under `assignmentEpoch` resumes from, with the rewind epoch
    /// it must run — and commit — under. Same tenure rule as [#fetch(String, String, int, Epoch)].
    default Promise<Option<Cursor>> fetchCursor(String consumerGroup,
                                                String streamName,
                                                int partition,
                                                Epoch assignmentEpoch) {
        return fetch(consumerGroup, streamName, partition, assignmentEpoch).map(offset -> offset.map(Cursor::unrewound));
    }

    /// A committed cursor and its rewind epoch. Ordered lexicographically `(epoch, offset)`: a rewind's
    /// `(epoch', fromOffset)` outranks every pre-rewind `(epoch, high)` however high, which is what
    /// lets a resume pick the rewound position over a stale local one.
    record Cursor(long offset, RewindEpoch epoch) implements Comparable<Cursor> {
        public static Cursor cursor(long offset, RewindEpoch epoch) {
            return new Cursor(offset, epoch);
        }

        public static Cursor unrewound(long offset) {
            return new Cursor(offset, RewindEpoch.NONE);
        }

        @Override
        public int compareTo(Cursor other) {
            var byEpoch = epoch.compareTo(other.epoch);

            return byEpoch != 0
                   ? byEpoch
                   : Long.compare(offset, other.offset);
        }

        public static Cursor later(Cursor first, Cursor second) {
            return first.compareTo(second) >= 0
                   ? first
                   : second;
        }
    }

    /// #1239: what a SUCCESSFUL `commit(...)` actually persisted, carried on that commit's own promise.
    /// A store composed of stages (the node's cluster-aware store chains a consensus checkpoint after the
    /// local write) may let a later stage fail without failing the commit; the outcome says so, for THIS
    /// commit only. It replaces a per-key side channel (`lastRecoveredFailure`) that overlapping commits
    /// for one key could read for each other — reporting one commit's cause against another, or losing
    /// it when the other cleared the entry. A failed `commit(...)` promise still means the first (local)
    /// stage failed.
    sealed interface CommitOutcome {
        /// Every stage this store has persisted the offset; for a single-stage store, its one write.
        record Persisted() implements CommitOutcome {}

        /// The local write succeeded and a later stage did not; `cause` is that stage's failure. The
        /// consumer runtime counts it and retries the periodic checkpoint until it persists.
        record LocalOnly(Cause cause) implements CommitOutcome {}

        /// #1271: the cursor is guarded by a consumer assignment that is no longer this node's — the
        /// commit was refused and nothing cluster-visible moved. TERMINAL: no retry can succeed, because
        /// retrying re-sends the same deposed assignment. The consumer stops delivering and detaches
        /// without a final flush (which would be refused the same way).
        record Fenced(String detail) implements CommitOutcome {}

        CommitOutcome PERSISTED = new Persisted();

        static CommitOutcome persisted() {
            return PERSISTED;
        }

        static CommitOutcome localOnly(Cause cause) {
            return new LocalOnly(cause);
        }

        static CommitOutcome fenced(String detail) {
            return new Fenced(detail);
        }
    }
}
