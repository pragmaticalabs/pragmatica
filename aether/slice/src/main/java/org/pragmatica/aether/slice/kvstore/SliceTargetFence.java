// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVCommand.LeaderTransaction;
import org.pragmatica.cluster.state.kvstore.KVCommand.Mutation;
import org.pragmatica.cluster.state.kvstore.KVCommand.ReadWitness;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// #2662: the fenced read-modify-write of a `SliceTarget` by a writer outside the leader's blueprint path.
///
/// A writer that read a target, derived a new value from it and issued a plain `Put` could commit after the owning blueprint was deleted
/// and its target removed (the put re-created the target, owned by a blueprint that no longer exists), or after a republish moved the
/// target (the derived value reverted it). Each write is now ONE [LeaderTransaction], as `RollbackManager` writes the rollback: every target
/// mutation expects exactly the value the writer read, and a target that names an owner carries a [ReadWitness] on that owner's
/// `AppBlueprintKey` at the value committed when the write was built. The applier refuses the transaction if either moved, whatever the
/// commit order.
///
/// A refused transaction is rebuilt from a fresh read ([#submit]), at most [#MAX_REBUILDS] times, as the blueprint writers of #2634 are.
/// A writer whose value is derived from an observed target (the autoscaler) rebuilds only while that target is unchanged, else it drops the
/// write ([SliceTargetFenceError.TargetMoved]).
/// A rebuild that finds the target gone, or its owner gone, fails with a [SliceTargetFenceError] instead of writing: the write is never
/// dropped silently, and never re-creates what a delete removed.
public sealed interface SliceTargetFence {
    /// Rebuilds after the first refused submission: the first submission and 5 rebuilds, the budget of #2634's writers.
    int MAX_REBUILDS = 5;

    /// One target's share of a transaction: the mutation that expects the value read, and the witness on its owner if it has one.
    record TargetWrite(Mutation<AetherKey, AetherValue> mutation, List<ReadWitness<AetherKey>> guards) {
        public TargetWrite {
            guards = List.copyOf(guards);
        }
    }

    /// Why a fenced target write was not made. Nothing was written in any of these cases.
    sealed interface SliceTargetFenceError extends Cause {
        /// No core leader is committed (an election is in progress), so no leader transaction can be authorized.
        enum NoCommittedLeader implements SliceTargetFenceError {
            INSTANCE;
            @Override
            public String message() {
                return "No core leader is committed yet; the slice target was not written. Retry shortly.";
            }
        }

        /// The target is not committed: it was removed (its blueprint deleted) or was never written. A fenced writer does not create it.
        record TargetAbsent(ArtifactBase base) implements SliceTargetFenceError {
            @Override
            public String message() {
                return "Slice target of " + base.asString()
                     + " is not committed (removed, or not written yet); it was not written";
            }
        }

        /// The target names an owning blueprint that is not committed: the target is an orphan, and writing it would keep it alive.
        record OrphanTarget(ArtifactBase base, BlueprintId owner) implements SliceTargetFenceError {
            @Override
            public String message() {
                return "Slice target of " + base.asString()
                     + " is owned by blueprint " + owner.asString()
                     + ", which is not committed; it was not written";
            }
        }

        /// The target is not committed and the blueprint that would own the created target is not committed either: creating it would
        /// leave a target no blueprint declares.
        record BlueprintAbsent(ArtifactBase base, BlueprintId blueprint) implements SliceTargetFenceError {
            @Override
            public String message() {
                return "Slice target of " + base.asString()
                     + " is not committed and blueprint " + blueprint.asString()
                     + " is not committed either; the target was not created";
            }
        }

        /// The target moved under a write whose value was derived from the target it observed (the autoscaler's). Rebuilding that value
        /// against the moved target would let a decision made against a stale input win, so the write is dropped; the next evaluation
        /// decides from the committed target.
        record TargetMoved(ArtifactBase base) implements SliceTargetFenceError {
            @Override
            public String message() {
                return "Slice target of " + base.asString()
                     + " moved after the write was decided; the decision was dropped and the next evaluation re-decides from the committed target";
            }
        }

        /// Every submission was refused because the target or its owner kept changing under the write.
        record Contended(String write, int submissions) implements SliceTargetFenceError {
            @Override
            public String message() {
                return "Slice-target write " + write
                     + " was refused " + submissions
                     + " times by concurrent changes to the target or its blueprint and was NOT written";
            }
        }
    }

