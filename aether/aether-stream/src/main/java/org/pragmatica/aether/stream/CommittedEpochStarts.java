// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.EpochStart;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.cluster.state.kvstore.KVStore;


/// Where each committed owner epoch of a `(stream, partition)` began (#1971, #1938; PR-6a): the committed
/// `StreamPartitionOwnershipValue.epochStarts()`, oldest first, with the record's fold (`coversFrom`) intact. Every owner
/// commits the start of its epoch before it is activated (#1873, `streamLineageCommit`), so the list is the consensus
/// record of which offsets each epoch assigned.
///
/// Two readers in [StreamPartitionManager]: the doubted prefix of a non-owner copy (I-24, `unverifiedFrom`), and the
/// provenance a catch-up is attributed to when the committed owner vouches an empty history (I-25, `applyOwnerAttributed`).
///
/// [#NONE] answers an empty list: no history is known, so a copy is doubted from 0 and an empty slice stays empty, the
/// behaviour of a manager that is not wired to the cluster.
@FunctionalInterface
public interface CommittedEpochStarts {
    /// The committed epoch starts of `(streamName, partition)`, oldest first; empty when no ownership record is committed.
    List<EpochStart> startsOf(String streamName, int partition);
    /// No committed history: the default of every factory that is not the production one.
    CommittedEpochStarts NONE = (_, _) -> List.of();

    /// Reads the committed ownership record of the partition from `kvStore`.
    static CommittedEpochStarts committedEpochStarts(KVStore<AetherKey, AetherValue> kvStore) {
        return (streamName, partition) -> kvStore.getTyped(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(streamName,
                                                                                                                   partition),
                                                           StreamPartitionOwnershipValue.class)
                                                 .map(StreamPartitionOwnershipValue::epochStarts)
                                                 .or(List.of());
    }
}
