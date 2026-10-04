// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

import org.pragmatica.aether.api.OperationalEvent;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;


/// #1883 (owner rule: an operator-facing condition emits an event on its transition): announces a stream partition's
/// in-sync set falling BELOW its confirmation factor — from then every acknowledged publish is refused with
/// `NOT_ENOUGH_REPLICAS` — and its return to the factor.
///
/// Derived from the COMMITTED ISR transition, exactly like [StreamFailoverAnnouncer]: every node applies the same
/// ownership Put and derives the same event from its old and new value, and the cluster-events aggregator publishes
/// only on the owner of the cluster-events partition, so one copy reaches the stream. The condition is a pure function
/// of the committed record and the stream's configured factor, so a commit that keeps it unchanged (an ISR change that
/// stays at or above, or stays below, the factor; an owner move; a fence change) announces nothing: the committed
/// ISR is the dedupe, and no per-commit event can make ISR churn an event storm.
///
/// A stream whose factor is not known on this node (`<= 1`: no confirmation required, or not hydrated) has no
/// minimum to fall below and announces nothing.
public interface StreamIsrAnnouncer {
    @Contract
    void onOwnershipPut(ValuePut<StreamPartitionOwnershipKey, StreamPartitionOwnershipValue> put);

    static StreamIsrAnnouncer streamIsrAnnouncer(ToIntFunction<String> confirmationFactor, Consumer<OperationalEvent> sink) {
        return put -> transition(put.cause().key(),
                                 put.oldValue(),
                                 put.cause().value(),
                                 confirmationFactor.applyAsInt(put.cause().key().stream())).onPresent(sink);
    }

    /// The event a committed change from `before` to `after` calls for: falling below `confirmationFactor` is a
    /// breach, reaching it again a restoration, anything else is silent.
    static Option<OperationalEvent> transition(StreamPartitionOwnershipKey key,
                                               Option<StreamPartitionOwnershipValue> before,
                                               StreamPartitionOwnershipValue after,
                                               int confirmationFactor) {
        var wasBelow = before.map(record -> below(record, confirmationFactor)).or(false);
        var isBelow = below(after, confirmationFactor);

        return wasBelow == isBelow
               ? Option.none()
               : Option.some(event(key, after, confirmationFactor, isBelow));
    }

    /// A record minted before #1730 (`isrVersion` 0) carries no committed ISR, so it is never below anything.
    private static boolean below(StreamPartitionOwnershipValue record, int confirmationFactor) {
        return confirmationFactor > 1 && record.isrVersion() > 0 && record.isr().size() < confirmationFactor;
    }

    private static OperationalEvent event(StreamPartitionOwnershipKey key,
                                          StreamPartitionOwnershipValue record,
                                          int confirmationFactor,
                                          boolean below) {
        var isr = ids(record.isr());
        var fenced = ids(record.fenced());

        return below
               ? OperationalEvent.StreamIsrBelowMinimum.streamIsrBelowMinimum(key.stream(),
                                                                              key.partition(),
                                                                              record.owner().id(),
                                                                              isr,
                                                                              fenced,
                                                                              confirmationFactor)
               : OperationalEvent.StreamIsrRestored.streamIsrRestored(key.stream(),
                                                                      key.partition(),
                                                                      record.owner().id(),
                                                                      isr,
                                                                      fenced,
                                                                      confirmationFactor);
    }

    private static List<String> ids(List<NodeId> nodes) {
        return nodes.stream()
                    .map(NodeId::id)
                    .toList();
    }
}
