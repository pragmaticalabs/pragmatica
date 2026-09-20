// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import org.pragmatica.aether.slice.generation.Epoch;
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

    /// #1271: the cursor as seen by a consumer admitted under `assignmentEpoch`. A store that records
    /// the epoch with the offset returns only a cursor written under THAT epoch — a node that regains a
    /// partition must not resume from a cursor it wrote in an earlier tenure, which can be ahead of the
    /// committed cursor its successor left behind.
    default Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition, Epoch assignmentEpoch) {
        return fetch(consumerGroup, streamName, partition);
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
