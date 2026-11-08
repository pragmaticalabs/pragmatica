// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.forge.EquivalenceComparison.Arm;
import org.pragmatica.aether.forge.EquivalenceComparison.Divergence;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

/// The divergent-engine control (FSM replacement plan 3.3, round 2, H5): one deliberately divergent result per comparison family, which the
/// comparison MUST report. A round in which any family does not report the control's divergence is VOID, whatever FSM2 shows.
///
/// **Deviation from 3.3, recorded:** the plan names a test-only engine selected like an engine. This control is the FSM2 arm's own record passed
/// through one divergent transform per family, so it costs no third cluster run per round. It exercises everything downstream of the KV
/// sampler (the extraction of verdicts, counts and residue, the normaliser, the gates, the monitor); the sampler itself is exercised by the O4
/// scripted batch, which is a real commit read back through the commit ring.
///
/// - O1: the first outcome's verdict flipped (FAILED -> SUCCEEDED, as the plan's "a FATAL report under ALL_OR_NOTHING writes SUCCEEDED"; any other
///   status -> FAILED); with no outcome, a SUCCEEDED outcome appears.
/// - O2: placement stops at target - 1 (one ACTIVE row of the first placed artifact removed); with no placed target, a target of 1 with no row.
/// - O3: C17b leaves one departed node's `TopicPublisher` row.
/// - O5: placement delayed by max(60 s, 2 x LEGACY); O6: one `lost` submission.
/// - Monitor (i)-(iv): each planted in a tail of samples appended after the arm's last sample, long enough to exceed every bound.
final class EquivalenceDivergentControl {
    static final List<String> FAMILIES = List.of("O1", "O2", "O3", "O5", "O6", "MON-i", "MON-ii", "MON-iii", "MON-iv");
    static final NodeId DEPARTED = NodeId.nodeId("f4-control-departed").unwrap();
    private static final Artifact I_PLANT = Artifact.artifact("org.f4.control:monitor-i:1.0.0").unwrap();
    private static final Artifact III_PLANT = Artifact.artifact("org.f4.control:monitor-iii:1.0.0").unwrap();
    private static final Artifact II_PLANT = Artifact.artifact("org.f4.control:monitor-ii:1.0.0").unwrap();
    private static final Artifact O2_PLANT = Artifact.artifact("org.f4.control:o2:1.0.0").unwrap();
    private static final BlueprintId II_OWNER = BlueprintId.blueprintId("org.f4.control:monitor-ii-owner:1.0.0").unwrap();
    private static final BlueprintId IV_PLANT = BlueprintId.blueprintId("org.f4.control:monitor-iv:1.0.0").unwrap();
    private static final BlueprintId O1_PLANT = BlueprintId.blueprintId("org.f4.control:o1:1.0.0").unwrap();

    private EquivalenceDivergentControl() {}

    static Arm of(Arm fsm2, Arm legacy, EquivalenceMonitor monitor) {
        return new Arm("control",
                       new EquivalenceKv(diverged(fsm2.quiescent().entries())),
                       fsm2.liveNodes(),
                       planted(fsm2.samples(), monitor),
                       fsm2.o4Batches(),
                       fsm2.o5PlacementMs() < 0 ? -1 : fsm2.o5PlacementMs() + Math.max(60_000L, 2 * Math.max(0L, legacy.o5PlacementMs())),
                       lostOne(fsm2.o6()));
    }

    /// The families the control did NOT make the comparison report: a divergence of the control comparison that the FSM2 comparison does not
    /// already contain, per family. Empty = every control fired.
    static List<String> silentFamilies(List<Divergence> fsm2, List<Divergence> control) {
        var fsm2Details = new HashSet<String>();

        fsm2.forEach(divergence -> fsm2Details.add(anonymous(divergence)));
        var fired = new HashSet<String>();

        control.stream()
               .filter(divergence -> !fsm2Details.contains(anonymous(divergence)))
               .forEach(divergence -> fired.add(divergence.family()));

        return FAMILIES.stream().filter(family -> !fired.contains(family)).toList();
    }

    /// A divergence with the arm's name erased, so the same finding in the FSM2 and the control comparison compares equal.
    private static String anonymous(Divergence divergence) {
        return divergence.describe().replaceAll("\\b(fsm2|control)\\b", "ARM");
    }

    private static Map<AetherKey, AetherValue> diverged(Map<AetherKey, AetherValue> entries) {
        var kv = new LinkedHashMap<>(entries);
        var view = new EquivalenceKv(entries);

        // O1
        view.outcomes()
            .entrySet()
            .stream()
            .findFirst()
            .ifPresentOrElse(first -> kv.put(AetherKey.DeploymentOutcomeKey.deploymentOutcomeKey(BlueprintId.blueprintId(first.getKey()).unwrap()),
                                             flipped(first.getValue())),
                             () -> kv.put(AetherKey.DeploymentOutcomeKey.deploymentOutcomeKey(O1_PLANT),
                                          new AetherValue.DeploymentOutcomeValue(AetherValue.DeploymentOutcomeStatus.SUCCEEDED, List.of(), "", 0L, 1L, "control")));
        // O2
        var active = view.active();
        var placed = view.targets().entrySet().stream().filter(entry -> entry.getValue().targetInstances() > 0 && active.getOrDefault(entry.getKey(), 0L) > 0).findFirst();

        placed.ifPresentOrElse(target -> removeOneActive(kv, target.getKey()),
                               () -> kv.put(AetherKey.SliceTargetKey.sliceTargetKey(O2_PLANT.base()),
                                            AetherValue.SliceTargetValue.sliceTargetValue(O2_PLANT.version(), 1)));
        // O3
        kv.put(new AetherKey.TopicPublisherKey(ResourceAddress.resourceAddress("f4", "control", "1").unwrap(), O2_PLANT, DEPARTED),
               AetherValue.TopicPublisherValue.topicPublisherValue(DEPARTED, AetherValue.TopicTier.ANY));

        return kv;
    }

