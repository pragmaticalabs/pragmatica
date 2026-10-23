// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Stream;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
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
/// **A refused retirement is retried, but only for the identity the decision read** (CTO rulings `c0b4ae539` (2), amended by `4c533461f`):
/// each retry re-reads both keys and the committed leader, and proceeds only while the departure record is still the DEPARTED record first
/// read (same epoch and process incarnation) or absent. A re-admitted record (ADMITTED/ACTIVE, a newer epoch) ends the retry and leaves the
/// record AND the directive: the compare-and-set refusal protected an identity that changed since the decision, and a retry must not turn it
/// into a delayed removal of the new state. The retry also ends when it commits, when neither key is left, and when the node stops. Nothing
/// else deletes the pair under FSM2, so a transient refusal (leadership moved) dropped after one attempt would leave both in place until the
/// next termination.
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

    /// The departure record is no longer the one the retirement first read (a re-admitted process): the retirement ends, and the record and
    /// the directive stay.
    record Readmitted(NodeId node, String message) implements Cause.Terminal {
        static final Fn1<Readmitted, NodeId> FACTORY = Causes.forOneValue("Retirement of the directive and departure record of %s ended: the record is no longer the one first read, so both stay",
                                                                          Readmitted::new);
    }

    /// The node is stopping: the retirement ends where it is.
    record Stopped(NodeId node, String message) implements Cause.Terminal {
        static final Fn1<Stopped, NodeId> FACTORY = Causes.forOneValue("Retirement of the directive and departure record of %s ended: the node is stopping",
                                                                       Stopped::new);
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

    /// The production retirement (B18, B19): [#directiveAndRecord] under [#UNTIL_RETIRED], ending when `stopped` reports the node stopping (the
    /// node's periodic-task holder, the same stop signal its other retry loops read).
    static TerminationRetirement production(KVStore<AetherKey, AetherValue> store,
                                            Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                            BooleanSupplier stopped) {
        return directiveAndRecord(store, applier, UNTIL_RETIRED, stopped);
    }

    /// Reads both keys and the committed leader from `store`, submits through `applier`, and retries under `retry` while the departure record
    /// is the one first read (or absent) and `stopped` is false, until the transaction commits or the node holds neither key. A node holding
    /// neither key writes nothing.
    static TerminationRetirement directiveAndRecord(KVStore<AetherKey, AetherValue> store,
                                                    Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                                    Retry retry,
                                                    BooleanSupplier stopped) {
        return node -> retireTheIdentityRead(store, applier, retry, stopped, node, store.get(new NodeDepartureKey(node)));
    }

    private static Promise<Unit> retireTheIdentityRead(KVStore<AetherKey, AetherValue> store,
                                                       Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                                       Retry retry,
                                                       BooleanSupplier stopped,
                                                       NodeId node,
                                                       Option<AetherValue> firstRead) {
        var attempts = new AtomicInteger();

        return retry.execute(() -> guardedAttempt(store,
                                                  applier,
                                                  stopped,
                                                  node,
                                                  firstRead,
                                                  attempts.getAndIncrement() == 0));
    }

    /// An attempt unless the node is stopping, or (on a retry) the record is no longer the one first read.
    private static Promise<Unit> guardedAttempt(KVStore<AetherKey, AetherValue> store,
                                                Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                                BooleanSupplier stopped,
                                                NodeId node,
                                                Option<AetherValue> firstRead,
                                                boolean first) {
        return stopped.getAsBoolean()
               ? Stopped.FACTORY.apply(node).promise()
               : attemptOnTheIdentityRead(store, applier, node, firstRead, first);
    }

    private static Promise<Unit> attemptOnTheIdentityRead(KVStore<AetherKey, AetherValue> store,
                                                          Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                                          NodeId node,
                                                          Option<AetherValue> firstRead,
                                                          boolean first) {
        return first || isTheRecordFirstRead(store.get(new NodeDepartureKey(node)), firstRead)
               ? attempt(store, applier, node)
               : Readmitted.FACTORY.apply(node).promise();
    }

    /// Absent, or the DEPARTED record first read: same epoch and process incarnation.
    static boolean isTheRecordFirstRead(Option<AetherValue> current, Option<AetherValue> firstRead) {
        return current.fold(() -> true, now -> isSameDeparture(now, firstRead));
    }

    private static boolean isSameDeparture(AetherValue now, Option<AetherValue> firstRead) {
        return now instanceof NodeDepartureValue record
               && record.state() == DepartureState.DEPARTED
               && firstRead.filter(NodeDepartureValue.class::isInstance)
                           .map(NodeDepartureValue.class::cast)
                           .filter(read -> read.epoch() == record.epoch() && read.process()
                                                                                 .equals(record.process()))
                           .isPresent();
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
