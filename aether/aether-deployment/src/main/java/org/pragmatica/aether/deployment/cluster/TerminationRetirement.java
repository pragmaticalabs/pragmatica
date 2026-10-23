// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;
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
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.Retry;
import org.pragmatica.lang.utils.Retry.BackoffStrategy;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


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
    /// node's periodic-task holder, the same stop signal its other retry loops read), and raising its overdue event to `warnings`.
    static TerminationRetirement production(KVStore<AetherKey, AetherValue> store,
                                            Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                            BooleanSupplier stopped,
                                            OperatorWarningSink warnings) {
        return directiveAndRecord(store,
                                  applier,
                                  UNTIL_RETIRED,
                                  stopped,
                                  Overdue.overdue(warnings, System::nanoTime, OVERDUE_AFTER));
    }

    /// How long a retirement may stay refused before it is an operator's concern. Under [#UNTIL_RETIRED] two minutes is about eight attempts
    /// (500 ms doubling to the 30 s cap), every one refused; a transient refusal (a leader change, a compare-and-set race) clears in one or
    /// two attempts, within seconds, and a leader election in tens of seconds. So a retirement refused for two minutes is stuck, not racing.
    TimeSpan OVERDUE_AFTER = TimeSpan.timeSpan(2).minutes();

    /// [#directiveAndRecord] that raises no overdue event.
    static TerminationRetirement directiveAndRecord(KVStore<AetherKey, AetherValue> store,
                                                    Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                                    Retry retry,
                                                    BooleanSupplier stopped) {
        return directiveAndRecord(store,
                                  applier,
                                  retry,
                                  stopped,
                                  Overdue.overdue(OperatorWarningSink.logOnly(), System::nanoTime, OVERDUE_AFTER));
    }

    /// Reads both keys and the committed leader from `store`, submits through `applier`, and retries under `retry` while the identity is the
    /// one first read and `stopped` is false, until the transaction commits or the node holds neither key. A node holding neither key writes
    /// nothing. Refused past `overdue`'s bound, it raises one overdue event, and one resolved event when it ends. The retirement runs on the
    /// one node whose authority started it (the leader's reap, or the node the operator's clear reached), so these events have one emitter.
    static TerminationRetirement directiveAndRecord(KVStore<AetherKey, AetherValue> store,
                                                    Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                                    Retry retry,
                                                    BooleanSupplier stopped,
                                                    Overdue overdue) {
        return node -> retireTheIdentityRead(store,
                                             applier,
                                             retry,
                                             stopped,
                                             node,
                                             Identity.read(store, node),
                                             overdue.watch(node));
    }

    /// What a retirement first read of the node's identity: its departure record and its directive.
    record Identity(Option<AetherValue> record, Option<AetherValue> directive) {
        static Identity read(KVStore<AetherKey, AetherValue> store, NodeId node) {
            return new Identity(store.get(new NodeDepartureKey(node)),
                                store.get(ActivationDirectiveKey.activationDirectiveKey(node)));
        }

        /// Whether a retry may go on, on what `store` holds now (CTO rulings `4c533461f`, `5ca85aa4e`):
        /// - a record present: only the DEPARTED record first read (same epoch and process incarnation);
        /// - the record absent: only while the directive is the one first read, or gone. A re-admission writes its ADMITTED record and its
        ///   directive in separate transactions (the cluster FSM's `SubmitKind.ADMISSION` draft, `Joins`, and its `SubmitKind.DIRECTIVE`
        ///   draft, `Directory`), so an absent record beside a new directive can be a re-admission whose record is not yet written.
        boolean stillHolds(KVStore<AetherKey, AetherValue> store, NodeId node) {
            return store.get(new NodeDepartureKey(node))
                        .fold(() -> isTheDirectiveFirstRead(store.get(ActivationDirectiveKey.activationDirectiveKey(node))),
                              this::isTheDepartureFirstRead);
        }

        private boolean isTheDirectiveFirstRead(Option<AetherValue> now) {
            return now.isEmpty() || now.equals(directive);
        }

        private boolean isTheDepartureFirstRead(AetherValue now) {
            return now instanceof NodeDepartureValue current
                   && current.state() == DepartureState.DEPARTED
                   && record.filter(NodeDepartureValue.class::isInstance)
                            .map(NodeDepartureValue.class::cast)
                            .filter(read -> read.epoch() == current.epoch() && read.process()
                                                                                   .equals(current.process()))
                            .isPresent();
        }
    }

    private static Promise<Unit> retireTheIdentityRead(KVStore<AetherKey, AetherValue> store,
                                                       Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                                       Retry retry,
                                                       BooleanSupplier stopped,
                                                       NodeId node,
                                                       Identity firstRead,
                                                       Overdue.Watch watch) {
        var attempts = new AtomicInteger();

        return retry.execute(() -> guardedAttempt(store,
                                                  applier,
                                                  stopped,
                                                  node,
                                                  firstRead,
                                                  attempts.getAndIncrement() == 0).onFailure(watch::refused))
                    .withResult(watch::ended);
    }

    /// An attempt unless the node is stopping, or (on a retry) the identity is no longer the one first read.
    private static Promise<Unit> guardedAttempt(KVStore<AetherKey, AetherValue> store,
                                                Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                                BooleanSupplier stopped,
                                                NodeId node,
                                                Identity firstRead,
                                                boolean first) {
        return stopped.getAsBoolean()
               ? Stopped.FACTORY.apply(node).promise()
               : attemptOnTheIdentityRead(store, applier, node, firstRead, first);
    }

    private static Promise<Unit> attemptOnTheIdentityRead(KVStore<AetherKey, AetherValue> store,
                                                          Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                                          NodeId node,
                                                          Identity firstRead,
                                                          boolean first) {
        return first || firstRead.stillHolds(store, node)
               ? attempt(store, applier, node)
               : Readmitted.FACTORY.apply(node).promise();
    }

    /// The overdue bound of a retirement and where its events go (owner rule 2026-10-03: an operator-visible condition raises one event on
    /// entering it and one on leaving it).
    ///
    /// **One emitter.** Both retirements run on the leader: the confirmed reap (B18) and the operator `--force` clear (B19) are reached only
    /// through an active topology manager (`ClusterTopologyManagerRecord.clearUnconfirmed` refuses with `NotActive` otherwise, and the manager
    /// is active only on the leader: `AetherNode.toggleCtmOnLeaderChange`). The two can still overlap on that one node for the same terminated
    /// node, so `open` holds the subjects whose overdue warning is raised: a second retirement of the same node raises nothing while the first
    /// one's warning is open. `[limit: a retirement started under an earlier leader keeps retrying after leadership moved; if the new leader
    /// starts one for the same node, each node raises its own]`.
    record Overdue(OperatorWarningSink warnings, LongSupplier clockNs, TimeSpan after, Set<NodeId> open) {
        static Overdue overdue(OperatorWarningSink warnings, LongSupplier clockNs, TimeSpan after) {
            return new Overdue(warnings, clockNs, after, ConcurrentHashMap.newKeySet());
        }

        Watch watch(NodeId node) {
            return new Watch(this, node, clockNs.getAsLong(), new AtomicBoolean());
        }

        /// One retirement's watch: raises [OperatorWarningCode#TERMINATION_RETIREMENT_OVERDUE] once, on the first refusal past the bound, and
        /// [OperatorWarningCode#TERMINATION_RETIREMENT_RESOLVED] once, when the retirement ends, only if the overdue event was raised.
        record Watch(Overdue overdue, NodeId node, long startedNs, AtomicBoolean raised) {
            private static final Logger LOG = LoggerFactory.getLogger(TerminationRetirement.class);

            @Contract
            void refused(Cause cause) {
                if (!cause.isTerminal() && overdue.clockNs().getAsLong() - startedNs >= overdue.after().nanos() && overdue.open()
                                                                                                                          .add(node) && raised.compareAndSet(false,
                                                                                                                                                             true)) {
                    OperatorWarnings.raise(LOG,
                                           overdue.warnings(),
                                           OperatorWarningCode.TERMINATION_RETIREMENT_OVERDUE,
                                           node.id(),
                                           "The directive and departure record of terminated node {} are still not retired after {}: the last attempt was refused ({}). Nothing else removes them under FSM2; the retirement keeps retrying while the record is the one first read",
                                           node.id(),
                                           overdue.after(),
                                           cause.message());
                }
            }

            @Contract
            void ended(Result<Unit> outcome) {
                if (raised.get() && overdue.open().remove(node)) {
                    OperatorWarnings.raise(LOG,
                                           overdue.warnings(),
                                           OperatorWarningCode.TERMINATION_RETIREMENT_RESOLVED,
                                           node.id(),
                                           "The retirement of the directive and departure record of node {} ended: {}",
                                           node.id(),
                                           outcome.fold(Cause::message, _ -> "it committed, or neither key was left"));
                }
            }
        }
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
