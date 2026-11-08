// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.consensus.NodeId;

/// The during-run invariant monitor (FSM replacement plan 3.3, "transient divergence"): quiescence cannot see a transient breach, so committed KV
/// is sampled every second and each sample checked against four invariants. A pure function over the recorded samples, so the planted
/// violations of the control arm run through exactly the code the real samples do.
///
/// - **(i)** per artifact, ACTIVE + LOADING + LOADED instances <= target + 1 while a scale step is in progress, <= target otherwise. "In progress":
///   the target value of that artifact changed within the last `scaleWindowMs` (and then the bound is `max(old, new) + 1`) `[guess: the window;
///   settled by the first LEGACY baseline runs]`.
/// - **(ii)** no `SliceTargetKey` whose owning `AppBlueprintKey` is absent for more than 2 reconcile ticks (CF-5).
/// - **(iii)** no ACTIVE instance row of a node with a committed DEPARTED record for more than one reconcile tick (C17b). LEGACY writes no
///   departure record, so (iii) is vacuous on a LEGACY arm.
/// - **(iv)** at most one open apply per blueprint. The key shape (`DeploymentApplyKey(blueprintId)`) makes two committed apply rows for one
///   blueprint unrepresentable, so the checkable form is: no open apply whose attempt is not the committed blueprint's attempt (a second, stale
///   open apply) for more than one reconcile tick.
record EquivalenceMonitor(long tickMs, long scaleWindowMs) {
    record Sample(long atMs, EquivalenceKv kv) {}

    record Violation(String invariant, long atMs, String detail) {
        String describe() {
            return "(" + invariant + ") at +" + atMs + " ms: " + detail;
        }
    }

    List<Violation> check(List<Sample> samples) {
        var violations = new ArrayList<Violation>();
        var lastChange = new HashMap<ArtifactBase, Long>();
        var previousTarget = new HashMap<ArtifactBase, Integer>();
        var peakDuringStep = new HashMap<ArtifactBase, Integer>();
        var orphanSince = new HashMap<ArtifactBase, Long>();
        var departedActiveSince = new HashMap<String, Long>();
        var staleApplySince = new HashMap<String, Long>();
        var reported = new LinkedHashSet<String>();

        for (var sample : samples) {
            var kv = sample.kv();
            var targets = kv.targets();
            var live = kv.live();

            scaleStep(sample.atMs(), targets, lastChange, previousTarget, peakDuringStep);
            for (var base : unionOf(targets.keySet(), live.keySet())) {
                var count = live.getOrDefault(base, 0L);
                var target = targets.containsKey(base) ? targets.get(base).targetInstances() : 0;
                var inStep = lastChange.containsKey(base) && sample.atMs() - lastChange.get(base) <= scaleWindowMs;
                var bound = inStep ? Math.max(target, peakDuringStep.getOrDefault(base, target)) + 1 : target;

                if (count > bound) {
                    report(violations, reported, new Violation("i", sample.atMs(), base.asString() + " live=" + count + " bound=" + bound + (inStep ? " (scale step)" : "")));
                }
            }
            var blueprints = kv.blueprints();

            targets.forEach((base, target) -> held(orphanSince,
                                                   base,
                                                   target.owningBlueprint().filter(owner -> !blueprints.containsKey(owner.asString())).isPresent(),
                                                   sample.atMs(),
                                                   2 * tickMs).ifPresent(since -> report(violations,
                                                                                          reported,
                                                                                          new Violation("ii", sample.atMs(), base.asString() + " target with no owning blueprint since +" + since))));
            orphanSince.keySet().retainAll(targets.keySet());
            var departed = kv.departed();
            var departedActive = new LinkedHashSet<String>();

            kv.instances()
              .stream()
              .filter(instance -> instance.state().equals("ACTIVE") && departed.contains(instance.node()))
              .forEach(instance -> departedActive.add(instance.node().id() + "/" + instance.base().asString()));
            departedActiveSince.keySet().retainAll(departedActive);
            departedActive.forEach(row -> held(departedActiveSince, row, true, sample.atMs(), tickMs).ifPresent(since -> report(violations,
                                                                                                                                reported,
                                                                                                                                new Violation("iii", sample.atMs(), row + " ACTIVE on a DEPARTED node since +" + since))));
            var staleApplies = new LinkedHashSet<String>();

            kv.applies().forEach((blueprint, apply) -> {
                if (blueprints.containsKey(blueprint) && !blueprints.get(blueprint).attemptId().equals(apply.attemptId())) {
                    staleApplies.add(blueprint);
                }
            });
            staleApplySince.keySet().retainAll(staleApplies);
            staleApplies.forEach(blueprint -> held(staleApplySince, blueprint, true, sample.atMs(), tickMs).ifPresent(since -> report(violations,
                                                                                                                                      reported,
                                                                                                                                      new Violation("iv", sample.atMs(), blueprint + " open apply of a superseded attempt since +" + since))));
        }

        return violations;
    }

    private static void scaleStep(long atMs,
                                  Map<ArtifactBase, AetherValue.SliceTargetValue> targets,
                                  Map<ArtifactBase, Long> lastChange,
                                  Map<ArtifactBase, Integer> previousTarget,
                                  Map<ArtifactBase, Integer> peakDuringStep) {
        for (var base : unionOf(targets.keySet(), previousTarget.keySet())) {
            var now = targets.containsKey(base) ? targets.get(base).targetInstances() : 0;
            var before = previousTarget.getOrDefault(base, 0);

            if (now != before) {
                lastChange.put(base, atMs);
                peakDuringStep.put(base, Math.max(now, before));
            }
            previousTarget.put(base, now);
        }
    }

    /// Tracks how long `condition` has held for `id`; present (the first time it held) once it has held for longer than `boundMs`.
    private static <K> Optional<Long> held(Map<K, Long> since, K id, boolean condition, long atMs, long boundMs) {
        if (!condition) {
            since.remove(id);

            return Optional.empty();
        }
        var first = since.computeIfAbsent(id, _ -> atMs);

        return atMs - first > boundMs ? Optional.of(first) : Optional.empty();
    }

    /// One report per invariant and subject: a breach that persists is one violation, not one per sample.
    private static void report(List<Violation> violations, Set<String> reported, Violation violation) {
        var subject = violation.invariant() + "|" + violation.detail().split(" ")[0];

        if (reported.add(subject)) {
            violations.add(violation);
        }
    }

    private static <T> Set<T> unionOf(Set<T> left, Set<T> right) {
        var union = new LinkedHashSet<T>(left);

        union.addAll(right);

        return union;
    }
}
