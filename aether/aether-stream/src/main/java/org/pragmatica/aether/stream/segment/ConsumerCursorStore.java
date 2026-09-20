// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

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
/// #1333: a cursor carries the [RewindEpoch] it was committed under. The epoch-aware pair —
/// [#commit(String, String, int, long, RewindEpoch)] and [#fetchCursor] — is what the consumer runtime
/// calls; the plain pair stays as the contract of a store with no rewind support (a test double, an
/// epoch-less backing), for which the defaults drop the epoch on commit and report [RewindEpoch#NONE]
/// on fetch. A store that persists the epoch overrides the epoch-aware pair and derives the plain one.
public interface ConsumerCursorStore {
    Promise<CommitOutcome> commit(String consumerGroup, String streamName, int partition, long offset);
    Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition);

    /// Commit `offset` under `epoch`. The default discards the epoch — correct only for a store that
    /// cannot be rewound.
    default Promise<CommitOutcome> commit(String consumerGroup,
                                          String streamName,
                                          int partition,
                                          long offset,
                                          RewindEpoch epoch) {
        return commit(consumerGroup, streamName, partition, offset);
    }

    /// The cursor to resume from, with the epoch the resumed consumer must run — and commit — under.
    default Promise<Option<Cursor>> fetchCursor(String consumerGroup, String streamName, int partition) {
        return fetch(consumerGroup, streamName, partition).map(offset -> offset.map(Cursor::unrewound));
    }

    /// A committed cursor and its epoch. Ordered lexicographically `(epoch, offset)`: a rewind's
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

        CommitOutcome PERSISTED = new Persisted();

        static CommitOutcome persisted() {
            return PERSISTED;
        }

        static CommitOutcome localOnly(Cause cause) {
            return new LocalOnly(cause);
        }
    }
}
