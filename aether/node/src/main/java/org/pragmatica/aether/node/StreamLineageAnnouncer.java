// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.function.Consumer;

import org.pragmatica.aether.api.OperationalEvent;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;


/// #1873 (owner rule: an operator-facing condition emits an event on its transition): announces that a partition's owner began
/// a new epoch WITHOUT a change of owner, which is how a ring rebuilt under an unchanged owner shows in the committed record (a
/// restart without a WAL, a lazy re-materialize, a re-created stream). A change of owner is the failover event's business.
///
/// Derived from the COMMITTED transition, like [StreamFailoverAnnouncer] and [StreamIsrAnnouncer]: every node applies the same
/// ownership Put and derives the same event from its old and new value, and the cluster-events aggregator publishes only on the
/// owner of the cluster-events partition, so one copy reaches the stream. The dedupe is the record itself: a record carries its
/// epoch once, so a commit that does not advance the epoch (an ISR change, a fence change, the owner committing its start)
/// announces nothing, and the epoch advance is committed by exactly one guarded write.
///
/// The event needs the new epoch's start offset, which the owner's `restarted` commit records in the same record; an advance
/// without a start for the new epoch (a leader-written bump) is not announced here.
public interface StreamLineageAnnouncer {
    @Contract
    void onOwnershipPut(ValuePut<StreamPartitionOwnershipKey, StreamPartitionOwnershipValue> put);

    static StreamLineageAnnouncer streamLineageAnnouncer(Consumer<OperationalEvent> sink) {
        return put -> transition(put.cause().key(),
                                 put.oldValue(),
                                 put.cause().value()).onPresent(sink);
    }

    /// The event a committed change from `before` to `after` calls for: the epoch advanced under the same owner, with the start of
    /// the new epoch recorded.
    static Option<OperationalEvent> transition(StreamPartitionOwnershipKey key,
                                               Option<StreamPartitionOwnershipValue> before,
                                               StreamPartitionOwnershipValue after) {
        return before.filter(previous -> previous.owner()
                                                 .equals(after.owner()))
                     .filter(previous -> after.ownerEpoch()
                                              .isStrictlyAfter(previous.ownerEpoch()))
                     .flatMap(previous -> after.lastEpochStart()
                                               .filter(start -> start.epoch()
                                                                     .equals(after.ownerEpoch()))
                                               .map(start -> OperationalEvent.StreamLineageRestarted.streamLineageRestarted(key.stream(),
                                                                                                                            key.partition(),
                                                                                                                            after.owner()
                                                                                                                                 .id(),
                                                                                                                            previous.ownerEpoch()
                                                                                                                                    .toString(),
                                                                                                                            after.ownerEpoch()
                                                                                                                                 .toString(),
                                                                                                                            start.startOffset())));
    }
}
