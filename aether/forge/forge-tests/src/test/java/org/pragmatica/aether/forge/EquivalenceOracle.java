// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm2.AbsenceBounds;
import org.pragmatica.aether.deployment.cluster.fsm2.Admission;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterEffect;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterFsm;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterState;
import org.pragmatica.aether.deployment.cluster.fsm2.Seams;
import org.pragmatica.aether.deployment.cluster.fsm2.Settings;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.swim.SwimConfig;

/// The quiescent-point convergence oracle (phase-3 design 9.6, P3-Q23, D-8): at a quiescent point of a LEGACY run, project the committed state
/// into FSM2's records, feed the projection to a **pure** `ClusterFsm` as `Rebuild`, run the first `Reconcile` through its `Flush`, and collect
/// every `Submit`. A Submit not on the frozen allow-list (`fsm2/oracle-allowlist.txt`, keyed by spec CHANGE row) is a disagreement to read.
///
/// The projection (D-8 (a)) is exactly:
/// - `SliceDesiredKey(n, a)` + `SliceObservedKey(n, a)` per LEGACY `NodeArtifactKey` in ACTIVE or LOADED: goal = state, generation
///   `(committed LeaderValue.viewSequence, counter 1..k in key order)`, observed version 1 for that generation, incarnation 0 (current by C8);
/// - no `DeploymentApplyKey` and no `NodeDepartureKey`; `NodeArtifactKey` itself is dropped (FSM2 does not read it);
/// - `ArtifactJudgmentKey`: none. LEGACY's in-memory `permanentlyFailed`/`retryCounters` have no test hook in this lane `[guess: the hook]`,
///   so the oracle runs only at quiescent points with no FAILED instance and no FAILED outcome, and says so (`judgmentsUnread`);
/// - every shared key (targets, blueprints, outcomes, directives, rosters, communities) as committed.
///
/// Seams are not in the D-8 table: they are synthesised from the cluster view (counted = ready = core view = local alive = the live cores),
/// admissions are all `UNKNOWN` (token 0, C8). Both positive controls (D-8 (e)) run on every check: under-placement (one projected instance
/// removed: a goal Submit must appear) and a stray (an observed row with no desired key: an adopt/condemn Submit naming it must appear).
final class EquivalenceOracle {
    private static final long NOW_WALL = 1_760_000_000_000L;
    private static final long NOW_MONO = 5_000_000_000L;
    private static final int DRAIN_GUARD = 20_000;
    static final String ALLOW_LIST = "/fsm2/oracle-allowlist.txt";

    private EquivalenceOracle() {}

    /// One Submit, reduced to what the allow-list matches: its kind and the classes of the keys it mutates, with each key.
    record SubmitSeen(String kind, List<String> mutatedKeys) {
        String describe() {
            return kind + " " + mutatedKeys;
        }
    }

    record AllowEntry(String changeRow, String kind, Pattern key) {
        boolean admits(SubmitSeen submit) {
            return submit.kind().equals(kind) && submit.mutatedKeys().stream().allMatch(mutated -> key.matcher(mutated).find());
        }
    }

    /// The verdict of one quiescent point: the disagreements (Submits not on the allow-list) and whether each positive control fired.
    record Verdict(List<SubmitSeen> submits,
                   List<SubmitSeen> disagreements,
                   boolean underPlacementControlFired,
                   boolean strayControlFired,
                   int projectedInstances,
                   boolean judgmentsUnread) {
        boolean controlsFired() {
            return underPlacementControlFired && strayControlFired;
        }

        boolean green() {
            return controlsFired() && disagreements.isEmpty();
        }

        String describe() {
            return "oracle: projected=%d submits=%d disagreements=%s controls(underPlacement=%s, stray=%s) judgmentsUnread=%s".formatted(projectedInstances,
                                                                                                                                      submits.size(),
                                                                                                                                      disagreements.stream()
                                                                                                                                                   .map(SubmitSeen::describe)
                                                                                                                                                   .toList(),
                                                                                                                                      underPlacementControlFired,
                                                                                                                                      strayControlFired,
                                                                                                                                      judgmentsUnread);
        }
    }

    static Verdict check(EquivalenceKv legacy, LeaderValue leader, Set<NodeId> liveCores) {
        var allowList = allowList();
        var projection = project(legacy, leader);
        var submits = run(projection, leader, liveCores);
        var disagreements = submits.stream()
                                   .filter(submit -> allowList.stream().noneMatch(entry -> entry.admits(submit)))
                                   .toList();

        return new Verdict(submits,
                           disagreements,
                           underPlacementFires(projection, leader, liveCores),
                           strayFires(projection, leader, liveCores),
                           (int) projection.keySet().stream().filter(AetherKey.SliceDesiredKey.class::isInstance).count(),
                           true);
    }

