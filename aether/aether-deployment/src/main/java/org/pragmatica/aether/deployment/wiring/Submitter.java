// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.util.HashMap;
import java.util.List;
import java.util.function.Supplier;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterEffect;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput.Settlement;
import org.pragmatica.aether.deployment.node.machine.NodeFsmInput.WriteOutcome;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVCommand.TransactionResult;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.ConsensusError;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;


/// The write path of both deployment FSM interpreters (phase-3 design 4.2, 5.2): `ClusterNode.apply(List)` on the switchable cluster
/// (`RabiaNode` on a core, `ForwardingClusterNode` on a worker), the outcome-to-settlement mapping, and `reRead`.
///
/// **One `apply` per step.** The caller hands over every write of one FSM step; they leave in one `apply(List)`. An empty step never
/// calls `apply` (an empty batch would fail with `CommandBatchIsEmpty`). `[guess: taste; one apply per Submit is equally correct. Settled
/// by E-01 rounds against spec 7.1.]`
///
/// **Settlement of the cluster FSM (CI-14).** Results are matched on `transactionId`, never by position: consensus batch merging makes the
/// returned list unattributable by index.
///
/// | Outcome | Settlement |
/// |---|---|
/// | `TransactionResult(corr, true)` | `Accepted` |
/// | `TransactionResult(corr, false)` | `Refused(reRead(tx))` |
/// | `corr` absent from the results | `Failed("unattributed")` |
/// | a failed `apply`: `QuorumPaused`, `NodeInactive`, `BackpressureExceeded`, `CommandBatchIsEmpty`, `submitGuard`, the `registerBatch` refusal | `Failed(cause)` |
/// | an unknown outcome: `ApplyTimeout`, `SnapshotOutcomeUnknown` | `Failed(cause)`; the FSM re-derives, and the guards protect a late commit |
///
/// **`reRead(tx)`** is the one function behind both the completion path (on the apply thread, right after this batch applied) and the
/// pre-submit self-check (on the consumer): the committed `LeaderValue` plus the committed value of **every key in `tx.mutations` and every
/// key in `tx.guards`**. A set missing a guard key misclassifies the Outbox's moved/stayed split (the v-2174 F5 class).
///
/// **Pre-submit self-check (R-5.7.1 (a)).** Before calling `apply`, the committed `LeaderValue` is read. If it differs from the
/// `tx.leader()` of any transaction, nothing is submitted: each withheld Submit is settled `Refused(reRead(tx))`, and `witnessStale` is
/// set if the committed leader names another node.
///
/// **Absorption, named.** An `apply` failure is turned into a `Failed` settlement, which is translation, not absorption: the cause's
/// text travels in the settlement and the FSM decides (retry, re-derive, give up). No failure is dropped here.
public final class Submitter {
    /// What the interpreter reads from the committed mirror: the leader record and any value, from the same store.
    public interface CommittedReader {
        Option<LeaderValue> leader();
        Option<AetherValue> value(AetherKey key);

        static CommittedReader committedReader(KVStore<AetherKey, AetherValue> store) {
            return new CommittedReader() {
                @Override
                public Option<LeaderValue> leader() {
                    return store.getTyped(LeaderKey.INSTANCE, LeaderValue.class);
                }

                @Override
                public Option<AetherValue> value(AetherKey key) {
                    return store.get(key);
                }
            };
        }
    }

    /// One cluster `Submit` after settlement, echoing the correlation and the incarnation it was submitted in.
    public record Settled(String correlationId, long incarnation, Settlement settlement) {}

    /// What became of a step's cluster writes.
    public sealed interface Dispatch {
        /// Nothing to write.
        record Nothing() implements Dispatch {}

        /// The self-check withheld the writes; they are settled already. `witnessStale` asks for a `WitnessStale` input.
        record Withheld(List<Settled> settled, boolean witnessStale) implements Dispatch {
            public Withheld {
                settled = List.copyOf(settled);
            }
        }

        /// One `apply` is in flight; it resolves with one `Settled` per submitted write.
        record Sent(Promise<List<Settled>> pending) implements Dispatch {}
    }

    private static final Settlement UNATTRIBUTED = new Settlement.Failed("unattributed");

    private final Supplier<ClusterNode<KVCommand<AetherKey>>> cluster;
    private final CommittedReader reader;

    private Submitter(Supplier<ClusterNode<KVCommand<AetherKey>>> cluster, CommittedReader reader) {
        this.cluster = cluster;
        this.reader = reader;
    }

