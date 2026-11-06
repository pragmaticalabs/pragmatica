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
/// target on creation), so the combiner writes nothing.
///
/// Scale-down happens only through the `REACTIVE` term falling (spec §3.1, v2713 item 2). A claim that is released or lowered (an
/// operator release) or that expires releases its claim without commanding a scale-down: the write that releases it re-bases `REACTIVE`
/// to the committed count in the same transaction ([#requirementMutations], and the combiner's removal of expired records in
/// [#combinerTransaction]), so the target holds until the autoscaler lowers `REACTIVE`, under its cooldown. The combiner removes an
/// expired record in the same transaction as the re-base, so a committed expired record always means an expiry not yet handled.
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

        /// An A/B test runs a canary on the slice's target at the fixed variant count (#1721): the combiner does not scale it. The
        /// test's terminal put re-runs the combination.
        record CanaryRunning(ArtifactBase base) implements CombinerOutcome {
            @Override
            public String message() {
                return "Slice target of " + base.asString()
                     + " runs an A/B canary at its fixed variant count; not combined";
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
    ///
    /// An expired requirement is removed in the same transaction, and while `REACTIVE` is below the committed count it is re-based to
    /// that count with it ([#reactiveRebase]): an expiry releases its claim and never commands a scale-down (spec §3.1). Fails with [CombinerOutcome.CanaryRunning] while an A/B canary runs on the target.
    static Result<LeaderTransaction<AetherKey, AetherValue>> combinerTransaction(KVStore<AetherKey, AetherValue> store,
                                                                                 ArtifactBase base,
                                                                                 long nowMs,
                                                                                 int placeableCapacity) {
        var key = SliceTargetKey.sliceTargetKey(base);

        if (canaryRunning(store, base)) {
            return new CombinerOutcome.CanaryRunning(base).result();
        }

        return SliceTargetFence.presentTarget(store, key).flatMap(target -> combinerTransaction(store,
                                                                                                key,
                                                                                                target,
                                                                                                committedRequirements(store,
                                                                                                                      base),
                                                                                                nowMs,
                                                                                                placeableCapacity));
    }

    private static Result<LeaderTransaction<AetherKey, AetherValue>> combinerTransaction(KVStore<AetherKey, AetherValue> store,
                                                                                         SliceTargetKey key,
                                                                                         SliceTargetValue target,
                                                                                         List<Requirement> requirements,
                                                                                         long nowMs,
                                                                                         int placeableCapacity) {
        var base = key.artifactBase();
        var expired = requirements.stream().filter(requirement -> !requirement.value()
                                                                              .unexpiredAt(nowMs)).toList();
        var remaining = requirements.stream().filter(requirement -> requirement.value()
                                                                               .unexpiredAt(nowMs)).toList();
        var rebase = expired.isEmpty()
                     ? Option.<SliceRequirementValue> none()
                     : reactiveRebase(store, base, target);
        var mutations = new ArrayList<Mutation<AetherKey, AetherValue>>();

        expired.stream()
               .filter(requirement -> rebase.isEmpty() || requirement.kind() != RequirementKind.REACTIVE)
               .forEach(requirement -> mutations.add(new Mutation<AetherKey, AetherValue>(SliceRequirementKey.sliceRequirementKey(base,
                                                                                                                                  requirement.kind()),
                                                                                          Option.some(requirement.value()),
                                                                                          Option.none())));
        rebase.onPresent(value -> mutations.add(requirementMutation(store,
                                                                    SliceRequirementKey.sliceRequirementKey(base,
                                                                                                            RequirementKind.REACTIVE),
                                                                    Option.some(value))));
        var combined = combine(target, withReactive(remaining, rebase), nowMs, placeableCapacity).or(target.targetInstances());

        if (combined == target.targetInstances()) {
            return mutations.isEmpty()
                   ? new CombinerOutcome.Settled(base).result()
                   : heldTargetTransaction(store, key, target, mutations);
        }

        return SliceTargetFence.targetWrite(store,
                                            key,
                                            Option.some(target),
                                            target.withInstances(combined))
                               .map(write -> new TargetWrite(write.mutation(),
                                                             withRequirementWitnesses(store,
                                                                                      base,
                                                                                      write.guards())))
                               .flatMap(write -> SliceTargetFence.transaction(store,
                                                                              List.of(write),
                                                                              mutations));
    }

    /// The requirement writes of a combination that leaves the target count as committed: witnessed on the target at `target` and on
    /// its owning blueprint, so a target that moved or was removed after the read refuses them.
    private static Result<LeaderTransaction<AetherKey, AetherValue>> heldTargetTransaction(KVStore<AetherKey, AetherValue> store,
                                                                                           SliceTargetKey key,
                                                                                           SliceTargetValue target,
                                                                                           List<Mutation<AetherKey, AetherValue>> mutations) {
        return SliceTargetFence.ownerGuards(store,
                                            key,
                                            target.owningBlueprint())
                               .flatMap(ownerGuards -> SliceTargetFence.guardedTransaction(store,
                                                                                           withTargetWitness(key,
                                                                                                             target,
                                                                                                             ownerGuards),
                                                                                           mutations));
    }

    /// `requirements` with `REACTIVE` replaced by `rebase` when present.
    private static List<Requirement> withReactive(List<Requirement> requirements,
                                                  Option<SliceRequirementValue> rebase) {
        return rebase.map(value -> Stream.concat(requirements.stream()
                                                             .filter(requirement -> requirement.kind() != RequirementKind.REACTIVE),
                                                 Stream.of(new Requirement(RequirementKind.REACTIVE, value)))
                                         .toList())
                     .or(requirements);
    }

    /// The `REACTIVE` value that holds `held`'s committed count, when the committed `REACTIVE` is below it (or absent): what a release,
    /// a lowering or an expiry of another claim writes in the same transaction, so that claim releases capacity without commanding a
    /// scale-down (spec §3.1). None when `REACTIVE` already holds the count.
    static Option<SliceRequirementValue> reactiveRebase(KVStore<AetherKey, AetherValue> store,
                                                        ArtifactBase base,
                                                        SliceTargetValue held) {
        var count = held.targetInstances();
        var reactive = store.getTyped(SliceRequirementKey.sliceRequirementKey(base, RequirementKind.REACTIVE),
                                      SliceRequirementValue.class)
                            .map(SliceRequirementValue::instances)
                            .or(0);

        return reactive >= count
               ? Option.none()
               : Option.some(SliceRequirementValue.sliceRequirementValue(count,
                                                                         "held at " + count
                                                                        + ": a claim was released or expired",
                                                                         Option.none(),
                                                                         store.committedRevision()));
    }

    /// Whether an A/B test runs a canary on `base`'s target ([AetherValue.AbTestValue#runsCanary]).
    static boolean canaryRunning(KVStore<AetherKey, AetherValue> store, ArtifactBase base) {
        var running = new boolean[1];

        store.forEach(AetherKey.AbTestKey.class,
                      AetherValue.AbTestValue.class,
                      (_, test) -> running[0] |= test.artifactBase()
                                                     .equals(base) && test.runsCanary());

        return running[0];
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
                                                                                           requirementMutations(store,
                                                                                                                observed,
                                                                                                                key,
                                                                                                                replacement)));
    }

    /// The mutations of a requirement source's write of `replacement` at `key` over the committed target `observed`: the put or
    /// removal itself and, when it releases or lowers a claim other than `REACTIVE` while `REACTIVE` is below the committed count,
    /// `REACTIVE` re-based to that count ([#reactiveRebase]). A release or a lowered floor therefore never commands a scale-down; only
    /// the autoscaler lowering `REACTIVE` does (spec §3.1).
    static List<Mutation<AetherKey, AetherValue>> requirementMutations(KVStore<AetherKey, AetherValue> store,
                                                                       SliceTargetValue observed,
                                                                       SliceRequirementKey key,
                                                                       Option<SliceRequirementValue> replacement) {
        var mutations = new ArrayList<Mutation<AetherKey, AetherValue>>();

        mutations.add(requirementMutation(store, key, replacement));
        if (releasesClaim(store, key, replacement)) {
            reactiveRebase(store, key.artifactBase(), observed).onPresent(value -> mutations.add(requirementMutation(store,
                                                                                                                     SliceRequirementKey.sliceRequirementKey(key.artifactBase(),
                                                                                                                                                             RequirementKind.REACTIVE),
                                                                                                                     Option.some(value))));
        }

        return mutations;
    }

    /// Whether writing `replacement` at `key` releases or lowers a committed claim of a source other than `REACTIVE`.
    private static boolean releasesClaim(KVStore<AetherKey, AetherValue> store,
                                         SliceRequirementKey key,
                                         Option<SliceRequirementValue> replacement) {
        return key.kind() != RequirementKind.REACTIVE && store.getTyped(key, SliceRequirementValue.class)
                                                              .map(committed -> replacement.map(value -> value.instances() < committed.instances())
                                                                                           .or(true))
                                                              .or(false);
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
        return requirementRemovals(store, base);
    }

    /// The removal of every committed requirement of `base`, each expecting the value read now: what a writer removing `base`'s target
    /// puts in the same transaction, so a requirement never outlives its target (v2713 item 3). A requirement changed after the read
    /// refuses the transaction, and the writer's rebuild reads it again.
    static List<Mutation<AetherKey, AetherValue>> requirementRemovals(KVStore<AetherKey, AetherValue> store,
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
