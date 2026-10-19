// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentState.Active;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// Community placement-query seam extracted (move-only) from {@link Active}. Owns the
/// community-governor KV queries ({@code activeCommunityIds}/{@code communityGovernor}/
/// {@code buildCommunityWorkerMap}). The worker-slice-directive distribution that used to live here
/// lost its only caller in #1390 and was deleted (#1535).
record CommunityPlacementPlanner(Active active) {
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
}
