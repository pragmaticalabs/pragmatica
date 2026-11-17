// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Set;

import org.pragmatica.aether.deployment.cluster.community.CommunityLiveMembers;
import org.pragmatica.aether.deployment.cluster.community.CommunityLivenessView;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import io.netty.buffer.ByteBuf;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;


/// #3159: under FSM2 the management API serves the count that decided the community's state, `Communities.liveMembers` of the committed model,
/// never the leader's liveness view. Each test names the line whose substitution turns it red.
class CommunityObservedCountTest {
    private static final String COMMUNITY = "east";

    private static ClusterModel modelWithMembers(boolean dissolved) {
        var m = new ClusterModel();

        m.seams = Seams.UNWIRED.withCountedCores(Set.of(CORE_1, CORE_2));
        m.rosters = m.rosters.put(COMMUNITY, roster(dissolved));
        m.directives = m.directives.put(CORE_1, ActivationDirectiveValue.worker(COMMUNITY, ""));
        m.directives = m.directives.put(CORE_2, ActivationDirectiveValue.worker(COMMUNITY, ""));

        return m;
    }

    private static GovernorAnnouncementValue roster(boolean dissolved) {
        return new GovernorAnnouncementValue(CORE_1,
                                             2,
                                             List.of(CORE_1, CORE_2),
                                             "10.0.0.1:9000",
                                             1700000000000L,
                                             5L,
                                             Epoch.ZERO,
                                             Epoch.ZERO,
                                             HlcTimestamp.ZERO,
                                             dissolved);
    }

    /// Red when: `observed` reads anything but `liveMembers(model, id)` of the built model.
    @Test
    void observed_isTheModelCount_ofTheBuiltModel() {
        var m = modelWithMembers(false);

        assertThat(Communities.observed(new ClusterState.Active(m), COMMUNITY)).isEqualTo(Option.some(Communities.liveMembers(m, COMMUNITY)));
        assertThat(Communities.observed(new ClusterState.Active(m), COMMUNITY)).isEqualTo(Option.some(2));
    }

    /// Red when: a dormant or rebuilding model, or a missing or dissolved roster, is served as a count (zero would pass for "nothing alive").
    @Test
    void observed_isAbsent_withoutABuiltModelOrALiveRoster() {
        assertThat(Communities.observed(ClusterState.initial(), COMMUNITY)).isEqualTo(Option.none());
        assertThat(Communities.observed(new ClusterState.Rebuilding(1L, List.of()), COMMUNITY)).isEqualTo(Option.none());
        assertThat(Communities.observed(new ClusterState.Active(new ClusterModel()), COMMUNITY)).isEqualTo(Option.none());
        assertThat(Communities.observed(new ClusterState.Active(modelWithMembers(true)), COMMUNITY)).isEqualTo(Option.none());
    }

    /// Red when: the FSM2 arm of `observedByLeader` serves the liveness view (here: every member absent, so 0) instead of the committed model (2).
    @Test
    void observedByLeader_underFsm2_servesTheModelCount_notTheLivenessView() {
        CommunityLivenessView everyoneAbsent = node -> true;
        var state = new ClusterState.Active(modelWithMembers(false));

        assertThat(CommunityLiveMembers.observedByLeader(true, Option.some(() -> state), emptyStore(), everyoneAbsent, COMMUNITY)).isEqualTo(Option.some(2));
    }

    /// Red when: a non-leader serves a count, or the FSM2 arm is gated on the liveness view being wired (the unwired view is not consulted).
    @Test
    void observedByLeader_underFsm2_isLeaderOnly_andIgnoresTheViewWiring() {
        var state = new ClusterState.Active(modelWithMembers(false));

        assertThat(CommunityLiveMembers.observedByLeader(false, Option.some(() -> state), emptyStore(), node -> false, COMMUNITY)).isEqualTo(Option.none());
        assertThat(CommunityLiveMembers.observedByLeader(true, Option.some(() -> state), emptyStore(), CommunityLivenessView.unwired(), COMMUNITY)).isEqualTo(Option.some(2));
    }

    private static KVStore<AetherKey, AetherValue> emptyStore() {
        return new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
