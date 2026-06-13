// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;


/// Liveness + watermark probe used by {@link PartitionBackfill} to break a cold-start backfill deadlock
/// safely. After a SIMULTANEOUS full-cluster restart every replica of a `(stream, partition)` is
/// `SYNCING` and each waits for a `CAUGHT_UP` source that cannot exist — a cluster-wide deadlock. To
/// break the symmetry without serving stale state, the deciding node must directly observe every other
/// replica's CURRENT local watermark AND distinguish a peer that is reachable-but-syncing from one that
/// is unreachable.
///
/// ## Contract
/// `probe(target, streamName, partition)` resolves with the target replica's highest locally-held
/// offset (its watermark; `-1` for an empty partition) when the target is REACHABLE — regardless of
/// whether that peer is itself still `SYNCING`. It FAILS (timeout / transport error) when the target is
/// UNREACHABLE.
///
/// This success-vs-failure split is exactly the data-safety signal the promotion predicate needs: a
/// reachable peer's watermark is known and comparable; an unreachable peer's watermark is UNKNOWN and
/// might be ahead of self, so self must NOT self-promote past it.
///
/// The production binding queries the peer over the same forward-read transport the read router uses
/// (a reachable peer answers with its local tail offset; an unreachable peer times out).
@FunctionalInterface
public interface ReplicaWatermarkProbe {
    Promise<Long> probe(NodeId target, String streamName, int partition);
}
