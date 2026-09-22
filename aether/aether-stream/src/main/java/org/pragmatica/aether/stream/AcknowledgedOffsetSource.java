// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

/// Source of the min-sync ACKNOWLEDGED watermark for a `(stream, partition)` being (re)built on recovery
/// (#1387). A partition's visibility watermark is `min(durable, acknowledged)`, and WAL replay restores
/// only the first half of that: the records it replays are durable by construction, while the
/// acknowledgements that made them visible died with the process. Recovery therefore recomputes visibility
/// through this source instead of letting the replay append decide it.
///
/// `minSyncReplicas` is passed in rather than looked up, and that is the whole point of the interface
/// existing at all. [StreamPartitionManager#minSyncReplicasFor] reads the `streams` map, and while
/// `StreamEntry.fromConfig` is recovering a partition the entry is NOT in that map yet — the lookup would
/// answer `0`, which means "no acknowledgement required" and would make the entire replayed tail visible,
/// reinstating the defect on the createStream path while the lazy-materialize path was fixed. The config
/// being materialized is the only authority available at that moment, so it is the one recovery consults.
///
/// Implementations answer with the convention [org.pragmatica.aether.stream.replication.ReplicationManager#replicatedThrough]
/// uses: the watermark covered by `minSyncReplicas - 1` distinct PEERS, and `Long.MAX_VALUE` when no peer
/// acknowledgement is required (`minSyncReplicas <= 1`), where visible and durable coincide.
@FunctionalInterface
public interface AcknowledgedOffsetSource {
    /// The highest offset covered by enough distinct peers to satisfy `minSyncReplicas` for
    /// `(stream, partition)`, `-1` when no peer has acknowledged anything, or `Long.MAX_VALUE` when the
    /// partition requires no peer acknowledgement at all.
    long acknowledgedThrough(String stream, int partition, int minSyncReplicas);
}