    /// `cluster` is read at every `apply`: the node switches its cluster handle (passive client to forwarding client) after construction.
    public static Submitter submitter(Supplier<ClusterNode<KVCommand<AetherKey>>> cluster, CommittedReader reader) {
        return new Submitter(cluster, reader);
    }

    // -- node FSM ----------------------------------------------------------------------------------------------------
    /// The node FSM's write: success includes a silently fenced refusal (`VersionFenced`; the FSM confirms only on its own `ObservedPut`),
    /// any failure is `Failed(cause)`.
    public Promise<WriteOutcome> submitBatch(List<KVCommand<AetherKey>> commands) {
        return commands.isEmpty()
               ? Promise.success(WriteOutcome.failed(new ConsensusError.CommandBatchIsEmpty()))
               : applied(commands).map(_ -> WriteOutcome.accepted())
                        .recover(WriteOutcome::failed);
    }

    // -- cluster FSM -------------------------------------------------------------------------------------------------
    /// The leader's writes of one step: self-check, then one `apply(List)`.
    public Dispatch submitTransactions(List<ClusterEffect.Submit> submits) {
        if (submits.isEmpty()) {
            return new Dispatch.Nothing();
        }

        var committed = reader.leader();

        return submits.stream()
                      .allMatch(submit -> leaderMatches(submit, committed))
               ? new Dispatch.Sent(send(submits))
               : new Dispatch.Withheld(refusedAll(submits), namesAnotherNode(submits, committed));
    }

    /// The committed mirror for the keys `submit`'s transaction touches: its mutations and its guards.
    public Settlement.Refused reRead(ClusterEffect.Submit submit) {
        var values = new HashMap<AetherKey, Option<AetherValue>>();

        submit.transaction().mutations().forEach(mutation -> values.put(mutation.key(),
                                                                        reader.value(mutation.key())));
        submit.transaction().guards().forEach(guard -> values.put(guard.key(),
                                                                  reader.value(guard.key())));

        return new Settlement.Refused(reader.leader(), values);
    }

    private Promise<List<Settled>> send(List<ClusterEffect.Submit> submits) {
        return applied(submits.stream().<KVCommand<AetherKey>> map(ClusterEffect.Submit::transaction).toList()).map(results -> settled(submits,
                                                                                                                                       results))
                      .recover(cause -> failedAll(submits,
                                                  cause.message()));
    }

    private Promise<List<Object>> applied(List<KVCommand<AetherKey>> commands) {
        return cluster.get()
                      .<Object> apply(commands);
    }

    private List<Settled> settled(List<ClusterEffect.Submit> submits, List<Object> results) {
        var verdicts = new HashMap<String, Boolean>();

        results.stream()
               .filter(TransactionResult.class::isInstance)
               .map(TransactionResult.class::cast)
               .forEach(result -> verdicts.put(result.transactionId(),
                                               result.accepted()));

        return submits.stream()
                      .map(submit -> settledFrom(submit,
                                                 Option.option(verdicts.get(submit.correlationId()))))
                      .toList();
    }

    private Settled settledFrom(ClusterEffect.Submit submit, Option<Boolean> verdict) {
        return settled(submit,
                       verdict.map(accepted -> settlementOf(submit, accepted)).or(UNATTRIBUTED));
    }

    private Settlement settlementOf(ClusterEffect.Submit submit, boolean accepted) {
        return accepted
               ? new Settlement.Accepted()
               : reRead(submit);
    }

    private static List<Settled> failedAll(List<ClusterEffect.Submit> submits, String reason) {
        return submits.stream()
                      .map(submit -> settled(submit,
                                             new Settlement.Failed(reason)))
                      .toList();
    }

    private List<Settled> refusedAll(List<ClusterEffect.Submit> submits) {
        return submits.stream()
                      .map(submit -> settled(submit,
                                             reRead(submit)))
                      .toList();
    }

    private static Settled settled(ClusterEffect.Submit submit, Settlement settlement) {
        return new Settled(submit.correlationId(), submit.incarnation(), settlement);
    }

    private static boolean leaderMatches(ClusterEffect.Submit submit, Option<LeaderValue> committed) {
        return committed.map(submit.transaction().leader()::equals)
                        .or(false);
    }

    /// The committed leader names a node other than the one the transactions were built for.
    private static boolean namesAnotherNode(List<ClusterEffect.Submit> submits, Option<LeaderValue> committed) {
        return committed.map(value -> submits.stream()
                                             .anyMatch(submit -> !submit.transaction()
                                                                        .leader()
                                                                        .leader()
                                                                        .equals(value.leader())))
                        .or(false);
    }
}
