// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.WorkflowClaimKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.WorkflowRunKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowClaimValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowRunValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVCommand.LeaderTransaction;
import org.pragmatica.cluster.state.kvstore.KVCommand.Mutation;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;


/// rc6 §4.2/§4.3: the committed workflow state and the only two ways to change it, each ONE leader transaction.
///
/// - [#create] writes the run AND claims every conflict key, each mutation expecting the key ABSENT. The applier accepts all of it
///   or none of it, so at most one admitted workflow holds a given key at a time: the claim is consensus-committed, and a second
///   admission whose keys overlap is refused whole (it claims none of its keys).
/// - [#commit] replaces the run by compare-and-set on the EXACT run read; when the next state is `DONE` or `FAILED` the same
///   transaction deletes every claim, each expecting this run as its holder, so a claim is released exactly when its run ends.
public interface WorkflowLedger {
    /// Every committed run, oldest first.
    List<WorkflowRunValue> runs();
    /// The committed claim of `conflictKey`.
    Option<WorkflowClaimValue> claim(String conflictKey);
    /// Whether this node is the committed leader, the only node whose transactions are submitted.
    boolean leads();
    /// The admission transaction for `run`: `true` only when it was accepted.
    Promise<Boolean> create(WorkflowRunValue run);
    /// The compare-and-set of `expected` to `next` (with the claims released when `next` ends the run): `true` only when accepted.
    Promise<Boolean> commit(WorkflowRunValue expected, WorkflowRunValue next);

    /// The mutations of an admission: the run, then one claim per conflict key, every one expecting its key absent.
    static List<Mutation<AetherKey, AetherValue>> admission(WorkflowRunValue run) {
        var mutations = new ArrayList<Mutation<AetherKey, AetherValue>>();

        mutations.add(new Mutation<>(new WorkflowRunKey(run.id()), Option.none(), Option.some(run)));
        run.conflictKeys()
           .forEach(key -> mutations.add(new Mutation<>(new WorkflowClaimKey(key),
                                                        Option.none(),
                                                        Option.some(new WorkflowClaimValue(run.id())))));

        return List.copyOf(mutations);
    }

    /// The mutations of a transition: the run by compare-and-set, then, when `next` ends the run, the deletion of every claim
    /// expecting this run as its holder.
    static List<Mutation<AetherKey, AetherValue>> transition(WorkflowRunValue expected, WorkflowRunValue next) {
        var mutations = new ArrayList<Mutation<AetherKey, AetherValue>>();

        mutations.add(new Mutation<>(new WorkflowRunKey(expected.id()), Option.some(expected), Option.some(next)));
        if (next.releasesClaims()) {
            expected.conflictKeys()
                    .forEach(key -> mutations.add(new Mutation<>(new WorkflowClaimKey(key),
                                                                 Option.some(new WorkflowClaimValue(expected.id())),
                                                                 Option.none())));
        }

        return List.copyOf(mutations);
    }

    /// The ledger over the node's KV store. `runs` is the run index fed by the store's `WorkflowRunKey` put/remove route; `apply`
    /// submits commands to consensus and answers their results; `self` is this node, which submits only while the committed leader
    /// record names it (the applier refuses a transaction of a stale leader anyway).
    static WorkflowLedger workflowLedger(KVStore<AetherKey, AetherValue> store,
                                         WorkflowRunIndex runs,
                                         NodeId self,
                                         Fn1<Promise<List<Object>>, List<KVCommand<AetherKey>>> apply) {
        record ledger(KVStore<AetherKey, AetherValue> store,
                      WorkflowRunIndex index,
                      NodeId self,
                      Fn1<Promise<List<Object>>, List<KVCommand<AetherKey>>> apply) implements WorkflowLedger {
            @Override
            public List<WorkflowRunValue> runs() {
                return index.runs();
            }

            @Override
            public Option<WorkflowClaimValue> claim(String conflictKey) {
                return store.getTyped(new WorkflowClaimKey(conflictKey), WorkflowClaimValue.class);
            }

            @Override
            public boolean leads() {
                return leader().isPresent();
            }

            @Override
            public Promise<Boolean> create(WorkflowRunValue run) {
                return submit(new WorkflowRunKey(run.id()), admission(run));
            }

            @Override
            public Promise<Boolean> commit(WorkflowRunValue expected, WorkflowRunValue next) {
                return submit(new WorkflowRunKey(expected.id()), transition(expected, next));
            }

            private Option<LeaderValue> leader() {
                return store.getTyped(LeaderKey.INSTANCE, LeaderValue.class)
                            .filter(value -> value.leader()
                                                  .equals(self));
            }

            private Promise<Boolean> submit(WorkflowRunKey key, List<Mutation<AetherKey, AetherValue>> mutations) {
                return leader().fold(() -> Promise.success(false),
                                     value -> submit(new LeaderTransaction<>(key,
                                                                             UUID.randomUUID().toString(),
                                                                             value,
                                                                             List.of(),
                                                                             mutations)));
            }

            private Promise<Boolean> submit(LeaderTransaction<AetherKey, AetherValue> transaction) {
                return apply.apply(List.of(transaction))
                            .map(results -> accepted(results,
                                                     transaction.transactionId()));
            }

            private static boolean accepted(List<Object> results, String transactionId) {
                return results.stream()
                              .anyMatch(result -> result instanceof KVCommand.TransactionResult outcome
                                                  && outcome.transactionId()
                                                            .equals(transactionId)
                                                  && outcome.accepted());
            }
        }

        return new ledger(store, runs, self, apply);
    }
}
