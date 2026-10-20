// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.ManagementServerError;
import org.pragmatica.aether.api.routes.CommunityPlacementRoutes.ClearRequest;
import org.pragmatica.aether.api.routes.CommunityPlacementRoutes.ClearResponse;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.cluster.CommunityPlacementReconciler;
import org.pragmatica.aether.deployment.cluster.CommunityPlacementReconciler.ParkedClear;
import org.pragmatica.aether.deployment.cluster.CommunityPlacementReconciler.ParkedClearRefusal;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.http.handler.security.SecurityContextHolder;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.management.route.RouteTarget;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherValue.PlacementOperationPhase;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


/// #241: the operator clear route for a parked placement operation. The request names the community (path) and what the operator checked (body);
/// the server's authenticated principal is the operator; refusals are statements about the cluster's state (404 no operation, 409 otherwise).
class CommunityPlacementRoutesTest {
    private static final ParkedClear CLEARED = new ParkedClear("eu-w-1",
                                                               "op-1",
                                                               PlacementOperationPhase.DRAIN_UNCERTAIN,
                                                               new NodeId("placement-op-1"),
                                                               Option.some(new NodeId("old-worker")));
    private final List<String> calls = new ArrayList<>();
    private Cause refusal;

    private CommunityPlacementRoutes routes() {
        var reconciler = mock(CommunityPlacementReconciler.class);

        when(reconciler.clearParked(anyString(), anyString(), anyString())).thenAnswer(call -> {
            calls.add(call.getArgument(0) + "|" + call.getArgument(1) + "|" + call.getArgument(2));

            return refusal == null
                   ? Promise.success(CLEARED)
                   : refusal.<ParkedClear> promise();
        });

        var manager = mock(ClusterTopologyManager.class);

        when(manager.installedCommunityPlacement()).thenReturn(Option.some(reconciler));

        var node = mock(ManageableNode.class);

        when(node.clusterTopologyManager()).thenReturn(Option.some(manager));

        return CommunityPlacementRoutes.communityPlacementRoutes(() -> node);
    }

    /// The operator is the principal the server bound for the request, read where the request runs.
    private Promise<ClearResponse> clearAs(String key, String community, ClearRequest request) {
        var context = SecurityContext.securityContext(key).unwrap();
        var result = new AtomicReference<Promise<ClearResponse>>();

        ScopedValue.where(SecurityContextHolder.scopedValue(), context).run(() -> result.set(routes().clear(community, request)));

        return result.get();
    }

    @Test
    void clear_passesTheCommunityTheBoundPrincipalAsOperatorAndTheTrimmedReason() {
        var principal = SecurityContext.securityContext("alice-key").unwrap().principal().value();
        var response = clearAs("alice-key", "eu-w-1", new ClearRequest(" previous node is gone "));

        assertThat(principal).isNotEqualTo("unknown");
        assertThat(calls).containsExactly("eu-w-1|" + principal + "|previous node is gone");
        response.await()
                .onSuccess(body -> assertThat(body).isEqualTo(new ClearResponse("eu-w-1",
                                                                                "op-1",
                                                                                "DRAIN_UNCERTAIN",
                                                                                "placement-op-1",
                                                                                "old-worker",
                                                                                principal)))
                .onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));
    }

    @Test
    void clear_withNoBoundPrincipal_isAttributedToUnknown() {
        routes().clear("eu-w-1", new ClearRequest("checked")).await();

        assertThat(calls).containsExactly("eu-w-1|unknown|checked");
    }

    /// The JSON the CLI sends (`ClusterClearPlacementCommandTest#body_isTheJsonThisRouteReads` pins the same literal) is what this route reads.
    @Test
    void theRequestBodyKey_isReason() {
        var parsed = org.pragmatica.json.JsonMapper.defaultJsonMapper().readString("{\"reason\":\"checked\"}", ClearRequest.class).unwrap();

        assertThat(parsed).isEqualTo(new ClearRequest("checked"));
    }

    @Test
    void clear_withoutAReason_is400_andClearsNothing() {
        for (var request : new ClearRequest[] {new ClearRequest(null), new ClearRequest("   ")}) {
            var response = routes().clear("eu-w-1", request).await();

            assertThat(response.isFailure()).isTrue();
            response.onFailure(cause -> assertThat(cause).isInstanceOf(ManagementServerError.InvalidRequest.class));
        }

        assertThat(calls).isEmpty();
    }

    @Test
    void refusals_answer404ForNoOperation_and409ForTheRest() {
        assertThat(statusOf(new ParkedClearRefusal.NoOperation("eu-w-1"))).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusOf(new ParkedClearRefusal.NotParked("eu-w-1", PlacementOperationPhase.AWAITING_READY))).isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf(new ParkedClearRefusal.Changed("eu-w-1"))).isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf(new ParkedClearRefusal.NotActive())).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void aNotParkedRefusal_reachesTheCallerAsTheConflict() {
        refusal = new ParkedClearRefusal.NotParked("eu-w-1", PlacementOperationPhase.AWAITING_READY);

        var response = routes().clear("eu-w-1", new ClearRequest("checked")).await();

        assertThat(response.isFailure()).isTrue();
        response.onFailure(cause -> assertThat(cause).isInstanceOf(ManagementServerError.Conflict.class));
    }

    @Test
    void aNodeWithoutAPlacementReconciler_refusesInsteadOfThrowing() {
        var node = mock(ManageableNode.class);

        when(node.clusterTopologyManager()).thenReturn(Option.none());

        var response = CommunityPlacementRoutes.communityPlacementRoutes(() -> node).clear("eu-w-1", new ClearRequest("checked")).await();

        assertThat(response.isFailure()).isTrue();
        response.onFailure(cause -> assertThat(cause).isInstanceOf(ManagementServerError.Conflict.class));
    }

    @Test
    void theRouteIsLeaderBound_aPost_withTheDocumentedPath() {
        assertThat(ManagementRoute.COMMUNITY_PLACEMENT_CLEAR.target()).isEqualTo(RouteTarget.LEADER);
        assertThat(ManagementRoute.COMMUNITY_PLACEMENT_CLEAR.assemble(List.of("eu-w-1")).unwrap()).endsWith("/cluster/communities/placement/clear/eu-w-1");
    }

    private static HttpStatus statusOf(Cause refusal) {
        return ((HttpStatusAware) CommunityPlacementRoutes.asManagementError(refusal)).httpStatus();
    }
}
