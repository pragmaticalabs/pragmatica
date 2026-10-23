// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;


/// What a termination authority does to a node's deployment identity once its termination is confirmed (phase-3 design 12 item 4b, CTO
/// ruling session 35): remove its `ActivationDirectiveKey` AND its `NodeDepartureKey` in ONE leader transaction, each expecting the value
/// read, so the directive and the record go together or not at all. The CTM's confirmed reap (B18) and the operator `--force` clear (B19)
/// use it under FSM2; community placement COMPLETE writes the same pair inside its own transaction.
@FunctionalInterface
public interface TerminationRetirement {
    /// Retires nothing (LEGACY: the cluster deployment FSM deletes the directive at `NodeRemoved`).
    TerminationRetirement NONE = _ -> Promise.unitPromise();
    Promise<Unit> retire(NodeId node);

    /// The retirement was refused: the directive, the record or the committed leader moved since they were read.
    record Refused(NodeId node, String message) implements Cause {
        static final Fn1<Refused, NodeId> FACTORY = Causes.forOneValue("Retirement of the directive and departure record of %s was refused: one of them, or the leader, moved",
                                                                       Refused::new);
    }

    /// The production retirement: reads both keys and the committed leader from `store`, submits through `applier`. A node holding neither
    /// key writes nothing.
    static TerminationRetirement directiveAndRecord(KVStore<AetherKey, AetherValue> store,
                                                    Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier) {
        return node -> transactionFor(store, node).fold(Promise::unitPromise,
                                                        transaction -> submit(applier, node, transaction));
    }

    /// The transaction for `node`, none when it holds neither key or no leader is committed. The record's removal comes FIRST: a transaction's
    /// notifications follow its mutation order, and a directive removed while the cluster FSM still mirrors a DEPARTED record is the case its
    /// R-4.3.3 fallback collects, which would submit a removal of a record this transaction already removed.
    static Option<KVCommand.LeaderTransaction<AetherKey, AetherValue>> transactionFor(KVStore<AetherKey, AetherValue> store,
                                                                                      NodeId node) {
        var removals = Stream.<AetherKey> of(new NodeDepartureKey(node),
                                             ActivationDirectiveKey.activationDirectiveKey(node))
                             .flatMap(key -> removal(store, key).stream())
                             .toList();

        return removals.isEmpty()
               ? Option.none()
               : store.getTyped(LeaderKey.INSTANCE, LeaderValue.class)
                      .map(leader -> transaction(node, leader, removals));
    }

    private static Option<KVCommand.Mutation<AetherKey, AetherValue>> removal(KVStore<AetherKey, AetherValue> store,
                                                                              AetherKey key) {
        return store.get(key)
                    .map(value -> new KVCommand.Mutation<>(key,
                                                           Option.some(value),
                                                           Option.none()));
    }

    private static KVCommand.LeaderTransaction<AetherKey, AetherValue> transaction(NodeId node,
                                                                                   LeaderValue leader,
                                                                                   List<KVCommand.Mutation<AetherKey, AetherValue>> removals) {
        return new KVCommand.LeaderTransaction<>(removals.getFirst().key(),
                                                 "retire:" + node.id() + ":" + UUID.randomUUID(),
                                                 leader,
                                                 List.of(),
                                                 removals);
    }

    private static Promise<Unit> submit(Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                        NodeId node,
                                        KVCommand.LeaderTransaction<AetherKey, AetherValue> transaction) {
        return applier.apply(List.of(transaction))
                      .flatMap(results -> accepted(results,
                                                   transaction.transactionId())
                                          ? Promise.unitPromise()
                                          : Refused.FACTORY.apply(node).promise());
    }

    private static boolean accepted(List<Object> results, String transactionId) {
        return results.stream()
                      .filter(KVCommand.TransactionResult.class::isInstance)
                      .map(KVCommand.TransactionResult.class::cast)
                      .anyMatch(result -> result.transactionId()
                                                .equals(transactionId) && result.accepted());
    }
}
