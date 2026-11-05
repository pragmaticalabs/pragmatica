// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.slice.blueprint.SliceSpec;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceRequirementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.RequirementKind;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceRequirementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.SliceTargetFence.TargetWrite;
import org.pragmatica.cluster.state.kvstore.KVCommand.LeaderTransaction;
import org.pragmatica.cluster.state.kvstore.KVCommand.Mutation;
import org.pragmatica.cluster.state.kvstore.KVCommand.ReadWitness;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// rc6 §3.1: the slice capacity-requirement model. Every requirement source commits its own [SliceRequirementKey]; the combiner
/// derives the committed target count from them:
///
/// `target = clamp(max(minInstances, every unexpired requirement), 3, maxInstances, placeableCapacity)`
///
/// The evaluation is a pure function of its inputs (INV-09): the evaluation time and the placeable capacity are parameters, nothing
/// reads a clock, and the requirements are read and folded in [RequirementKind] order, so the input order never changes the result.
/// The lower bound of 3 ([SliceSpec#MIN_INSTANCES]) wins over every upper bound (INV-12). `max` combines alternative estimates of
/// the same demand; it never sums them.
///
/// With no unexpired requirement there is nothing to combine: the committed count stands (the blueprint's declared count seeds a
/// target on creation, and an expiring or released requirement releases its claim without commanding a scale-down), so the combiner
/// writes nothing.
public sealed interface SliceRequirements {
    /// One committed requirement of a slice, typed by its source.
    record Requirement(RequirementKind kind, SliceRequirementValue value) {}

    /// Why the combiner did not write a target. Nothing was written in any of these cases.
    sealed interface CombinerOutcome extends Cause {
        /// The committed count already equals the combined count, or no unexpired requirement exists (the committed count
        /// stands): the target needs no write.
        record Settled(ArtifactBase base) implements CombinerOutcome {
            @Override
            public String message() {
                return "Slice target of " + base.asString() + " already holds the combined requirement count";
            }
        }

        /// No requirement of the base is orphaned: it holds none, or its target is committed.
        record NoOrphans(ArtifactBase base) implements CombinerOutcome {
            @Override
            public String message() {
                return "Slice requirements of " + base.asString() + " are not orphaned; none was removed";
            }
        }
    }

    /// The combined count of `target` and `requirements` at `nowMs`, or none when no requirement is unexpired at `nowMs`.
    static Option<Integer> combine(SliceTargetValue target,
                                   List<Requirement> requirements,
                                   long nowMs,
                                   int placeableCapacity) {
        return Option.from(unexpired(requirements, nowMs).max(Integer::compare)).map(demand -> clamp(Math.max(target.effectiveMinInstances(),
                                                                                                              demand),
                                                                                                     target.maxInstances(),
                                                                                                     placeableCapacity));
    }

    /// The instance counts of the requirements still in force at `nowMs`: an absent expiry never expires, a present one expires at its instant.
    private static Stream<Integer> unexpired(List<Requirement> requirements, long nowMs) {
        return requirements.stream()
                           .filter(requirement -> requirement.value()
                                                             .expiresAt()
                                                             .map(expiry -> expiry > nowMs)
                                                             .or(true))
                           .map(requirement -> requirement.value()
                                                          .instances());
    }

    /// `demand` bounded above by `maxInstances` and `placeableCapacity`, then below by [SliceSpec#MIN_INSTANCES], which wins.
    static int clamp(int demand, Option<Integer> maxInstances, int placeableCapacity) {
        var upper = maxInstances.map(max -> Math.min(max, placeableCapacity)).or(placeableCapacity);

        return Math.max(SliceSpec.MIN_INSTANCES, Math.min(demand, upper));
    }

    /// The earliest expiry later than `nowMs` among `requirements`: the time the combination next changes without a write.
    static Option<Long> earliestExpiry(List<Requirement> requirements, long nowMs) {
        return Option.from(requirements.stream()
                                       .flatMap(requirement -> requirement.value()
                                                                          .expiresAt()
                                                                          .stream())
                                       .filter(expiry -> expiry > nowMs)
                                       .min(Long::compare));
    }

