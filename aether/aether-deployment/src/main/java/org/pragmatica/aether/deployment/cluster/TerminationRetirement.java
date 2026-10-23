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
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.Retry;
import org.pragmatica.lang.utils.Retry.BackoffStrategy;


/// What a termination authority does to a node's deployment identity once its termination is confirmed (phase-3 design 12 item 4b, CTO
/// ruling session 35): remove its `ActivationDirectiveKey` AND its `NodeDepartureKey` in ONE leader transaction, each expecting the value
/// read, so the directive and the record go together or not at all. The CTM's confirmed reap (B18) and the operator `--force` clear (B19)
/// use it under FSM2; community placement COMPLETE writes the same pair inside its own transaction.
///
/// **A refused retirement is not terminal** (CTO ruling 2026-10-23, P3-F review): the retirement is retried, each attempt re-reading both
/// keys and the committed leader, until it commits or neither key is left. Nothing else deletes the pair under FSM2, so a transient
/// refusal (one of them moved, leadership moved) dropped after one attempt would leave both in place until the next termination.
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

    /// No leader is committed, so no retirement can be witnessed yet.
    record NoCommittedLeader(NodeId node, String message) implements Cause {
        static final Fn1<NoCommittedLeader, NodeId> FACTORY = Causes.forOneValue("Retirement of the directive and departure record of %s waits for a committed leader",
                                                                                 NoCommittedLeader::new);
    }

    /// The production retry: from 500 ms, doubling, capped at 30 s, with no attempt limit; it ends when the retirement commits or there is
    /// nothing left to retire.
    Retry UNTIL_RETIRED = Retry.retry()
                               .attempts(Integer.MAX_VALUE)
                               .strategy(BackoffStrategy.exponential()
                                                        .initialDelay(TimeSpan.timeSpan(500).millis())
                                                        .maxDelay(TimeSpan.timeSpan(30).seconds())
                                                        .factor(2.0)
                                                        .withJitter());

    /// The production retirement: reads both keys and the committed leader from `store`, submits through `applier`, and retries under
    /// `retry` until the transaction commits or the node holds neither key. A node holding neither key writes nothing.
    static TerminationRetirement directiveAndRecord(KVStore<AetherKey, AetherValue> store,
                                                    Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                                    Retry retry) {
        return node -> retry.execute(() -> attempt(store, applier, node));
    }

    /// One attempt, on what the store holds now: done when neither key is left, refused when the transaction is.
    private static Promise<Unit> attempt(KVStore<AetherKey, AetherValue> store,
                                         Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                         NodeId node) {
        return removals(store, node).isEmpty()
               ? Promise.unitPromise()
               : transactionFor(store, node).async(NoCommittedLeader.FACTORY.apply(node))
                               .flatMap(transaction -> submit(applier, node, transaction));
    }

    /// The transaction for `node`, none when it holds neither key or no leader is committed. The record's removal comes FIRST: a transaction's
    /// notifications follow its mutation order, and a directive removed while the cluster FSM still mirrors a DEPARTED record is the case its
    /// R-4.3.3 fallback collects, which would submit a removal of a record this transaction already removed.
    static Option<KVCommand.LeaderTransaction<AetherKey, AetherValue>> transactionFor(KVStore<AetherKey, AetherValue> store,
                                                                                      NodeId node) {
        var removals = removals(store, node);

        return removals.isEmpty()
               ? Option.none()
               : store.getTyped(LeaderKey.INSTANCE, LeaderValue.class)
                      .map(leader -> transaction(node, leader, removals));
    }

    private static List<KVCommand.Mutation<AetherKey, AetherValue>> removals(KVStore<AetherKey, AetherValue> store,
                                                                             NodeId node) {
        return Stream.<AetherKey> of(new NodeDepartureKey(node),
                                     ActivationDirectiveKey.activationDirectiveKey(node))
                     .flatMap(key -> removal(store, key).stream())
                     .toList();
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
