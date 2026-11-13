// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.consensus.NodeId;

/// Outcome equivalence, LEGACY vs FSM2 (phase-3 design 9.5 criterion 4, O1-O6), over [Arm]s recorded by the same instruments for both engines.
///
/// Every comparison is a list of [Divergence]s tagged with its family. The **normaliser** is exactly the exemptions written here, each named
/// by the design: O1 `DEPLOYED -> FULLY_ACTIVE` (Q7; the durable record has neither, so a no-op), O2 node sets may differ, O3 FSM2 desired
/// tombstones (goal UNLOADED, inside the grace) and kept directives (never read). The divergent control (`EquivalenceDivergentControl`) bounds
/// it: a normaliser generous enough to hide the control voids the round.
final class EquivalenceComparison {
    /// Design 9.5 O4: FSM2 <= 1.2 x LEGACY `[guess: 1.2]`.
    static final double O4_RATIO = 1.2;
    /// Design 9.5 O5: FSM2 <= LEGACY + max(30 s, 25%) `[guess]`.
    static final long O5_FLOOR_MS = 30_000L;
    static final double O5_RATIO = 0.25;

    private EquivalenceComparison() {}

    record Divergence(String family, String detail) {
        String describe() {
            return family + ": " + detail;
        }
    }

    /// What one arm of a round recorded. `o4Batches` is negative when the arm did not quiesce (the count is void; a commit ring that dropped
    /// entries on every node is a round ERROR, never an O4 value);
    /// `o5PlacementMs` is negative when placement never completed; `o6` holds the hygiene counters (lost, settled_unknown, closed);
    /// `targetEvents` are the `SliceTargetKey` transitions the commit ring recorded between the samples (scale-step detection, MON-i).
    record Arm(String engine,
               EquivalenceKv quiescent,
               Set<NodeId> liveNodes,
               List<EquivalenceMonitor.Sample> samples,
               long o4Batches,
               long o5PlacementMs,
               Map<String, Double> o6,
               List<EquivalenceMonitor.TargetEvent> targetEvents) {
        /// An arm with no ring-recorded target transitions: the samples are the only witness of a scale step.
        Arm(String engine,
            EquivalenceKv quiescent,
            Set<NodeId> liveNodes,
            List<EquivalenceMonitor.Sample> samples,
            long o4Batches,
            long o5PlacementMs,
            Map<String, Double> o6) {
            this(engine, quiescent, liveNodes, samples, o4Batches, o5PlacementMs, o6, List.of());
        }
    }

    /// A RULED outcome change, not a divergence: in `scenario` (and nowhere else), LEGACY's `legacy` verdict and the other arm's `other` verdict
    /// on the same blueprint are equal by an owner ruling, recorded as the `know` commit. Never a general normaliser: one named mapping per ruling.
    record RuledVerdict(String scenario, AetherValue.DeploymentOutcomeStatus legacy, AetherValue.DeploymentOutcomeStatus other, String know) {}

    /// E-09 only: owner ruling know `8018203973` (2026-10-21; #970 folds into the FSM rewrite with terminal ROLLED_BACK, matching #922
    /// ALL_OR_NOTHING). LEGACY writes FAILED, FSM2 writes ROLLED_BACK; the round treats the pair as one verdict.
    static final RuledVerdict E09_ROLLED_BACK = new RuledVerdict("E09",
                                                                 AetherValue.DeploymentOutcomeStatus.FAILED,
                                                                 AetherValue.DeploymentOutcomeStatus.ROLLED_BACK,
                                                                 "8018203973");

    /// E-09 only, the O4 allow-list (CTO R2 on #2763): the batch signatures (`Family op detail`) that are rollback actions the ROLLED_BACK terminal
    /// requires: unloading each placed instance, removing its observed and desired rows, removing the targets. A batch is excluded from O4 only
    /// when EVERY effect it carries matches one of these; a batch that mixes in anything else (the terminal batch, a promotion) stays counted.
    /// Allow-listed per family and transition, never per family alone: `SliceObservedKey put LOADING` / `ACTIVATING` / `ACTIVE` stay counted.
    static final Set<String> E09_ROLLBACK_SIGNATURES = Set.of("SliceObservedKey put UNLOADING",
                                                              "SliceObservedKey put UNLOADED",
                                                              "SliceObservedKey remove -",
                                                              "SliceDesiredKey put UNLOADED",
                                                              "SliceDesiredKey remove -",
                                                              "SliceTargetKey remove -");

    /// The O4 count of one arm from its per-slot effects (`Family op detail key`): every slot, minus, on E-09 only, the slots made solely of
    /// [#E09_ROLLBACK_SIGNATURES]. The same function for both arms.
    static long o4Counted(Map<Long, List<String>> slotEffects, String scenario) {
        return slotEffects.values().stream().filter(effects -> !isRuledRollback(effects, scenario)).count();
    }

