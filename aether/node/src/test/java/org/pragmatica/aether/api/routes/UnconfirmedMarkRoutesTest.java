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
import org.pragmatica.aether.api.routes.UnconfirmedMarkRoutes.ClearRequest;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager.UnconfirmedClearRefusal;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.management.route.RouteTarget;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.http.handler.security.SecurityContextHolder;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
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

        when(manager.clearUnconfirmed(any(), anyString(), anyString(), anyBoolean())).thenAnswer(call -> {
            calls.add(((NodeId) call.getArgument(0)).id() + "|" + call.getArgument(1) + "|" + call.getArgument(2) + "|" + call.getArgument(3));

            return refusal == null
                   ? Promise.success(Unit.unit())
                   : refusal.<Unit> promise();
        });

        var node = mock(ManageableNode.class);

        when(node.clusterTopologyManager()).thenReturn(Option.some(manager));

        return UnconfirmedMarkRoutes.unconfirmedMarkRoutes(() -> node);
    }

    /// The operator is the principal the server bound for the request, read where the request runs.
    private Promise<UnconfirmedMarkRoutes.ClearResponse> clearAs(String key, String node, ClearRequest request) {
        var context = SecurityContext.securityContext(key).unwrap();
        var result = new AtomicReference<Promise<UnconfirmedMarkRoutes.ClearResponse>>();

        ScopedValue.where(SecurityContextHolder.scopedValue(), context).run(() -> result.set(routes().clear(node, request)));

        return result.get();
    }

    @Test
    void clear_passesTheNodeTheBoundPrincipalAsOperatorTheAttestedInstanceAndForce() {
        var principal = SecurityContext.securityContext("alice-key").unwrap().principal().value();
        var response = clearAs("alice-key", "core-old", new ClearRequest(" i-42 ", true)).await();

        assertThat(principal).isNotEqualTo("unknown");
        assertThat(calls).containsExactly("core-old|" + principal + "|i-42|true");
        response.onSuccess(body -> assertThat(body).isEqualTo(new UnconfirmedMarkRoutes.ClearResponse("core-old", "i-42", principal, true)))
                .onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));
    }

    @Test
    void clear_withNoBoundPrincipal_isAttributedToUnknown_andAnAbsentForceIsOff() {
        routes().clear("core-old", new ClearRequest("i-42", null)).await();

        assertThat(calls).containsExactly("core-old|unknown|i-42|false");
    }

    /// The JSON the CLI sends (`ClusterClearUnconfirmedCommandTest#body_isTheJsonThisRouteReads` pins the same literals) is what this route reads.
    @Test
    void theRequestBodyKeys_areInstanceAndForce() {
        var mapper = org.pragmatica.json.JsonMapper.defaultJsonMapper();
        var parsed = mapper.readString("{\"instance\":\"i-42\",\"force\":true}", ClearRequest.class).unwrap();
        var defaulted = mapper.readString("{\"instance\":\"i-42\"}", ClearRequest.class).unwrap();

        assertThat(parsed).isEqualTo(new ClearRequest("i-42", true));
        assertThat(defaulted.force()).as("force is optional").isNull();
    }

    @Test
    void clear_withoutAnInstance_is400_andClearsNothing() {
        for (var request : new ClearRequest[] {new ClearRequest(null, false), new ClearRequest("   ", false)}) {
            var response = routes().clear("core-old", request).await();

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
        assertThat(statusOf(new UnconfirmedClearRefusal.InstanceStillListed(new NodeId("core-old"), "i-1", "RUNNING"))).isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf(new UnconfirmedClearRefusal.ProviderUnreachable(new NodeId("core-old"), "down"))).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void aLiveNodeRefusal_reachesTheCallerAsTheConflict() {
        refusal = new UnconfirmedClearRefusal.NodeLive(new NodeId("core-old"));

        var response = routes().clear("core-old", new ClearRequest("i-42", false)).await();

        response.onFailure(cause -> assertThat(cause).isInstanceOf(ManagementServerError.Conflict.class));
        assertThat(response.isFailure()).isTrue();
    }

    @Test
    void aNodeWithoutATopologyManager_refusesInsteadOfThrowing() {
        var node = mock(ManageableNode.class);

        when(node.clusterTopologyManager()).thenReturn(Option.none());

        var response = UnconfirmedMarkRoutes.unconfirmedMarkRoutes(() -> node).clear("core-old", new ClearRequest("i-42", false)).await();

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
