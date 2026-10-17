// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.Map;

import org.pragmatica.aether.api.ManagementApiResponses.CommunitiesResponse;
import org.pragmatica.aether.api.ManagementApiResponses.CommunityInfo;
import org.pragmatica.aether.api.routes.CommunityRouteError.CommunityNotFound;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.QueryParams;
import org.pragmatica.http.routing.RequestContext;
import org.pragmatica.http.routing.Route;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// #1652: `/cluster/communities` reports each community as the union of its two committed records, with
/// a missing half shown as absent rather than defaulted, and the leader's live-member count passed through
/// exactly as the node observes it — absent when unobserved, never a stand-in zero.
class CommunityRoutesTest {
    private static final NodeId GOVERNOR = NodeId.nodeId("worker-1").unwrap();
    private static final NodeId WORKER_B = NodeId.nodeId("worker-2").unwrap();

    private static final LeaderValue LEADER_VALUE = LeaderValue.leaderValue(NodeId.nodeId("core-1").unwrap(), 1L);

    private KVStore<AetherKey, AetherValue> kvStore;
    private Map<String, Integer> liveCounts;

    @BeforeEach
    void setUp() {
        kvStore = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        liveCounts = Map.of();
        commitLeader();
    }

    @Test
    void list_reportsTheUnionOfBothRecords_withAMissingHalfAbsent() {
        commit(CommunityKey.communityKey("both"), community(CommunityState.ACTIVE, 2));
        commit(GovernorAnnouncementKey.forCommunity("both"), roster(List.of(WORKER_B, GOVERNOR)));
        commit(CommunityKey.communityKey("minted-only"), community(CommunityState.FORMING, 3));
        commit(GovernorAnnouncementKey.forCommunity("roster-only"), roster(List.of(GOVERNOR)));

        var communities = list().communities();

        assertThat(communities).extracting(CommunityInfo::communityId)
                               .containsExactly("both", "minted-only", "roster-only");
        assertThat(communities.get(0)).extracting(CommunityInfo::state,
                                                  CommunityInfo::targetSize,
                                                  CommunityInfo::governorId,
                                                  CommunityInfo::members,
                                                  CommunityInfo::memberCount)
                                      .containsExactly(some("ACTIVE"),
                                                       some(2),
                                                       some("worker-1"),
                                                       List.of("worker-1", "worker-2"),
                                                       2);
        assertThat(communities.get(1)).extracting(CommunityInfo::state,
                                                  CommunityInfo::governorId,
                                                  CommunityInfo::members,
                                                  CommunityInfo::communityTerm)
                                      .containsExactly(some("FORMING"), none(), List.of(), none());
        assertThat(communities.get(2)).extracting(CommunityInfo::state,
                                                  CommunityInfo::targetSize,
                                                  CommunityInfo::governorId)
                                      .containsExactly(none(), none(), some("worker-1"));
    }

    @Test
    void list_passesTheLeadersLiveCountThrough_andLeavesItAbsentWhereUnobserved() {
        commit(CommunityKey.communityKey("observed"), community(CommunityState.DEGRADED, 3));
        commit(CommunityKey.communityKey("unobserved"), community(CommunityState.ACTIVE, 3));
        liveCounts = Map.of("observed", 1);

        assertThat(list().communities()).extracting(CommunityInfo::liveMembers)
                                        .containsExactly(some(1), none());
    }

    @Test
    void list_isEmpty_whenNoCommunityIsCommitted() {
        assertThat(list().communities()).isEmpty();
    }

    @Test
    void detail_returnsTheNamedCommunity() {
        commit(CommunityKey.communityKey("east"), community(CommunityState.ACTIVE, 2));
        commit(CommunityKey.communityKey("west"), community(CommunityState.FORMING, 4));

        detail("west").onFailure(cause -> fail(cause.message()))
                      .onSuccess(info -> assertThat(info).extracting(CommunityInfo::communityId,
                                                                     CommunityInfo::state,
                                                                     CommunityInfo::targetSize)
                                                         .containsExactly("west", some("FORMING"), some(4)));
    }

