// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.AllocationPool;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentState.Active;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.WorkerSliceDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkerSliceDirectiveValue;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Community/worker placement-distribution seam extracted (move-only) from {@link Active}. Owns the
/// community-governor KV queries ({@code activeCommunityIds}/{@code communityGovernor}/
/// {@code activeCommunities}/{@code buildCommunityWorkerMap}) and the worker-slice-directive
/// distribution that places instances across communities proportionally to member count, falling
/// back to a single worker directive when no communities are announced.
record CommunityPlacementPlanner(Active active) {
    private static final Logger log = LoggerFactory.getLogger(CommunityPlacementPlanner.class);

    /// The authoritative DESIRED community set (worker-membership-spec D1 / §3.3): the committed,
    /// leader-authored [`CommunityKey`]/[`CommunityValue`] facts, filtered to the strictly `ACTIVE`
    /// state. Slice 2's per-community FSM (leader-evaluated in `reconcile()`) now drives a community
    /// to `ACTIVE` once its observed live membership reaches the viability floor, so placement gates
    /// on `ACTIVE` and excludes the still-`FORMING`, transiently-`DEGRADED`, and terminal
    /// `DISSOLVING`/`DISSOLVED` communities. (The previous source enumerated the governor-OWNED
    /// [`GovernorAnnouncementKey`], which is the OBSERVED statement; that read is preserved in the
    /// governor/worker-map methods below.)
    Set<String> activeCommunityIds() {
        var ids = new LinkedHashSet<String>();

        active.ctx()
              .kvStore()
              .forEach(CommunityKey.class,
                       CommunityValue.class,
                       (key, value) -> collectDesiredCommunity(ids, key, value));

        return Set.copyOf(ids);
    }

    private static void collectDesiredCommunity(Set<String> ids, CommunityKey key, CommunityValue value) {
        if (isDesiredState(value.state())) {
            ids.add(key.communityId());
        }
    }

    /// A community counts toward the desired set only once the per-community FSM has driven it to
    /// `ACTIVE` (worker-membership-spec §3.3). `FORMING` (not yet viable), `DEGRADED` (lost quorum),
    /// and the terminal `DISSOLVING`/`DISSOLVED` teardown states are all excluded.
    private static boolean isDesiredState(CommunityState state) {
        return state == CommunityState.ACTIVE;
    }

    private Option<GovernorAnnouncementValue> communityGovernor(String communityId) {
        return active.ctx()
                     .kvStore()
                     .get(GovernorAnnouncementKey.forCommunity(communityId))
                     .filter(GovernorAnnouncementValue.class::isInstance)
                     .map(GovernorAnnouncementValue.class::cast);
    }

    Map<String, List<NodeId>> buildCommunityWorkerMap() {
        var communityIds = activeCommunityIds();

        if (communityIds.isEmpty()) {
            return Map.of();
        }

        var result = new HashMap<String, List<NodeId>>();

        communityIds.forEach(communityId -> communityGovernor(communityId).onPresent(announcement -> placeableMembers(announcement).onPresent(members -> result.put(communityId,
                                                                                                                                                                    members))));

        return Map.copyOf(result);
    }

    /// The members of `announcement` this core may actually place work on — its self-reported list minus
    /// those positively observed absent. Empty (community omitted entirely) when nothing in it is
    /// reachable.
    ///
    /// #590 at the PLACEMENT grain. The community-state FSM already demotes a community whose observed
    /// live membership falls below the viability floor, but this planner read `announcement.members()`
    /// raw — the community's own claim about itself, which under partition does not expire, it FREEZES.
    /// So a cut-off community stayed weighted at its full size and the core kept issuing directives
    /// naming nodes it could not reach: the exact consequence #590 describes, at a grain the
    /// ACTIVE/DEGRADED gate cannot catch, because a community can be comfortably above the floor and
    /// still have lost members.
    ///
    /// Fail-safe: `isAbsent` reports only POSITIVELY observed absence, and is `false` when the collector
    /// is unwired — so an unwired deployment places exactly as it did before.
    private Option<List<NodeId>> placeableMembers(GovernorAnnouncementValue announcement) {
        var liveness = active.ctx().communityLiveness();

        if (announcement.members().isEmpty()) {
            // No published list: the governor is the only identity there is to check, which still catches
            // the case this exists for — a whole community gone silent.
            return liveness.isAbsent(announcement.governorId())
                   ? Option.none()
                   : Option.some(List.of(announcement.governorId()));
        }

        var live = liveness.liveMembers(announcement.members());

        return live.isEmpty()
               ? Option.none()
               : Option.some(live);
    }

    /// Observed live size, used for proportional weighting: the declared count minus those positively
    /// observed absent. Weighting a shrunken community at its FROZEN size over-provisions it with
    /// instances it has no members left to run.
    ///
    /// Deliberately NOT `placeableMembers(...).size()`: when a community publishes no member list there
    /// is exactly one identity to check (the governor), and collapsing that to a weight of 1 would
    /// re-weight every list-less community on no evidence at all. Absent evidence, the declared count
    /// stands — same semantics as `Active.observedLiveMembers`, which computes this for the viability
    /// gate.
    private int liveMemberCount(GovernorAnnouncementValue announcement) {
        var liveness = active.ctx().communityLiveness();

        if (announcement.members().isEmpty()) {
            return liveness.isAbsent(announcement.governorId())
                   ? 0
                   : announcement.memberCount();
        }

        return liveness.liveMembers(announcement.members())
                       .size();
    }