    /// D-8 (a).
    static Map<AetherKey, AetherValue> project(EquivalenceKv legacy, LeaderValue leader) {
        var projected = new HashMap<AetherKey, AetherValue>();
        var instances = new ArrayList<Map.Entry<AetherKey.NodeArtifactKey, AetherValue.NodeArtifactValue>>();

        legacy.entries().forEach((key, value) -> {
            switch (key) {
                case AetherKey.NodeArtifactKey artifactKey when value instanceof AetherValue.NodeArtifactValue artifactValue -> instances.add(Map.entry(artifactKey, artifactValue));
                case AetherKey.NodeArtifactKey _ -> {}
                case AetherKey.SliceDesiredKey _, AetherKey.SliceObservedKey _, AetherKey.DeploymentApplyKey _, AetherKey.NodeDepartureKey _,
                     AetherKey.ArtifactJudgmentKey _ -> {}
                default -> projected.put(key, value);
            }
        });
        instances.sort(Comparator.comparing(entry -> entry.getKey().toString()));
        var counter = 0L;

        for (var instance : instances) {
            var state = instance.getValue().state().name();

            if (!state.equals("ACTIVE") && !state.equals("LOADED")) {
                continue;
            }
            var generation = new AetherValue.Generation(leader.viewSequence(), ++counter);
            var node = instance.getKey().nodeId();
            var artifact = instance.getKey().artifact();

            projected.put(AetherKey.SliceDesiredKey.sliceDesiredKey(node, artifact),
                          new AetherValue.SliceDesiredValue(AetherValue.SliceGoal.valueOf(state), generation, NOW_WALL));
            projected.put(AetherKey.SliceObservedKey.sliceObservedKey(node, artifact),
                          observed(AetherValue.SliceObservedState.valueOf(state), generation, instance.getValue()));
        }

        return projected;
    }

    private static AetherValue.SliceObservedValue observed(AetherValue.SliceObservedState state,
                                                           AetherValue.Generation generation,
                                                           AetherValue.NodeArtifactValue legacy) {
        return new AetherValue.SliceObservedValue(state,
                                                  1L,
                                                  generation,
                                                  Settings.UNKNOWN_PROCESS,
                                                  Option.none(),
                                                  false,
                                                  legacy.instanceNumber(),
                                                  legacy.methods(),
                                                  NOW_WALL);
    }

    /// D-8 (e), first control: one projected instance removed; red unless a Submit puts a desired key.
    private static boolean underPlacementFires(Map<AetherKey, AetherValue> projection, LeaderValue leader, Set<NodeId> liveCores) {
        return projection.keySet()
                         .stream()
                         .filter(AetherKey.SliceDesiredKey.class::isInstance)
                         .map(AetherKey.SliceDesiredKey.class::cast)
                         .min(Comparator.comparing(Object::toString))
                         .map(victim -> {
                             var underPlaced = new HashMap<>(projection);

                             underPlaced.remove(victim);
                             underPlaced.remove(AetherKey.SliceObservedKey.sliceObservedKey(victim.nodeId(), victim.artifact()));

                             return run(underPlaced, leader, liveCores).stream()
                                                                       .anyMatch(submit -> submit.mutatedKeys()
                                                                                                 .stream()
                                                                                                 .anyMatch(key -> key.startsWith("SliceDesiredKey")
                                                                                                                  && key.contains(victim.artifact().asString())));
                         })
                         .orElse(false);
    }

    /// D-8 (e), second control: one stray observed ACTIVE row with no desired key on a live core; red unless a Submit names that row's key.
    private static boolean strayFires(Map<AetherKey, AetherValue> projection, LeaderValue leader, Set<NodeId> liveCores) {
        var artifact = projection.keySet()
                                 .stream()
                                 .filter(AetherKey.SliceDesiredKey.class::isInstance)
                                 .map(key -> ((AetherKey.SliceDesiredKey) key).artifact())
                                 .min(Comparator.comparing(Artifact::asString));
        var host = artifact.flatMap(chosen -> liveCores.stream()
                                                        .sorted(Comparator.comparing(NodeId::id))
                                                        .filter(node -> !projection.containsKey(AetherKey.SliceDesiredKey.sliceDesiredKey(node, chosen)))
                                                        .findFirst());

        if (artifact.isEmpty() || host.isEmpty()) {
            return false;
        }
        var strayKey = AetherKey.SliceObservedKey.sliceObservedKey(host.get(), artifact.get());
        var withStray = new HashMap<>(projection);

        withStray.put(strayKey,
                      new AetherValue.SliceObservedValue(AetherValue.SliceObservedState.ACTIVE,
                                                         1L,
                                                         new AetherValue.Generation(leader.viewSequence(), 999_999L),
                                                         Settings.UNKNOWN_PROCESS,
                                                         Option.none(),
                                                         false,
                                                         0,
                                                         List.of(),
                                                         NOW_WALL));
        var hostId = host.get().id();
        var artifactName = artifact.get().asString();

        return run(withStray, leader, liveCores).stream()
                                                .anyMatch(submit -> submit.mutatedKeys()
                                                                          .stream()
                                                                          .anyMatch(key -> key.contains(hostId) && key.contains(artifactName)));
    }