    /// The committed target of the key, typed.
    static Option<SliceTargetValue> committedTarget(KVStore<AetherKey, AetherValue> store, SliceTargetKey key) {
        return store.get(key)
                    .filter(SliceTargetValue.class::isInstance)
                    .map(SliceTargetValue.class::cast);
    }

    /// The committed target of the key, or [SliceTargetFenceError.TargetAbsent]: the writers that only modify a target never create one.
    static Result<SliceTargetValue> presentTarget(KVStore<AetherKey, AetherValue> store, SliceTargetKey key) {
        return committedTarget(store, key).toResult(new SliceTargetFenceError.TargetAbsent(key.artifactBase()));
    }

    /// The write of `replacement` over `read`. The mutation expects `read` exactly. When `replacement` names an owner, the owner's blueprint
    /// must be committed now, and the write carries a witness on it at that value; an owner that is not committed fails with
    /// [SliceTargetFenceError.OrphanTarget].
    static Result<TargetWrite> targetWrite(KVStore<AetherKey, AetherValue> store,
                                           SliceTargetKey key,
                                           Option<SliceTargetValue> read,
                                           SliceTargetValue replacement) {
        var mutation = new Mutation<AetherKey, AetherValue>(key,
                                                            read.map(AetherValue.class::cast),
                                                            Option.some(replacement));

        return ownerGuards(store, key, replacement.owningBlueprint()).map(guards -> new TargetWrite(mutation, guards));
    }

    /// The creation of `created` where no target is committed. The mutation expects the target absent, and the write carries a witness on
    /// `blueprint` at its committed value, so a create racing the blueprint's delete, or its own target put, is refused. A blueprint that
    /// is not committed fails with [SliceTargetFenceError.BlueprintAbsent]: nothing would declare the created target.
    static Result<TargetWrite> createWrite(KVStore<AetherKey, AetherValue> store,
                                           SliceTargetKey key,
                                           BlueprintId blueprint,
                                           SliceTargetValue created) {
        var blueprintKey = AppBlueprintKey.appBlueprintKey(blueprint);
        var mutation = new Mutation<AetherKey, AetherValue>(key, Option.none(), Option.some(created));

        return store.get(blueprintKey)
                    .toResult(new SliceTargetFenceError.BlueprintAbsent(key.artifactBase(),
                                                                        blueprint))
                    .map(committed -> new TargetWrite(mutation,
                                                      List.of(new ReadWitness<AetherKey>(blueprintKey,
                                                                                         Option.some(committed)))));
    }

    /// The witness on `owner`'s blueprint at its committed value (none when `owner` is absent), or
    /// [SliceTargetFenceError.OrphanTarget] when the owner is not committed: a write must not keep an orphan alive.
    static Result<List<ReadWitness<AetherKey>>> ownerGuards(KVStore<AetherKey, AetherValue> store,
                                                            SliceTargetKey key,
                                                            Option<BlueprintId> owner) {
        return owner.fold(() -> Result.success(List.of()),
                          id -> ownerGuard(store, key, id));
    }

    private static Result<List<ReadWitness<AetherKey>>> ownerGuard(KVStore<AetherKey, AetherValue> store,
                                                                   SliceTargetKey key,
                                                                   BlueprintId owner) {
        var blueprintKey = AppBlueprintKey.appBlueprintKey(owner);

        return store.get(blueprintKey)
                    .toResult(new SliceTargetFenceError.OrphanTarget(key.artifactBase(),
                                                                     owner))
                    .map(committed -> List.of(new ReadWitness<AetherKey>(blueprintKey,
                                                                         Option.some(committed))));
    }

    /// The leader transaction holding the target writes and any other mutations that must commit with them, authorized by the committed
    /// leader. Fails with [SliceTargetFenceError.NoCommittedLeader] when none is committed.
    static Result<LeaderTransaction<AetherKey, AetherValue>> transaction(KVStore<AetherKey, AetherValue> store,
                                                                         List<TargetWrite> writes,
                                                                         List<Mutation<AetherKey, AetherValue>> others) {
        return store.getTyped(LeaderKey.INSTANCE, LeaderValue.class)
                    .toResult(SliceTargetFenceError.NoCommittedLeader.INSTANCE)
                    .map(leader -> transaction(leader, writes, others));
    }

