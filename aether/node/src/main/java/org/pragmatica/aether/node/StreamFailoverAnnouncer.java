// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.pragmatica.aether.api.OperationalEvent;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// #1730 (owner ruling): announces a stream partition's failover REFUSAL and its RESOLUTION as operational events.
///
/// The announcement is driven by the committed transition, never by the condition: the leader's ownership writer
/// commits the refusal into the partition's ownership record (`failoverRefused`) with a guarded transaction that
/// expects the exact unrefused record, and clears it the same way when an owner is elected or returns. A guarded
/// transaction for one transition is accepted exactly once cluster-wide, so the node whose batch it was in — the
/// leader at that moment — is the single announcer, and a reconcile of a still-refused partition writes nothing and
/// announces nothing. The committed flag is the dedupe: it survives a leader change and a restart.
public interface StreamFailoverAnnouncer {
    /// Announce every refusal/resolution transition among `commands` whose transaction the applier ACCEPTED.
    void announce(List<KVCommand<AetherKey>> commands, List<Object> results);

    StreamFailoverAnnouncer NONE = (_, _) -> {};

    static StreamFailoverAnnouncer streamFailoverAnnouncer(Supplier<List<NodeId>> live,
                                                           Consumer<OperationalEvent> sink) {
        return (commands, results) -> transitions(commands, results, live.get()).forEach(sink);
    }

    /// The events the ACCEPTED transactions among `commands` call for: a mutation that sets `failoverRefused` is a
    /// refusal, one that clears it is a resolution, anything else is silent. A refused transaction changed nothing.
    static List<OperationalEvent> transitions(List<KVCommand<AetherKey>> commands,
                                              List<Object> results,
                                              List<NodeId> live) {
        var accepted = acceptedIds(results);

        return commands.stream()
                       .filter(KVCommand.LeaderTransaction.class::isInstance)
                       .map(command -> (KVCommand.LeaderTransaction<AetherKey, AetherValue>) command)
                       .filter(transaction -> accepted.contains(transaction.transactionId()))
                       .flatMap(transaction -> transaction.mutations()
                                                          .stream())
                       .flatMap(mutation -> transition(mutation, live).stream())
                       .toList();
    }

    private static Set<String> acceptedIds(List<Object> results) {
        return results.stream()
                      .filter(KVCommand.TransactionResult.class::isInstance)
                      .map(KVCommand.TransactionResult.class::cast)
                      .filter(KVCommand.TransactionResult::accepted)
                      .map(KVCommand.TransactionResult::transactionId)
                      .collect(Collectors.toSet());
    }

    private static Option<OperationalEvent> transition(KVCommand.Mutation<AetherKey, AetherValue> mutation,
                                                       List<NodeId> live) {
        if (! (mutation.key() instanceof StreamPartitionOwnershipKey key)) {
            return Option.none();
        }

        var before = mutation.expected()
                             .filter(StreamPartitionOwnershipValue.class::isInstance)
                             .map(StreamPartitionOwnershipValue.class::cast);
        var after = mutation.replacement()
                            .filter(StreamPartitionOwnershipValue.class::isInstance)
                            .map(StreamPartitionOwnershipValue.class::cast);
        var wasRefused = before.map(StreamPartitionOwnershipValue::failoverRefused).or(false);

        return after.flatMap(next -> next.failoverRefused() == wasRefused
                                     ? Option.none()
                                     : Option.some(event(key, next, live, next.failoverRefused())));
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