    private static boolean isRuledRollback(List<String> effects, String scenario) {
        return E09_ROLLED_BACK.scenario().equals(scenario)
               && !effects.isEmpty()
               && effects.stream().map(EquivalenceComparison::signature).allMatch(E09_ROLLBACK_SIGNATURES::contains);
    }

    /// The `Family op detail` part of one effect description `Family op detail key`.
    static String signature(String effect) {
        var parts = effect.split(" ", 4);

        return parts.length < 3 ? effect : parts[0] + " " + parts[1] + " " + parts[2];
    }

    /// The families whose per-node puts are placement decisions: LEGACY's `NodeArtifactKey`, FSM2's `SliceDesiredKey` and `SliceObservedKey`.
    private static final Set<String> PLACEMENT_FAMILIES = Set.of("NodeArtifactKey", "SliceDesiredKey", "SliceObservedKey");

    /// The placement of one arm from its per-slot effects (`Family op detail key`, key `prefix/node/artifact`): per artifact, the number of
    /// distinct nodes that ever received a non-teardown put (detail not UNLOAD*). Every placement family is keyed per (node, artifact), so a
    /// node holds at most one instance and the count is nodes x 1 instance. Node ids differ per arm (prefix), so only the counts are compared:
    /// an O4 comparison is valid only between arms with equal placement (CTO ruling on #2763; LEGACY E-09 was bimodal, 1 node vs 3).
    static String placement(Map<Long, List<String>> slotEffects) {
        return slotEffects.values()
                          .stream()
                          .flatMap(List::stream)
                          .map(effect -> effect.split(" ", 4))
                          .filter(parts -> isPlacement(parts))
                          .map(parts -> parts[3].split("/", 3))
                          .filter(key -> key.length == 3)
                          .collect(Collectors.groupingBy(key -> key[2], TreeMap::new, Collectors.mapping(key -> key[1], Collectors.toSet())))
                          .entrySet()
                          .stream()
                          .map(entry -> entry.getKey() + "=" + entry.getValue().size() + " nodes")
                          .collect(Collectors.joining(", ", "[", "]"));
    }

    /// Whether one effect description (`Family op detail key`) is a placement put: the effects [#placement] counts.
    static boolean isPlacement(String effect) {
        return isPlacement(effect.split(" ", 4));
    }

    private static boolean isPlacement(String[] parts) {
        return parts.length == 4 && PLACEMENT_FAMILIES.contains(parts[0]) && parts[1].equals("put") && !parts[2].startsWith("UNLOAD");
    }

    /// Compares outside any ruled scenario: no outcome mapping applies.
    static List<Divergence> compare(Arm legacy, Arm other, EquivalenceMonitor monitor) {
        return compare(legacy, other, monitor, "");
    }

    /// Compares one round of `scenario`; the only scenario-scoped exemption is [#E09_ROLLED_BACK], applied in O1 when `scenario` is E09.
    static List<Divergence> compare(Arm legacy, Arm other, EquivalenceMonitor monitor, String scenario) {
        var divergences = new ArrayList<Divergence>();

        o1(legacy, other, divergences, scenario);
        o2(legacy, divergences);
        o2(other, divergences);
        residue(other).forEach(row -> divergences.add(new Divergence("O3", other.engine() + " residue " + row)));
        residue(legacy).forEach(row -> divergences.add(new Divergence("O3", legacy.engine() + " residue " + row)));
        o4(legacy, other, divergences);
        o5(legacy, other, divergences);
        other.o6().forEach((counter, count) -> {
            if (count != 0.0) {
                divergences.add(new Divergence("O6", other.engine() + " " + counter + "=" + count + ", expected 0"));
            }
        });
        monitor.check(other.samples(), other.targetEvents())
               .forEach(violation -> divergences.add(new Divergence("MON-" + violation.invariant(), other.engine() + " " + violation.describe())));
        monitor.check(legacy.samples(), legacy.targetEvents())
               .forEach(violation -> divergences.add(new Divergence("MON-" + violation.invariant(), legacy.engine() + " " + violation.describe())));

        return divergences;
    }

    /// O1: the verdict per blueprint, on the durable outcome record, after the scenario's ruled mapping (E-09 only).
    private static void o1(Arm legacy, Arm other, List<Divergence> divergences, String scenario) {
        var left = verdicts(legacy.quiescent());
        var right = ruled(left, verdicts(other.quiescent()), scenario);

        if (!left.equals(right)) {
            divergences.add(new Divergence("O1", legacy.engine() + "=" + left + " " + other.engine() + "=" + right));
        }
    }