    /// The leader transaction holding `mutations` under `guards`, authorized by the committed leader: the shape of a write that
    /// mutates no target (the combiner's removal of an orphan's requirements). Fails with [SliceTargetFenceError.NoCommittedLeader]
    /// when none is committed.
    static Result<LeaderTransaction<AetherKey, AetherValue>> guardedTransaction(KVStore<AetherKey, AetherValue> store,
                                                                                List<ReadWitness<AetherKey>> guards,
                                                                                List<Mutation<AetherKey, AetherValue>> mutations) {
        return store.getTyped(LeaderKey.INSTANCE, LeaderValue.class)
                    .toResult(SliceTargetFenceError.NoCommittedLeader.INSTANCE)
                    .map(leader -> new LeaderTransaction<>(mutations.getFirst().key(),
                                                           UUID.randomUUID().toString(),
                                                           leader,
                                                           List.copyOf(guards),
                                                           List.copyOf(mutations)));
    }

    private static LeaderTransaction<AetherKey, AetherValue> transaction(LeaderValue leader,
                                                                         List<TargetWrite> writes,
                                                                         List<Mutation<AetherKey, AetherValue>> others) {
        var mutations = new ArrayList<Mutation<AetherKey, AetherValue>>();
        var guards = new ArrayList<ReadWitness<AetherKey>>();

        writes.forEach(write -> mutations.add(write.mutation()));
        mutations.addAll(others);
        writes.forEach(write -> addDistinct(guards, write.guards()));

        return new LeaderTransaction<>(mutations.getFirst().key(),
                                       UUID.randomUUID().toString(),
                                       leader,
                                       guards,
                                       mutations);
    }

    private static void addDistinct(List<ReadWitness<AetherKey>> guards, List<ReadWitness<AetherKey>> more) {
        more.stream().filter(guard -> !guards.contains(guard)).forEach(guards::add);
    }

    /// Submits the transaction `build` makes and, while the applier refuses it, rebuilds it from a fresh read and submits again, at most
    /// [#MAX_REBUILDS] times. `build` is the writer's whole read-modify step: it reads what it derives from on every call, so a rebuild
    /// is decided against the committed state and fails (writing nothing) when the target or its owner is gone. A refusal of the last
    /// allowed submission fails with [SliceTargetFenceError.Contended]. A failed apply (consensus could not commit it) fails with its cause.
    static Promise<Unit> submit(String write,
                                Fn1<Promise<List<Object>>, List<KVCommand<AetherKey>>> apply,
                                Fn0<Result<LeaderTransaction<AetherKey, AetherValue>>> build) {
        return submit(write, apply, build, build);
    }

    /// As [#submit(String, Fn1, Fn0)], for a writer whose first submission derives from a value it already holds (the autoscaler's observed
    /// target) rather than from a read made now: `first` builds the first submission, and `rebuild` every later one from a fresh read.
    static Promise<Unit> submit(String write,
                                Fn1<Promise<List<Object>>, List<KVCommand<AetherKey>>> apply,
                                Fn0<Result<LeaderTransaction<AetherKey, AetherValue>>> first,
                                Fn0<Result<LeaderTransaction<AetherKey, AetherValue>>> rebuild) {
        return submit(write, apply, first, rebuild, 0);
    }

    private static Promise<Unit> submit(String write,
                                        Fn1<Promise<List<Object>>, List<KVCommand<AetherKey>>> apply,
                                        Fn0<Result<LeaderTransaction<AetherKey, AetherValue>>> build,
                                        Fn0<Result<LeaderTransaction<AetherKey, AetherValue>>> rebuild,
                                        int rebuilds) {
        return build.apply()
                    .async()
                    .flatMap(transaction -> apply.apply(List.of(transaction))
                                                 .flatMap(results -> outcome(write,
                                                                             apply,
                                                                             rebuild,
                                                                             rebuilds,
                                                                             accepted(results,
                                                                                      transaction.transactionId()))));
    }

    private static Promise<Unit> outcome(String write,
                                         Fn1<Promise<List<Object>>, List<KVCommand<AetherKey>>> apply,
                                         Fn0<Result<LeaderTransaction<AetherKey, AetherValue>>> rebuild,
                                         int rebuilds,
                                         boolean accepted) {
        if (accepted) {
            return Promise.unitPromise();
        }

        return rebuilds >= MAX_REBUILDS
               ? new SliceTargetFenceError.Contended(write, rebuilds + 1).promise()
               : submit(write, apply, rebuild, rebuild, rebuilds + 1);
    }

    /// Whether the results of an apply hold the acceptance of this transaction. Correlation survives batch merging (`TransactionResult`).
    static boolean accepted(List<?> results, String transactionId) {
        return results.stream()
                      .anyMatch(result -> result instanceof KVCommand.TransactionResult outcome
                                          && outcome.transactionId()
                                                    .equals(transactionId)
                                          && outcome.accepted());
    }

    record unused() implements SliceTargetFence {}
}