    private Map<String, GovernorAnnouncementValue> activeCommunities() {
        var communityIds = activeCommunityIds();

        if (communityIds.isEmpty()) {
            return Map.of();
        }

        var result = new HashMap<String, GovernorAnnouncementValue>();

        communityIds.forEach(communityId -> communityGovernor(communityId).onPresent(announcement -> result.put(communityId,
                                                                                                                announcement)));

        return Map.copyOf(result);
    }

    // Fire-and-forget placement orchestration: dispatches community/worker directive writes whose
    // outcomes are handled inline; the allocation-engine caller ignores the return. void is the contract.
    @Contract
    void distributeWorkerOrCommunity(Artifact artifact, int desiredInstances, String placement, AllocationPool pool) {
        if (pool.hasCommunities()) {
            distributeToCommunities(artifact, desiredInstances, placement);
        } else {
            writeWorkerDirective(artifact, desiredInstances, placement);
        }
    }

    private void writeWorkerDirective(Artifact artifact, int targetInstances, String placement) {
        var key = WorkerSliceDirectiveKey.workerSliceDirectiveKey(artifact);
        var value = WorkerSliceDirectiveValue.workerSliceDirectiveValue(artifact, targetInstances, placement);
        var command = new KVCommand.Put<AetherKey, AetherValue>(key, value);

        active.ctx()
              .cluster()
              .apply(List.of(command))
              .onSuccess(_ -> log.info("Written worker directive for {} with {} instances", artifact, targetInstances))
              .onFailure(cause -> log.error("Failed to write worker directive for {}: {}",
                                            artifact,
                                            cause.message()));
    }

    private void writeWorkerDirective(Artifact artifact, int targetInstances, String placement, String communityId) {
        var key = WorkerSliceDirectiveKey.workerSliceDirectiveKey(artifact, communityId);
        var value = WorkerSliceDirectiveValue.workerSliceDirectiveValue(artifact,
                                                                        targetInstances,
                                                                        placement,
                                                                        communityId);
        var command = new KVCommand.Put<AetherKey, AetherValue>(key, value);

        active.ctx()
              .cluster()
              .apply(List.of(command))
              .onSuccess(_ -> log.info("Written worker directive for {} community '{}' with {} instances",
                                       artifact,
                                       communityId,
                                       targetInstances))
              .onFailure(cause -> log.error("Failed to write worker directive for {} community '{}': {}",
                                            artifact,
                                            communityId,
                                            cause.message()));
    }

    private void distributeToCommunities(Artifact artifact, int desiredInstances, String placement) {
        var communities = activeCommunities();
        var totalMembers = communities.values().stream().mapToInt(this::liveMemberCount).sum();

        if (totalMembers == 0) {
            writeWorkerDirective(artifact, desiredInstances, placement);

            return;
        }

        var sorted = new ArrayList<>(communities.entrySet());

        sorted.sort(Comparator.<Map.Entry<String, GovernorAnnouncementValue>> comparingInt(e -> liveMemberCount(e.getValue())).reversed());
        var remaining = desiredInstances;

        for (var i = 0; i < sorted.size(); i++) {
            var share = computeCommunityShare(i, sorted, desiredInstances, totalMembers, remaining);

            if (share > 0) {
                writeWorkerDirective(artifact,
                                     share,
                                     placement,
                                     sorted.get(i).getKey());
                remaining -= share;
            }
        }

        assignRemainder(artifact, remaining, placement, sorted);
    }

    private int computeCommunityShare(int index,
                                      List<Map.Entry<String, GovernorAnnouncementValue>> sorted,
                                      int desiredInstances,
                                      int totalMembers,
                                      int remaining) {
        if (index == 0) {
            return computeLargestCommunityShare(sorted, desiredInstances, totalMembers, remaining);
        }

        var memberCount = liveMemberCount(sorted.get(index).getValue());
        var proportional = Math.max(1, Math.round((float) desiredInstances * memberCount / totalMembers));

        return Math.min(proportional, remaining);
    }

    private int computeLargestCommunityShare(List<Map.Entry<String, GovernorAnnouncementValue>> sorted,
                                             int desiredInstances,
                                             int totalMembers,
                                             int remaining) {
        var share = remaining;

        for (var j = 1; j < sorted.size(); j++) {
            var otherCount = liveMemberCount(sorted.get(j).getValue());

            share -= Math.max(1, Math.round((float) desiredInstances * otherCount / totalMembers));
        }

        return Math.min(Math.max(1, share), remaining);
    }

    private void assignRemainder(Artifact artifact,
                                 int remaining,
                                 String placement,
                                 List<Map.Entry<String, GovernorAnnouncementValue>> sorted) {
        if (remaining > 0) {
            writeWorkerDirective(artifact,
                                 remaining,
                                 placement,
                                 sorted.getFirst().getKey());
        }
    }
}