    /// The other arm's verdicts with [#E09_ROLLED_BACK] applied: on E-09 only, a blueprint LEGACY recorded FAILED and the other arm recorded
    /// ROLLED_BACK reads as FAILED. Every other verdict, and every verdict of every other scenario, is unchanged.
    static Map<String, String> ruled(Map<String, String> legacy, Map<String, String> other, String scenario) {
        var mapped = new TreeMap<String, String>();

        other.forEach((blueprint, status) -> mapped.put(blueprint, isRuled(scenario, legacy.get(blueprint), status) ? E09_ROLLED_BACK.legacy().name() : status));

        return mapped;
    }

    private static boolean isRuled(String scenario, String legacy, String other) {
        return E09_ROLLED_BACK.scenario().equals(scenario)
               && E09_ROLLED_BACK.legacy().name().equals(legacy)
               && E09_ROLLED_BACK.other().name().equals(other);
    }

    static Map<String, String> verdicts(EquivalenceKv kv) {
        var verdicts = new TreeMap<String, String>();

        kv.outcomes().forEach((blueprint, outcome) -> verdicts.put(blueprint, outcome.status().name()));

        return verdicts;
    }

    /// O2: ACTIVE instances per artifact equal the target at quiescence, in each arm (node sets may differ).
    private static void o2(Arm arm, List<Divergence> divergences) {
        var active = arm.quiescent().active();

        arm.quiescent().targets().forEach((base, target) -> {
            var count = active.getOrDefault(base, 0L);

            if (count != target.targetInstances()) {
                divergences.add(new Divergence("O2", arm.engine() + " " + base.asString() + " active=" + count + " target=" + target.targetInstances()));
            }
        });
    }

    /// O3: rows left behind at quiescence. Exempt by design: FSM2 desired tombstones (goal UNLOADED) and kept directives (not read here).
    static List<String> residue(Arm arm) {
        var kv = arm.quiescent();
        var targets = kv.targets().keySet();
        var live = arm.liveNodes();
        var rows = new ArrayList<String>();

        kv.entries().forEach((key, value) -> {
            switch (key) {
                case AetherKey.NodeArtifactKey row when !targets.contains(row.artifact().base()) || !live.contains(row.nodeId()) -> rows.add(row.toString());
                case AetherKey.SliceObservedKey row when !targets.contains(row.artifact().base()) || !live.contains(row.nodeId()) -> rows.add(row.toString());
                case AetherKey.SliceDesiredKey row when value instanceof AetherValue.SliceDesiredValue desired
                                                       && desired.goal() != AetherValue.SliceGoal.UNLOADED
                                                       && (!targets.contains(row.artifact().base()) || !live.contains(row.nodeId())) -> rows.add(row.toString());
                case AetherKey.TopicPublisherKey row when !live.contains(row.nodeId()) || !targets.contains(row.artifact().base()) -> rows.add(row.toString());
                case AetherKey.HttpNodeRouteKey row when !live.contains(row.nodeId()) -> rows.add(row.toString());
                case AetherKey.EndpointKey row when !targets.contains(row.artifact().base()) -> rows.add(row.toString());
                case AetherKey.DeploymentApplyKey row -> rows.add(row + " (open apply at quiescence)");
                default -> {}
            }
        });
        kv.outcomes()
          .forEach((blueprint, outcome) -> {
              if (outcome.status() == AetherValue.DeploymentOutcomeStatus.IN_PROGRESS) {
                  rows.add(blueprint + " outcome IN_PROGRESS at quiescence");
              }
          });

        return rows;
    }

    private static void o4(Arm legacy, Arm other, List<Divergence> divergences) {
        if (legacy.o4Batches() < 0 || other.o4Batches() < 0) {
            divergences.add(new Divergence("O4", "void count: an arm did not quiesce (legacy=" + legacy.o4Batches() + ", " + other.engine() + "=" + other.o4Batches() + ")"));
        } else if (other.o4Batches() > O4_RATIO * legacy.o4Batches()) {
            divergences.add(new Divergence("O4", other.engine() + " batches=" + other.o4Batches() + " > " + O4_RATIO + " x legacy " + legacy.o4Batches()));
        }
    }

    private static void o5(Arm legacy, Arm other, List<Divergence> divergences) {
        if (legacy.o5PlacementMs() < 0 && other.o5PlacementMs() < 0) {
            return;
        }
        if (legacy.o5PlacementMs() < 0 || other.o5PlacementMs() < 0) {
            divergences.add(new Divergence("O5", "placement completed in one arm only (legacy=" + legacy.o5PlacementMs() + " ms, " + other.engine() + "=" + other.o5PlacementMs() + " ms)"));

            return;
        }
        var allowance = Math.max(O5_FLOOR_MS, (long) (O5_RATIO * legacy.o5PlacementMs()));

        if (other.o5PlacementMs() > legacy.o5PlacementMs() + allowance) {
            divergences.add(new Divergence("O5", other.engine() + " placement=" + other.o5PlacementMs() + " ms > legacy " + legacy.o5PlacementMs() + " ms + " + allowance + " ms"));
        }
    }
}
