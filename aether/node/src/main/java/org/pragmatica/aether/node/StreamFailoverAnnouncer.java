// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.pragmatica.aether.api.OperationalEvent;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;


/// #1730 (owner ruling): announces a stream partition's failover REFUSAL and its RESOLUTION as operational events.
///
/// The announcement is derived from the COMMITTED transition, never from the condition and never from the writer's
/// view: the leader's ownership writer commits the refusal into the partition's ownership record (`failoverRefused`)
/// with a guarded transaction expecting the exact unrefused record, and clears it the same way when an owner is
/// elected or returns. Every node applies that committed Put and derives the same event from its old → new value;
/// the cluster-events aggregator publishes only on the owner of the cluster-events partition, so exactly one copy
/// reaches the event stream whichever node led the write. A reconcile of a still-refused partition commits nothing,
/// so nothing is derived; the committed flag is the dedupe, and it survives a leader change and a restart.
public interface StreamFailoverAnnouncer {
    /// Derive and route the event, if any, of one committed ownership Put.
    @Contract
    void onOwnershipPut(ValuePut<StreamPartitionOwnershipKey, StreamPartitionOwnershipValue> put);

    static StreamFailoverAnnouncer streamFailoverAnnouncer(Supplier<List<NodeId>> live,
                                                           Consumer<OperationalEvent> sink) {
        return put -> transition(put.cause().key(),
                                 put.oldValue(),
                                 put.cause().value(),
                                 live.get()).onPresent(sink);
    }

    /// The event a committed change from `before` to `after` calls for: setting `failoverRefused` is a refusal,
    /// clearing it a resolution, anything else is silent.
    static Option<OperationalEvent> transition(StreamPartitionOwnershipKey key,
                                               Option<StreamPartitionOwnershipValue> before,
                                               StreamPartitionOwnershipValue after,
                                               List<NodeId> live) {
        var wasRefused = before.map(StreamPartitionOwnershipValue::failoverRefused).or(false);

        return after.failoverRefused() == wasRefused
               ? Option.none()
               : Option.some(event(key, after, live, after.failoverRefused()));
    }

    private static OperationalEvent event(StreamPartitionOwnershipKey key,
                                          StreamPartitionOwnershipValue record,
                                          List<NodeId> live,
                                          boolean refused) {
        var isr = ids(record.isr());
        var liveIds = ids(live);

        return refused
               ? OperationalEvent.StreamFailoverRefused.streamFailoverRefused(key.stream(),
                                                                              key.partition(),
                                                                              record.owner().id(),
                                                                              isr,
                                                                              liveIds,
                                                                              "owner not live and no in-sync replica live; unclean failover is off")
               : OperationalEvent.StreamFailoverResolved.streamFailoverResolved(key.stream(),
                                                                                key.partition(),
                                                                                record.owner().id(),
                                                                                isr,
                                                                                liveIds,
                                                                                "owner live again or an in-sync replica elected");
    }

    private static List<String> ids(List<NodeId> nodes) {
        return nodes.stream()
                    .map(NodeId::id)
                    .toList();
    }
}
