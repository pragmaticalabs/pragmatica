// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.ManagementServerError;
import org.pragmatica.aether.api.routes.UnconfirmedMarkRoutes.ClearRequest;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager.UnconfirmedClearRefusal;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.management.route.RouteTarget;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


/// #2062: the operator clear route. The request names the node (path) and the instance the operator terminated (body); the server's authenticated
/// principal is the operator; refusals are statements about the cluster's state (404 no mark, 409 otherwise), never a server fault.
class UnconfirmedMarkRoutesTest {
    private final List<String> calls = new ArrayList<>();
    private Cause refusal;

    private UnconfirmedMarkRoutes routes() {
        var manager = mock(ClusterTopologyManager.class);

        when(manager.clearUnconfirmed(any(), anyString(), anyString())).thenAnswer(call -> {
            calls.add(((NodeId) call.getArgument(0)).id() + "|" + call.getArgument(1) + "|" + call.getArgument(2));

            return refusal == null
                   ? Promise.success(Unit.unit())
                   : refusal.<Unit> promise();
        });

        var node = mock(ManageableNode.class);

        when(node.clusterTopologyManager()).thenReturn(Option.some(manager));

        return UnconfirmedMarkRoutes.unconfirmedMarkRoutes(() -> node);
    }

    @Test
    void clear_passesTheNodeTheOperatorAndTheAttestedInstance_andAnswersThem() {
        var response = routes().clear("core-old", new ClearRequest("i-42"), "alice").await();

        assertThat(calls).containsExactly("core-old|alice|i-42");
        response.onSuccess(body -> assertThat(body).isEqualTo(new UnconfirmedMarkRoutes.ClearResponse("core-old", "i-42", "alice")))
                .onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));
    }

    @Test
    void clear_withoutAnInstance_is400_andClearsNothing() {
        for (var request : new ClearRequest[] {new ClearRequest(null), new ClearRequest("   ")}) {
            var response = routes().clear("core-old", request, "alice").await();

            assertThat(response.isFailure()).isTrue();
            response.onFailure(cause -> assertThat(cause).isInstanceOf(ManagementServerError.InvalidRequest.class));
        }

        assertThat(calls).isEmpty();
    }

    @Test
    void refusals_answer404ForNoMark_and409ForTheRest() {
        assertThat(statusOf(new UnconfirmedClearRefusal.NotMarked(new NodeId("core-old")))).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusOf(new UnconfirmedClearRefusal.NodeLive(new NodeId("core-old")))).isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf(new UnconfirmedClearRefusal.Changed(new NodeId("core-old")))).isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf(new UnconfirmedClearRefusal.NotActive())).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void aLiveNodeRefusal_reachesTheCallerAsTheConflict() {
        refusal = new UnconfirmedClearRefusal.NodeLive(new NodeId("core-old"));

        var response = routes().clear("core-old", new ClearRequest("i-42"), "alice").await();

        response.onFailure(cause -> assertThat(cause).isInstanceOf(ManagementServerError.Conflict.class));
        assertThat(response.isFailure()).isTrue();
    }

    @Test
    void aNodeWithoutATopologyManager_refusesInsteadOfThrowing() {
        var node = mock(ManageableNode.class);

        when(node.clusterTopologyManager()).thenReturn(Option.none());

        var response = UnconfirmedMarkRoutes.unconfirmedMarkRoutes(() -> node).clear("core-old", new ClearRequest("i-42"), "alice").await();

        assertThat(response.isFailure()).isTrue();
        response.onFailure(cause -> assertThat(cause).isInstanceOf(ManagementServerError.Conflict.class));
    }

    @Test
    void theRouteIsLeaderBound_aPost_withTheDocumentedPath() {
        assertThat(ManagementRoute.NODE_UNCONFIRMED_CLEAR.target()).isEqualTo(RouteTarget.LEADER);
        assertThat(ManagementRoute.NODE_UNCONFIRMED_CLEAR.assemble(List.of("core-old")).unwrap()).endsWith("/nodes/unconfirmed/clear/core-old");
    }

    private static HttpStatus statusOf(Cause refusal) {
        return ((HttpStatusAware) UnconfirmedMarkRoutes.asManagementError(refusal)).httpStatus();
    }
}
