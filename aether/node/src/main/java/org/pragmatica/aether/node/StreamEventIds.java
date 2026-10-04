// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;


/// The `details.eventId` of the stream events derived from a committed change ([StreamFailoverAnnouncer],
/// [StreamIsrAnnouncer]).
///
/// Every node derives the same event from the same committed Put, and the cluster-events aggregator publishes only on
/// the owner of the events partition. During a membership change two nodes can both pass that owner gate, and each
/// would then stamp its own `<incarnation>:<seq>` id, so no read could tell the two copies are one event. An id that
/// is a pure function of the committed record is the same on both nodes: the aggregator keeps an id that is already
/// set, and every reader that de-duplicates by `eventId` collapses the copies.
///
/// The id names the committed state the event describes: partition, ownership epoch and term, the ISR version and the
/// failover refusal count. The ISR version is monotone, so two genuine ISR transitions never share an id; the refusal
/// count is committed with the flag and grows on every transition into refused within an ownership term (a move restarts it
/// at 0, and the raised term keeps ids distinct), so a partition that is refused,
/// resolved by its owner returning, and refused again in an otherwise identical record still gets a new id.
final class StreamEventIds {
    private StreamEventIds() {}

    /// `kind` of the event, then the committed record it describes.
    static String of(String kind, StreamPartitionOwnershipKey key, StreamPartitionOwnershipValue record) {
        var epoch = record.ownerEpoch();

        return String.join(":",
                           kind,
                           key.stream(),
                           String.valueOf(key.partition()),
                           epoch.incarnation() + "." + epoch.rabiaTerm() + "." + epoch.localCounter(),
                           String.valueOf(record.ownershipTerm()),
                           String.valueOf(record.isrVersion()),
                           String.valueOf(record.failoverRefusalSeq()));
    }
}