    /// The committed requirements of `base`, in [RequirementKind] order.
    static List<Requirement> committedRequirements(KVStore<AetherKey, AetherValue> store, ArtifactBase base) {
        return RequirementKind.sources()
                              .stream()
                              .flatMap(kind -> committedRequirement(store, base, kind).stream())
                              .sorted(Comparator.comparing(Requirement::kind))
                              .toList();
    }

    private static Option<Requirement> committedRequirement(KVStore<AetherKey, AetherValue> store,
                                                            ArtifactBase base,
                                                            RequirementKind kind) {
        return store.getTyped(SliceRequirementKey.sliceRequirementKey(base, kind),
                              SliceRequirementValue.class)
                    .map(value -> new Requirement(kind, value));
    }

    /// The combined count the committed state of `base` yields at `nowMs`: the committed count when no requirement is unexpired.
    /// Fails with [SliceTargetFence.SliceTargetFenceError.TargetAbsent] when no target is committed.
    static Result<Integer> committedCombination(KVStore<AetherKey, AetherValue> store,
                                                ArtifactBase base,
                                                long nowMs,
                                                int placeableCapacity) {
        return SliceTargetFence.presentTarget(store,
                                              SliceTargetKey.sliceTargetKey(base))
                               .map(target -> combine(target,
                                                      committedRequirements(store, base),
                                                      nowMs,
                                                      placeableCapacity).or(target.targetInstances()));
    }

    /// The combiner's write for `base`, built from a read made now: ONE leader transaction whose mutation expects the committed
    /// target and whose witnesses hold the target's owning blueprint and every requirement kind of `base` (present or absent) at the
    /// values read, so a requirement or target that moved after the read refuses the write and the rebuild combines the moved
    /// state. Fails with [CombinerOutcome.Settled] when the committed target needs no write, and with a
    /// [SliceTargetFence.SliceTargetFenceError] when no target, owner or leader is committed.
    static Result<LeaderTransaction<AetherKey, AetherValue>> combinerTransaction(KVStore<AetherKey, AetherValue> store,
                                                                                 ArtifactBase base,
                                                                                 long nowMs,
                                                                                 int placeableCapacity) {
        var key = SliceTargetKey.sliceTargetKey(base);

        return SliceTargetFence.presentTarget(store, key)
                               .flatMap(target -> combinedWrite(store, key, target, nowMs, placeableCapacity))
                               .flatMap(write -> SliceTargetFence.transaction(store,
                                                                              List.of(write),
                                                                              List.of()));
    }

    private static Result<TargetWrite> combinedWrite(KVStore<AetherKey, AetherValue> store,
                                                     SliceTargetKey key,
                                                     SliceTargetValue target,
                                                     long nowMs,
                                                     int placeableCapacity) {
        var base = key.artifactBase();

        return combine(target,
                       committedRequirements(store, base),
                       nowMs,
                       placeableCapacity).filter(combined -> combined != target.targetInstances())
                      .toResult(new CombinerOutcome.Settled(base))
                      .flatMap(combined -> SliceTargetFence.targetWrite(store,
                                                                        key,
                                                                        Option.some(target),
                                                                        target.withInstances(combined)))
                      .map(write -> new TargetWrite(write.mutation(),
                                                    withRequirementWitnesses(store,
                                                                             base,
                                                                             write.guards())));
    }

    private static List<ReadWitness<AetherKey>> withRequirementWitnesses(KVStore<AetherKey, AetherValue> store,
                                                                         ArtifactBase base,
                                                                         List<ReadWitness<AetherKey>> guards) {
        var all = new ArrayList<>(guards);

        all.addAll(requirementWitnesses(store, base));

        return all;
    }

    /// A witness per requirement kind of `base` at its committed value, absent included: a requirement put after the read refuses
    /// the write as surely as one changed or removed.
    static List<ReadWitness<AetherKey>> requirementWitnesses(KVStore<AetherKey, AetherValue> store, ArtifactBase base) {
        return RequirementKind.sources()
                              .stream()
                              .map(kind -> requirementWitness(store,
                                                              SliceRequirementKey.sliceRequirementKey(base, kind)))
                              .toList();
    }