    private static AetherValue.DeploymentOutcomeValue flipped(AetherValue.DeploymentOutcomeValue outcome) {
        var status = outcome.status() == AetherValue.DeploymentOutcomeStatus.FAILED
                     ? AetherValue.DeploymentOutcomeStatus.SUCCEEDED
                     : AetherValue.DeploymentOutcomeStatus.FAILED;

        return new AetherValue.DeploymentOutcomeValue(status,
                                                      outcome.failingSlices(),
                                                      outcome.cause(),
                                                      outcome.timestampMs(),
                                                      outcome.outcomeVersion(),
                                                      outcome.attemptId());
    }

    private static void removeOneActive(Map<AetherKey, AetherValue> kv, ArtifactBase base) {
        kv.entrySet()
          .stream()
          .filter(entry -> isActiveRowOf(entry, base))
          .map(Map.Entry::getKey)
          .findFirst()
          .ifPresent(kv::remove);
    }

    private static boolean isActiveRowOf(Map.Entry<AetherKey, AetherValue> entry, ArtifactBase base) {
        return switch (entry.getKey()) {
            case AetherKey.NodeArtifactKey key -> key.artifact().base().equals(base)
                                                  && entry.getValue() instanceof AetherValue.NodeArtifactValue value
                                                  && value.state().name().equals("ACTIVE");
            case AetherKey.SliceObservedKey key -> key.artifact().base().equals(base)
                                                   && entry.getValue() instanceof AetherValue.SliceObservedValue value
                                                   && value.state() == AetherValue.SliceObservedState.ACTIVE;
            default -> false;
        };
    }

    private static Map<String, Double> lostOne(Map<String, Double> o6) {
        var counters = new HashMap<>(o6);

        counters.merge("lost", 1.0, Double::sum);

        return counters;
    }

    /// The arm's samples, each carrying the steady plant targets (so the plants never look like a scale step), then a tail with the four
    /// violations held for longer than every bound (3 ticks + the scale window), one sample per second.
    private static List<EquivalenceMonitor.Sample> planted(List<EquivalenceMonitor.Sample> samples, EquivalenceMonitor monitor) {
        var out = new ArrayList<EquivalenceMonitor.Sample>();

        samples.forEach(sample -> out.add(new EquivalenceMonitor.Sample(sample.atMs(), new EquivalenceKv(withPlantTargets(sample.kv().entries())))));
        var last = samples.isEmpty() ? new EquivalenceMonitor.Sample(0L, new EquivalenceKv(Map.of())) : samples.getLast();
        var tail = new LinkedHashMap<>(withPlantTargets(last.kv().entries()));

        // (i): three LOADING rows of an artifact whose steady target is 1
        for (var index = 0; index < 3; index++) {
            tail.put(AetherKey.SliceObservedKey.sliceObservedKey(NodeId.nodeId("f4-control-i-" + index).unwrap(), I_PLANT), observed(AetherValue.SliceObservedState.LOADING));
        }
        // (ii): a target owned by a blueprint that is not committed
        tail.put(AetherKey.SliceTargetKey.sliceTargetKey(II_PLANT.base()), AetherValue.SliceTargetValue.sliceTargetValue(II_PLANT.version(), 0, Option.some(II_OWNER)));
        // (iii): an ACTIVE row of a DEPARTED node
        tail.put(new AetherKey.NodeDepartureKey(DEPARTED),
                 new AetherValue.NodeDepartureValue(AetherValue.DepartureState.DEPARTED, 1L, new AetherValue.ProcessIncarnation(1L, 1L), Option.none(), 0L));
        tail.put(AetherKey.SliceObservedKey.sliceObservedKey(DEPARTED, III_PLANT), observed(AetherValue.SliceObservedState.ACTIVE));
        // (iv): an open apply of an attempt that is not the committed blueprint's
        tail.put(new AetherKey.AppBlueprintKey(IV_PLANT),
                 AetherValue.AppBlueprintValue.appBlueprintValue(ExpandedBlueprint.expandedBlueprint(IV_PLANT, List.of()), false, "f4-control-current-attempt"));
        tail.put(AetherKey.DeploymentApplyKey.deploymentApplyKey(IV_PLANT), new AetherValue.DeploymentApplyValue("f4-control-superseded-attempt", Option.none(), 0L));
        var tailKv = new EquivalenceKv(tail);
        var span = 3 * monitor.tickMs() + monitor.scaleWindowMs() + 2_000L;

        for (var at = last.atMs() + 1_000L; at <= last.atMs() + span; at += 1_000L) {
            out.add(new EquivalenceMonitor.Sample(at, tailKv));
        }

        return out;
    }

    private static Map<AetherKey, AetherValue> withPlantTargets(Map<AetherKey, AetherValue> entries) {
        var kv = new LinkedHashMap<>(entries);

        kv.put(AetherKey.SliceTargetKey.sliceTargetKey(I_PLANT.base()), AetherValue.SliceTargetValue.sliceTargetValue(I_PLANT.version(), 1));
        kv.put(AetherKey.SliceTargetKey.sliceTargetKey(III_PLANT.base()), AetherValue.SliceTargetValue.sliceTargetValue(III_PLANT.version(), 1));

        return kv;
    }

    private static AetherValue.SliceObservedValue observed(AetherValue.SliceObservedState state) {
        return new AetherValue.SliceObservedValue(state, 1L, new AetherValue.Generation(1L, 1L), new AetherValue.ProcessIncarnation(1L, 1L), Option.none(), false, 0, List.of(), 0L);
    }
}
