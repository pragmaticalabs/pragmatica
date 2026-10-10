// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.pragmatica.aether.api.OperationalEvent;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.DHTNode;
import org.pragmatica.dht.DHTNode.StaleRefusal;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;


/// #1777 (owner rule; v1882 round 4): a node whose DHT writes the replication-change fence refuses as stale — stamped
/// below a change the replicas have applied — and that does not adopt the change is an operator-attention condition. It
/// happens when the settle roster dropped a live writer (the leader's membership view held it `Dead`, or never tracked
/// it), so the cluster settled without it and its writes are refused until it learns the change.
///
/// The refused node is the subject, so it is the one emitter: it announces `DHT_WRITER_STALE` once its writes have been
/// refused for longer than [DhtReplicationSettlement#OVERDUE_AFTER_MS] without it adopting a newer change, and
/// `DHT_WRITER_STALE_RESOLVED` once it has. Deduped per node and per refused fence version: one announcement per
/// episode. Evaluated on a cadence the node already runs; no timer of its own.
public interface DhtWriterStaleWatch {
    /// Evaluate the condition now.
    @Contract
    void tick();

    /// The event the transition from `announced` to `current` calls for, at `nowMillis`. Entering: a refusal older than the
    /// bound that has not been announced. Leaving: an announced refusal that is gone, or replaced by a refusal at another
    /// fence (the node adopted a change in between).
    static Option<OperationalEvent> transition(NodeId self,
                                               Option<StaleRefusal> announced,
                                               Option<StaleRefusal> current,
                                               long nowMillis) {
        if (announced.isPresent()) {
            var left = announced.unwrap();

            return current.filter(refusal -> refusal.fence() == left.fence())
                          .isPresent()
                   ? Option.none()
                   : Option.some(OperationalEvent.DhtWriterStaleResolved.dhtWriterStaleResolved(self.id(),
                                                                                                left.fence(),
                                                                                                left.sinceMillis()));
        }

        return current.filter(refusal -> nowMillis - refusal.sinceMillis() > DhtReplicationSettlement.OVERDUE_AFTER_MS)
                      .map(refusal -> OperationalEvent.DhtWriterStale.dhtWriterStale(self.id(),
                                                                                     refusal.fence(),
                                                                                     refusal.sinceMillis()));
    }

    static DhtWriterStaleWatch dhtWriterStaleWatch(NodeId self,
                                                   DHTNode dhtNode,
                                                   Consumer<OperationalEvent> events,
                                                   LongSupplier clock) {
        record watch(NodeId self,
                     DHTNode dhtNode,
                     Consumer<OperationalEvent> events,
                     LongSupplier clock,
                     AtomicReference<Option<StaleRefusal>> announced) implements DhtWriterStaleWatch {
            @Override
            @Contract
            public void tick() {
                var current = dhtNode.staleRefusal();

                transition(self, announced.get(), current, clock.getAsLong()).onPresent(event -> announce(event, current));
            }

            private void announce(OperationalEvent event, Option<StaleRefusal> current) {
                announced.set(event instanceof OperationalEvent.DhtWriterStale
                              ? current
                              : Option.none());
                events.accept(event);
            }
        }

        return new watch(self, dhtNode, events, clock, new AtomicReference<>(Option.none()));
    }
}