    private static ReadWitness<AetherKey> requirementWitness(KVStore<AetherKey, AetherValue> store,
                                                             SliceRequirementKey key) {
        return new ReadWitness<>(key,
                                 store.get(key).map(Object.class::cast));
    }

    /// The put (`replacement` present) or removal (absent) of the requirement at `key`, expecting the value committed now.
    static Mutation<AetherKey, AetherValue> requirementMutation(KVStore<AetherKey, AetherValue> store,
                                                                SliceRequirementKey key,
                                                                Option<SliceRequirementValue> replacement) {
        return new Mutation<>(key, store.get(key), replacement.map(AetherValue.class::cast));
    }

    /// A requirement source's write decided against `observed`, the committed target of the requirement's base: ONE leader transaction
    /// putting `replacement` at `key` (removing it when absent), witnessing the target at `observed` and the target's owning blueprint at
    /// its committed value. A target removed with its blueprint, or moved by a republish or by the combiner, refuses the write, and so
    /// does a blueprint deleted or republished under it, so a requirement is never committed for a target that is gone or orphaned
    /// (#2662's discipline). Fails with [SliceTargetFence.SliceTargetFenceError.OrphanTarget] when the owner is not committed now, and
    /// with [SliceTargetFence.SliceTargetFenceError.NoCommittedLeader] when no leader is.
    static Result<LeaderTransaction<AetherKey, AetherValue>> requirementTransaction(KVStore<AetherKey, AetherValue> store,
                                                                                    SliceTargetValue observed,
                                                                                    SliceRequirementKey key,
                                                                                    Option<SliceRequirementValue> replacement) {
        var targetKey = SliceTargetKey.sliceTargetKey(key.artifactBase());

        return SliceTargetFence.ownerGuards(store,
                                            targetKey,
                                            observed.owningBlueprint())
                               .flatMap(ownerGuards -> SliceTargetFence.guardedTransaction(store,
                                                                                           withTargetWitness(targetKey,
                                                                                                             observed,
                                                                                                             ownerGuards),
                                                                                           List.of(requirementMutation(store,
                                                                                                                       key,
                                                                                                                       replacement))));
    }

    private static List<ReadWitness<AetherKey>> withTargetWitness(SliceTargetKey targetKey,
                                                                  SliceTargetValue observed,
                                                                  List<ReadWitness<AetherKey>> ownerGuards) {
        var all = new ArrayList<ReadWitness<AetherKey>>();

        all.add(new ReadWitness<>(targetKey, Option.some(observed)));
        all.addAll(ownerGuards);

        return all;
    }

    /// The removal of every committed requirement of `base`, for a base whose target is gone: each mutation expects the value read,
    /// and the witness holds the target absent, so a target re-created after the read refuses the removal. Fails with
    /// [CombinerOutcome.NoOrphans] when `base` holds no requirement, or its target is committed.
    static Result<LeaderTransaction<AetherKey, AetherValue>> orphanRemovalTransaction(KVStore<AetherKey, AetherValue> store,
                                                                                      ArtifactBase base) {
        var targetKey = SliceTargetKey.sliceTargetKey(base);
        var removals = orphanRemovals(store, base);

        return SliceTargetFence.committedTarget(store, targetKey).isEmpty() && !removals.isEmpty()
               ? SliceTargetFence.guardedTransaction(store,
                                                     List.of(new ReadWitness<AetherKey>(targetKey, Option.none())),
                                                     removals)
               : new CombinerOutcome.NoOrphans(base).result();
    }

    private static List<Mutation<AetherKey, AetherValue>> orphanRemovals(KVStore<AetherKey, AetherValue> store,
                                                                         ArtifactBase base) {
        return committedRequirements(store, base).stream()
                                    .map(requirement -> new Mutation<AetherKey, AetherValue>(SliceRequirementKey.sliceRequirementKey(base,
                                                                                                                                     requirement.kind()),
                                                                                             Option.some(requirement.value()),
                                                                                             Option.none()))
                                    .toList();
    }

    record unused() implements SliceRequirements {}
}
