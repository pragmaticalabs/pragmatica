// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

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
public sealed interface CommunityLiveMembers {
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
