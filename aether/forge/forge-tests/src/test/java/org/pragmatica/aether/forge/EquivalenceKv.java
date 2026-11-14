// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.consensus.NodeId;

/// The engine-neutral reading of one committed KV snapshot that every F4 instrument shares (design 9.5 criterion 4: "the instruments are
/// identical for both engines"). A LEGACY instance is a `NodeArtifactKey`; an FSM2 instance is a `SliceObservedKey`. Both families are read in
/// every snapshot, so a reading never depends on which engine the run was told it used.
record EquivalenceKv(Map<AetherKey, AetherValue> entries) {
    /// The O4 key families: the UNION of both engines' deployment families plus the shared target/blueprint/outcome keys (design 9.5 O4).
    static final List<Class<? extends AetherKey>> O4_FAMILIES = List.of(AetherKey.NodeArtifactKey.class,
                                                                        AetherKey.SliceDesiredKey.class,
                                                                        AetherKey.SliceObservedKey.class,
                                                                        AetherKey.NodeDepartureKey.class,
                                                                        AetherKey.ArtifactJudgmentKey.class,
                                                                        AetherKey.DeploymentApplyKey.class,
                                                                        AetherKey.SliceTargetKey.class,
                                                                        AetherKey.AppBlueprintKey.class,
                                                                        AetherKey.DeploymentOutcomeKey.class);

    /// The instance states invariant (i) counts: ACTIVE + LOADING + LOADED (plan 3.3).
    private static final Set<String> LIVE_STATES = Set.of("ACTIVE", "LOADING", "LOADED");

    EquivalenceKv {
        entries = Map.copyOf(entries);
    }

    static boolean inO4Family(Object key) {
        return O4_FAMILIES.stream().anyMatch(family -> family.isInstance(key));
    }

    /// One instance row of either engine, reduced to what the instruments compare.
    record Instance(NodeId node, ArtifactBase base, String state, String family) {}

    List<Instance> instances() {
        return entries.entrySet()
                      .stream()
                      .<Instance>mapMulti((entry, sink) -> {
                          switch (entry.getKey()) {
                              case AetherKey.NodeArtifactKey key when entry.getValue() instanceof AetherValue.NodeArtifactValue value ->
                                  sink.accept(new Instance(key.nodeId(), key.artifact().base(), value.state().name(), "legacy"));
                              case AetherKey.SliceObservedKey key when entry.getValue() instanceof AetherValue.SliceObservedValue value ->
                                  sink.accept(new Instance(key.nodeId(), key.artifact().base(), value.state().name(), "fsm2"));
                              default -> {}
                          }
                      })
                      .toList();
    }

    Map<ArtifactBase, Long> count(Predicate<String> state) {
        var counts = new TreeMap<ArtifactBase, Long>(EquivalenceKv::compareBase);

        instances().stream()
                   .filter(instance -> state.test(instance.state()))
                   .forEach(instance -> counts.merge(instance.base(), 1L, Long::sum));

        return counts;
    }

    Map<ArtifactBase, Long> active() {
        return count("ACTIVE"::equals);
    }

    Map<ArtifactBase, Long> live() {
        return count(LIVE_STATES::contains);
    }

    Map<ArtifactBase, AetherValue.SliceTargetValue> targets() {
        var targets = new TreeMap<ArtifactBase, AetherValue.SliceTargetValue>(EquivalenceKv::compareBase);

        entries.forEach((key, value) -> {
            if (key instanceof AetherKey.SliceTargetKey target && value instanceof AetherValue.SliceTargetValue targetValue) {
                targets.put(target.artifactBase(), targetValue);
            }
        });

        return targets;
    }

    Map<String, AetherValue.DeploymentOutcomeValue> outcomes() {
        var outcomes = new TreeMap<String, AetherValue.DeploymentOutcomeValue>();

        entries.forEach((key, value) -> {
            if (key instanceof AetherKey.DeploymentOutcomeKey outcome && value instanceof AetherValue.DeploymentOutcomeValue outcomeValue) {
                outcomes.put(outcome.blueprintId().asString(), outcomeValue);
            }
        });

        return outcomes;
    }

    Map<String, AetherValue.AppBlueprintValue> blueprints() {
        var blueprints = new TreeMap<String, AetherValue.AppBlueprintValue>();

        entries.forEach((key, value) -> {
            if (key instanceof AetherKey.AppBlueprintKey blueprint && value instanceof AetherValue.AppBlueprintValue blueprintValue) {
                blueprints.put(blueprint.blueprintId().asString(), blueprintValue);
            }
        });

        return blueprints;
    }

    Map<String, AetherValue.DeploymentApplyValue> applies() {
        var applies = new TreeMap<String, AetherValue.DeploymentApplyValue>();

        entries.forEach((key, value) -> {
            if (key instanceof AetherKey.DeploymentApplyKey apply && value instanceof AetherValue.DeploymentApplyValue applyValue) {
                applies.put(apply.blueprintId().asString(), applyValue);
            }
        });

        return applies;
    }

    /// Nodes with a committed DEPARTED record (FSM2 only; LEGACY writes none, so invariant (iii) is vacuous on a LEGACY arm).
    Set<NodeId> departed() {
        return entries.entrySet()
                      .stream()
                      .filter(entry -> entry.getKey() instanceof AetherKey.NodeDepartureKey
                                       && entry.getValue() instanceof AetherValue.NodeDepartureValue value
                                       && value.state() == AetherValue.DepartureState.DEPARTED)
                      .map(entry -> ((AetherKey.NodeDepartureKey) entry.getKey()).nodeId())
                      .collect(Collectors.toUnmodifiableSet());
    }

    static int compareBase(ArtifactBase left, ArtifactBase right) {
        return left.asString().compareTo(right.asString());
    }
}
