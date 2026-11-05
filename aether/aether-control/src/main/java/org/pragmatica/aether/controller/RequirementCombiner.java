// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.controller;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceRequirementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceRequirementValue;
import org.pragmatica.aether.slice.kvstore.SliceRequirements;
import org.pragmatica.aether.slice.kvstore.SliceRequirements.CombinerOutcome;
import org.pragmatica.aether.slice.kvstore.SliceTargetFence;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// rc6 §3.1: the leader's requirement combiner, the only writer of a committed target's `targetInstances` after its creation
/// (INV-02). It runs on the leader only (between [#activate] and [#deactivate]) and re-combines a slice on every requirement put or
/// remove, on every target put (a blueprint publish rewrites the target, so a blueprint change arrives this way), at the earliest
/// expiry of the slice's requirements, and for every slice holding a requirement when it activates (a new leader re-arms the expiry
/// timers it did not inherit).
///
/// Each write is [SliceRequirements#combinerTransaction] through [SliceTargetFence#submit]: the target CAS plus the witnesses on every
/// requirement kind mean no two runs commit from the same base, and a run whose inputs moved is rebuilt from a fresh read. The
/// evaluation time is sampled once per build at this boundary and passed in; the combination itself reads no clock (INV-09).
///
/// A slice whose target is gone (its blueprint deleted) has its requirements removed here, witnessed on the target's absence, so
/// requirements follow their target out as surely as they would on the removal paths themselves.
public interface RequirementCombiner {
    /// Starts combining: this node became the leader. Sweeps every slice holding a requirement.
    Unit activate();
    /// Stops combining: this node is no longer the leader. Cancels every expiry timer.
    Unit deactivate();
    /// A requirement of `key`'s slice was put.
    Unit onRequirementPut(SliceRequirementKey key);
    /// A requirement of `key`'s slice was removed.
    Unit onRequirementRemove(SliceRequirementKey key);
    /// The target of `key`'s slice was put (a creation, a republish, a version change or the combiner's own write).
    Unit onSliceTargetPut(SliceTargetKey key);
    /// The target of `key`'s slice was removed: its requirements are removed with it.
    Unit onSliceTargetRemove(SliceTargetKey key);

    static RequirementCombiner requirementCombiner(KVStore<AetherKey, AetherValue> store,
                                                   Fn1<Promise<List<Object>>, List<KVCommand<AetherKey>>> apply,
                                                   LongSupplier clock,
                                                   IntSupplier placeableCapacity) {
        return new LeaderCombiner(store,
                                  apply,
                                  clock,
                                  placeableCapacity,
                                  new AtomicBoolean(false),
                                  new ConcurrentHashMap<>());
    }

    record LeaderCombiner(KVStore<AetherKey, AetherValue> store,
                          Fn1<Promise<List<Object>>, List<KVCommand<AetherKey>>> apply,
                          LongSupplier clock,
                          IntSupplier placeableCapacity,
                          AtomicBoolean active,
                          ConcurrentHashMap<ArtifactBase, ScheduledFuture<?>> expiryTimers) implements RequirementCombiner {
        private static final Logger log = LoggerFactory.getLogger(RequirementCombiner.class);
        private static final TimeSpan NOW = TimeSpan.timeSpan(0).millis();

        @Override
        public Unit activate() {
            active.set(true);
            requirementBases().forEach(this::schedule);

            return Unit.unit();
        }

        @Override
        public Unit deactivate() {
            active.set(false);
            expiryTimers.values().forEach(timer -> timer.cancel(false));
            expiryTimers.clear();

            return Unit.unit();
        }

        @Override
        public Unit onRequirementPut(SliceRequirementKey key) {
            return schedule(key.artifactBase());
        }

        @Override
        public Unit onRequirementRemove(SliceRequirementKey key) {
            return schedule(key.artifactBase());
        }

        @Override
        public Unit onSliceTargetPut(SliceTargetKey key) {
            return schedule(key.artifactBase());
        }

        @Override
        public Unit onSliceTargetRemove(SliceTargetKey key) {
            return schedule(key.artifactBase());
        }

        /// The evaluation runs off the notifying thread: it reads the store and submits a write.
        private Unit schedule(ArtifactBase base) {
            if (active.get()) {
                SharedScheduler.schedule(() -> evaluate(base), NOW);
            }

            return Unit.unit();
        }

        private Set<ArtifactBase> requirementBases() {
            var bases = new HashSet<ArtifactBase>();

            store.forEach(SliceRequirementKey.class,
                          SliceRequirementValue.class,
                          (key, _) -> bases.add(key.artifactBase()));

            return bases;
        }

        @Contract
        private void evaluate(ArtifactBase base) {
            if (!active.get()) {
                return;
            }

            SliceTargetFence.committedTarget(store,
                                             SliceTargetKey.sliceTargetKey(base))
                            .onPresent(_ -> combine(base))
                            .onEmpty(() -> removeOrphans(base));
        }

        @Contract
        private void combine(ArtifactBase base) {
            SliceTargetFence.submit("combine " + base.asString(),
                                    apply,
                                    () -> SliceRequirements.combinerTransaction(store,
                                                                                base,
                                                                                clock.getAsLong(),
                                                                                placeableCapacity.getAsInt()))
                            .onSuccess(_ -> log.info("Combined the requirements of {} into its target",
                                                     base.asString()))
                            .onFailure(cause -> notWritten(base, cause));
            rearmExpiry(base);
        }

        /// The next evaluation of `base` at the earliest expiry of its requirements, replacing any armed before.
        @Contract
        private void rearmExpiry(ArtifactBase base) {
            var now = clock.getAsLong();

            SliceRequirements.earliestExpiry(SliceRequirements.committedRequirements(store, base),
                                             now)
                             .onPresent(expiry -> arm(base,
                                                      SharedScheduler.schedule(() -> evaluate(base),
                                                                               TimeSpan.timeSpan(expiry - now).millis())))
                             .onEmpty(() -> disarm(base));
        }

        @Contract
        private void arm(ArtifactBase base, ScheduledFuture<?> timer) {
            Option.option(expiryTimers.put(base, timer)).onPresent(previous -> previous.cancel(false));
        }

        @Contract
        private void disarm(ArtifactBase base) {
            Option.option(expiryTimers.remove(base)).onPresent(previous -> previous.cancel(false));
        }

        @Contract
        private void removeOrphans(ArtifactBase base) {
            disarm(base);
            SliceTargetFence.submit("remove requirements of " + base.asString(),
                                    apply,
                                    () -> SliceRequirements.orphanRemovalTransaction(store, base))
                            .onSuccess(_ -> log.info("Removed the requirements of {}: its target is gone",
                                                     base.asString()))
                            .onFailure(cause -> notWritten(base, cause));
        }

        /// FER (degrade forward): a combination or removal that was not written is logged, and nothing else is done. Guarantee earned:
        /// the committed state is never left derived from a stale read. Mechanism: every change that could make the write necessary
        /// again (a requirement or target put or remove, an expiry, a new leader's sweep) schedules a fresh evaluation, which reads
        /// the committed state anew. A [CombinerOutcome] is the postcondition already holding, not a failure.
        @Contract
        private void notWritten(ArtifactBase base, Cause cause) {
            if (cause instanceof CombinerOutcome) {
                log.debug("Requirement combination of {}: {}", base.asString(), cause.message());
            } else {
                log.warn("Requirement combination of {} not written: {}", base.asString(), cause.message());
            }
        }
    }
}