    @Test
    void detail_failsWith404_whenNoRecordExists() {
        commit(CommunityKey.communityKey("east"), community(CommunityState.ACTIVE, 2));

        detail("nowhere").onSuccess(info -> fail("Expected 404, got " + info))
                         .onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(CommunityNotFound.class,
                                                                                      notFound -> assertThat(notFound.httpStatus())
                                                                                                                      .isEqualTo(HttpStatus.NOT_FOUND)));
    }

    @Test
    void routes_registerListAndDetail_underTheDeclaredRouteNames() {
        assertThat(routes()).extracting(Route::name)
                            .containsExactly(ManagementRoute.CLUSTER_COMMUNITIES.name(),
                                             ManagementRoute.CLUSTER_COMMUNITY_GET.name());
    }

    private CommunitiesResponse list() {
        return routes().getFirst()
                       .handler()
                       .handle(null)
                       .await()
                       .onFailure(cause -> fail(cause.message()))
                       .map(CommunitiesResponse.class::cast)
                       .unwrap();
    }

    private Result<CommunityInfo> detail(String communityId) {
        return routes().get(1)
                       .handler()
                       .handle(new PathRequestContext(List.of(communityId)))
                       .await()
                       .map(CommunityInfo.class::cast);
    }

    private List<Route<?>> routes() {
        return CommunityRoutes.communityRoutes(this::node)
                              .routes()
                              .toList();
    }

    /// Community records are `LeaderAuthorized`: the applier refuses a plain `Put` of one, so they are
    /// committed the way production commits them — a leader transaction under the committed leader.
    private void commit(AetherKey key, AetherValue value) {
        kvStore.process(kvStore.createBatch(List.of(new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key,
                                                                                                           UUID.randomUUID()
                                                                                                               .toString(),
                                                                                                           LEADER_VALUE,
                                                                                                           List.of(),
                                                                                                           List.of(new KVCommand.Mutation<>(key,
                                                                                                                                            kvStore.get(key),
                                                                                                                                            some(value)))))));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void commitLeader() {
        kvStore.process(kvStore.createBatch((List) List.of(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER_VALUE))));
    }

    private static CommunityValue community(CommunityState state, int targetSize) {
        return CommunityValue.communityValue("", AetherValue.ActivationDirectiveValue.WORKER, targetSize, state, 1L, none());
    }

    private static GovernorAnnouncementValue roster(List<NodeId> members) {
        return new GovernorAnnouncementValue(GOVERNOR,
                                             members.size(),
                                             members,
                                             "10.0.0.1:9000",
                                             1700000000000L,
                                             5L,
                                             Epoch.ZERO,
                                             Epoch.ZERO,
                                             HlcTimestamp.ZERO,
                                             false);
    }

    private ManageableNode node() {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class<?>[] {ManageableNode.class},
                                                       (_, method, args) -> switch (method.getName()) {
                                                           case "kvStore" -> kvStore;
                                                           case "communityLiveMembers" -> Option.option(liveCounts.get((String) args[0]));
                                                           default -> throw new UnsupportedOperationException("Not in test proxy: " + method.getName());
                                                       });
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

    private static <T> T unsupported(String methodName) {
        return fail("Not touched by the community detail handler: " + methodName);
    }

    /// The detail handler reads the id through `pathParam(0)`, a default method over `pathParams()`, so a
    /// real implementation is used; everything else fails loudly if touched.
    private record PathRequestContext(List<String> pathParams) implements RequestContext {
        @Override
        public <T> Result<T> fromJson(TypeToken<T> literal) { return unsupported("fromJson"); }
        @Override
        public Route<?> route() { return unsupported("route"); }
        @Override
        public HttpHeaders responseHeaders() { return unsupported("responseHeaders"); }
        @Override
        public String requestId() { return unsupported("requestId"); }
        @Override
        public HttpMethod method() { return unsupported("method"); }
        @Override
        public String path() { return unsupported("path"); }
        @Override
        public Headers headers() { return unsupported("headers"); }
        @Override
        public QueryParams queryParams() { return unsupported("queryParams"); }
        @Override
        public byte[] body() { return unsupported("body"); }
    }
}
