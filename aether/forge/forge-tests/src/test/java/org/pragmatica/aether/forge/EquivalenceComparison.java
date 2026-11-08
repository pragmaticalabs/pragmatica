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

    /// What one arm of a round recorded. `o4Batches` is negative when the commit ring dropped entries between reads (the count is void);
    /// `o5PlacementMs` is negative when placement never completed; `o6` holds the hygiene counters (lost, settled_unknown, closed).
    record Arm(String engine,
               EquivalenceKv quiescent,
               Set<NodeId> liveNodes,
               List<EquivalenceMonitor.Sample> samples,
               long o4Batches,
               long o5PlacementMs,
               Map<String, Double> o6) {}

    static List<Divergence> compare(Arm legacy, Arm other, EquivalenceMonitor monitor) {
        var divergences = new ArrayList<Divergence>();

        o1(legacy, other, divergences);
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
        monitor.check(other.samples())
               .forEach(violation -> divergences.add(new Divergence("MON-" + violation.invariant(), other.engine() + " " + violation.describe())));
        monitor.check(legacy.samples())
               .forEach(violation -> divergences.add(new Divergence("MON-" + violation.invariant(), legacy.engine() + " " + violation.describe())));

        return divergences;
    }

    /// O1: the verdict per blueprint, on the durable outcome record.
    private static void o1(Arm legacy, Arm other, List<Divergence> divergences) {
        var left = verdicts(legacy.quiescent());
        var right = verdicts(other.quiescent());

        if (!left.equals(right)) {
            divergences.add(new Divergence("O1", legacy.engine() + "=" + left + " " + other.engine() + "=" + right));
        }
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
            divergences.add(new Divergence("O4", "void count: the commit ring dropped entries (legacy=" + legacy.o4Batches() + ", " + other.engine() + "=" + other.o4Batches() + ")"));
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