    /// Activate, `Rebuild(projection)`, then drain the mailbox until the first `Reconcile` has run and the `Flush` after it has run (D-8 (b)).
    static List<SubmitSeen> run(Map<AetherKey, AetherValue> projection, LeaderValue leader, Set<NodeId> liveCores) {
        var fsm = ClusterFsm.clusterFsm(settings(leader.leader()), () -> NOW_WALL, () -> NOW_MONO);
        var mailbox = new ArrayDeque<ClusterInput>();
        var submits = new ArrayList<SubmitSeen>();
        var snapshot = snapshot(projection, leader, liveCores);
        var state = ClusterState.initial();
        var reconciled = false;
        var flushedAfterReconcile = false;
        var guard = 0;

        mailbox.add(new ClusterInput.Activate());
        while (!flushedAfterReconcile) {
            if (mailbox.isEmpty()) {
                if (reconciled) {
                    break;
                }
                mailbox.add(new ClusterInput.Reconcile());
            }
            if (++guard > DRAIN_GUARD) {
                throw new AssertionError("oracle: the pure cluster FSM keeps queueing work for itself");
            }
            var input = mailbox.pollFirst();
            var stepped = fsm.step(state, input);

            state = stepped.state();
            if (input instanceof ClusterInput.Reconcile) {
                reconciled = true;
            }
            if (input instanceof ClusterInput.Flush && reconciled) {
                flushedAfterReconcile = true;
            }
            for (var effect : stepped.effects()) {
                switch (effect) {
                    case ClusterEffect.Enqueue enqueue -> mailbox.addLast(enqueue.input());
                    case ClusterEffect.RequestRebuild _ -> mailbox.addFirst(new ClusterInput.Rebuild(snapshot));
                    case ClusterEffect.Submit submit -> submits.add(seen(submit));
                    default -> {}
                }
            }
        }

        return submits;
    }

    private static SubmitSeen seen(ClusterEffect.Submit submit) {
        return new SubmitSeen(submit.kind().name(),
                              submit.transaction()
                                    .mutations()
                                    .stream()
                                    .map(mutation -> mutation.key().getClass().getSimpleName() + ":" + mutation.key())
                                    .toList());
    }

    private static ClusterInput.Snapshot snapshot(Map<AetherKey, AetherValue> projection, LeaderValue leader, Set<NodeId> liveCores) {
        var entries = projection.entrySet()
                                .stream()
                                .sorted(Comparator.comparing(entry -> entry.getKey().toString()))
                                .map(entry -> new ClusterInput.KvPut(entry.getKey(), entry.getValue()))
                                .toList();
        var admissions = new HashMap<NodeId, Admission>();

        liveCores.forEach(node -> admissions.put(node, Admission.UNKNOWN));

        return ClusterInput.Snapshot.snapshot(entries, seams(liveCores), admissions, Option.some(leader));
    }

    private static Seams seams(Set<NodeId> liveCores) {
        return Seams.seams(liveCores, liveCores, Set.of(), Option.some(liveCores), Option.some(liveCores), Set.of(), Map.of(), Map.of());
    }

    /// The production settings shape (Fsm2System.clusterSettings) with the default timeouts: ALL_OR_NOTHING, default SWIM, no suspicion lag.
    private static Settings settings(NodeId self) {
        var swim = SwimConfig.DEFAULT;
        var bounds = AbsenceBounds.derive(swim.suspectTimeout(),
                                          swim.suspectTimeout(),
                                          swim.lhmMaxScore(),
                                          SwimConfig.MAX_CLUSTER_SIZE_WINDOW_MULTIPLIER,
                                          TimeSpan.timeSpan(30).seconds(),
                                          TimeSpan.timeSpan(60).seconds(),
                                          TimeSpan.timeSpan(0).seconds())
                                  .unwrap();

        return Settings.settings(self, DeploymentAtomicity.ALL_OR_NOTHING, Set.of(), bounds);
    }

    /// D-8 (d): the allow-list is frozen before the first run. One line per entry: `<CHANGE row> <SubmitKind> <regex over "KeyClass:key">`.
    static List<AllowEntry> allowList() {
        var stream = Objects.requireNonNull(EquivalenceOracle.class.getResourceAsStream(ALLOW_LIST), ALLOW_LIST + " is checked in");

        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.lines()
                         .map(String::strip)
                         .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                         .map(line -> line.split("\\s+", 3))
                         .map(parts -> new AllowEntry(parts[0], parts[1], Pattern.compile(parts[2])))
                         .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
