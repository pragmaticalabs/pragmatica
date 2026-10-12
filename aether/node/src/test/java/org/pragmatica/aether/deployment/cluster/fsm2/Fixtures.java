// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Set;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.config.TimeoutsConfig.ClusterTimeouts;
import org.pragmatica.aether.config.WorkerConfig.SwimSettings;
import org.pragmatica.aether.deployment.membership.MembershipConfig;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.aether.worker.governor.GovernorAnnouncer;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.swim.SwimConfig;

/// Fixtures for the cluster FSM tests: identities, artifacts, rows, blueprints, and the absence bounds derived from the
/// **real** defaults (spec 8.3: a change to any default moves the test's boundary with it).
final class Fixtures {
    private Fixtures() {}

    static final NodeId CORE_1 = node("core-1");
    static final NodeId CORE_2 = node("core-2");
    static final NodeId CORE_3 = node("core-3");
    static final Artifact ORDERS = artifact("org.example:orders:1.0.0");
    static final Artifact ORDERS_V2 = artifact("org.example:orders:2.0.0");
    static final Artifact BILLING = artifact("org.example:billing:1.0.0");

    static NodeId node(String id) {
        return NodeId.nodeId(id).unwrap();
    }

    static Artifact artifact(String coordinates) {
        return Artifact.artifact(coordinates).unwrap();
    }

    static BlueprintId blueprintId(String coordinates) {
        return BlueprintId.blueprintId(artifact(coordinates));
    }

    /// B_rm, B_w and S_ann from the real configuration defaults (R-2.14, R-2.14b, R-2.14c).
    static AbsenceBounds bounds() {
        var swim = SwimConfig.DEFAULT;

        return AbsenceBounds.derive(swim.suspectTimeout(),
                                    SwimSettings.swimSettings().suspectTimeout(),
                                    swim.lhmMaxScore(),
                                    SwimConfig.MAX_CLUSTER_SIZE_WINDOW_MULTIPLIER,
                                    MembershipConfig.membershipConfig().splitTimeout(),
                                    GovernorAnnouncer.DEFAULT_REANNOUNCE_INTERVAL,
                                    ClusterTimeouts.clusterTimeouts().communityAbsence());
    }

    static long ms(long nanos) {
        return nanos / 1_000_000L;
    }

    static ProcessIncarnation process(long token) {
        return new ProcessIncarnation(1_760_000_000_000L + token, token);
    }

    static Admission admission(long epoch, long token) {
        return new Admission(epoch, process(token));
    }

    static Generation gen(long counter) {
        return new Generation(1L, counter);
    }

    static SliceDesiredValue desired(SliceGoal goal, Generation generation) {
        return new SliceDesiredValue(goal, generation, FsmHarness.START_WALL);
    }

    static SliceObservedValue row(SliceObservedState state, long version, Generation generation, ProcessIncarnation process) {
        return new SliceObservedValue(state, version, generation, process, Option.none(), false, 1, List.of("handle"), FsmHarness.START_WALL);
    }

    static SliceObservedValue failedRow(long version, Generation generation, ProcessIncarnation process, boolean fatal) {
        return new SliceObservedValue(SliceObservedState.FAILED, version, generation, process, Option.some("boom"), fatal, 1, List.of(), FsmHarness.START_WALL);
    }

    static ResolvedSlice slice(Artifact artifact, int instances, int minAvailable, Set<Artifact> dependencies) {
        return new ResolvedSlice(artifact, instances, minAvailable, false, dependencies, Option.none(), Option.none(), Option.none());
    }

    static AppBlueprintValue blueprint(String id, boolean registerOnly, String attempt, ResolvedSlice... slices) {
        var expanded = ExpandedBlueprint.expandedBlueprint(blueprintId(id), List.of(slices));

        return new AppBlueprintValue(expanded, registerOnly, attempt);
    }

    static ActivationDirectiveValue worker(String community) {
        return ActivationDirectiveValue.worker(community, "");
    }

    static CommunityValue community(String source, int targetSize, CommunityState state) {
        return new CommunityValue(source, ActivationDirectiveValue.WORKER, targetSize, state, FsmHarness.START_WALL, Option.none());
    }

    /// A governor announcement (a roster) of community members, at a fixed epoch so that re-announcing is a refresh.
    static GovernorAnnouncementValue roster(NodeId governor, boolean dissolved, NodeId... members) {
        return new GovernorAnnouncementValue(governor,
                                             members.length,
                                             List.of(members),
                                             "10.0.0.1:9000",
                                             FsmHarness.START_WALL,
                                             1L,
                                             Epoch.ZERO,
                                             Epoch.ZERO,
                                             HlcTimestamp.ZERO,
                                             dissolved);
    }
}
