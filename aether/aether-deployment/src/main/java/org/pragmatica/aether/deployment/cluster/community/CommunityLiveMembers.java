// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.community;

import java.util.function.Supplier;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterState;
import org.pragmatica.aether.deployment.cluster.fsm2.Communities;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// The live-member count of one community, as the leader sees it — the number the per-community FSM
/// compares against the viability floor, and the number `GET /cluster/communities` reports (#1652).
/// One computation serves both, so the route can never show a count the FSM did not decide on.
///
/// Authority rosters describe assignment, not liveness. A member counts only when it is still on the
/// committed roster, still directed to this community, and not observed absent by `liveness`.
///
/// [Option#none] when there is no committed roster to count against (no announcement, or a dissolved
/// one) — "nothing to count", which is not the same fact as "zero members alive".
///
/// ## Who may observe it (#3159)
/// The count is the LEADER's observation, taken from the same source that decided the state: LEGACY the roster against the leader's wired
/// liveness view, FSM2 the committed model ([#observedByLeader]). Anywhere else — a node that is not the leader, or (LEGACY) a
/// liveness view that is still [CommunityLivenessView#unwired], or (FSM2) a model that is not built — it is [Option#none], never a count computed from the everyone-alive default.
public sealed interface CommunityLiveMembers {
    /// The management-API reading, from the source of the engine that decided the community's state (#3159). Only the leader observes.
    /// - LEGACY (`fsm2Model` none): the committed KV roster counted against the wired liveness view, the condition LEGACY applied through
    ///   `ClusterDeploymentManager` (its context active, its view wired), with leadership as the engine-independent form of "active".
    /// - FSM2: [Communities#observed] of the committed model the FSM2 state was evaluated on, never the liveness view — FSM2 decides ACTIVE and
    ///   DEGRADED from `Communities.liveMembers`, so a count from any other source can disagree with the state it is shown beside.
    static Option<Integer> observedByLeader(boolean leader,
                                            Option<Supplier<ClusterState>> fsm2Model,
                                            KVStore<AetherKey, AetherValue> kvStore,
                                            CommunityLivenessView liveness,
                                            String communityId) {
        return leader
               ? fsm2Model.fold(() -> legacyObserved(kvStore, liveness, communityId),
                                model -> Communities.observed(model.get(), communityId))
               : Option.none();
    }

    private static Option<Integer> legacyObserved(KVStore<AetherKey, AetherValue> kvStore,
                                                  CommunityLivenessView liveness,
                                                  String communityId) {
        return CommunityLivenessView.isWired(liveness)
               ? communityLiveMembers(kvStore, liveness, communityId)
               : Option.none();
    }

    static Option<Integer> communityLiveMembers(KVStore<AetherKey, AetherValue> kvStore,
                                                CommunityLivenessView liveness,
                                                String communityId) {
        return kvStore.getTyped(GovernorAnnouncementKey.forCommunity(communityId),
                                GovernorAnnouncementValue.class)
                      .filter(value -> !value.dissolved())
                      .map(value -> countLive(kvStore, liveness, communityId, value));
    }

    private static int countLive(KVStore<AetherKey, AetherValue> kvStore,
                                 CommunityLivenessView liveness,
                                 String communityId,
                                 GovernorAnnouncementValue value) {
        return (int) value.members()
                          .stream()
                          .distinct()
                          .filter(node -> countsAsLive(kvStore, liveness, node, communityId))
                          .count();
    }

    private static boolean countsAsLive(KVStore<AetherKey, AetherValue> kvStore,
                                        CommunityLivenessView liveness,
                                        NodeId node,
                                        String communityId) {
        return directedTo(kvStore, node, communityId) && !liveness.isAbsent(node);
    }

    private static boolean directedTo(KVStore<AetherKey, AetherValue> kvStore, NodeId node, String communityId) {
        return kvStore.getTyped(ActivationDirectiveKey.activationDirectiveKey(node),
                                ActivationDirectiveValue.class)
                      .filter(directive -> directive.communityId()
                                                    .equals(communityId))
                      .isPresent();
    }

    record unused() implements CommunityLiveMembers {}
}
