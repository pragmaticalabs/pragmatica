// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.slice.MethodHandle;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1678 (v1670 P6): the policy that authorizes a request is the policy of the route that SERVES it. Sibling routes
/// of one slice share a base path (`GET /orders/{id}` and `GET /orders/{id}/admin` are both `/orders/`), and the
/// prefix pick alone returned whichever sibling was listed FIRST -- on every node, the host included: listed first,
/// the PUBLIC sibling authorized requests the admin handler served. Both declaration orders, through the REAL
/// publisher and the REAL slice router.
/// v1873 probe for #1916 (#755): the HOST's local resolution (`HttpRoutePublisher.resolveServed`) selects over
/// `HttpRouteDefinition` shapes, which carry no spacer slots, so it still matches by membership while each slice's own router
/// is positional. Two slices share `/users/` with the spacer at different slots; the request must be served by the one whose
/// declared position matches.
class SpacerPositionHostResolutionProbeTest {
    private static final NodeId SELF = NodeId.nodeId("self-spacer").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:unused:1.0.0").unwrap();
    private static final Artifact ID_THEN_EDIT = Artifact.artifact("org.example:aaa-id-then-edit:1.0.0").unwrap();
    private static final Artifact EDIT_THEN_ID = Artifact.artifact("org.example:zzz-edit-then-id:1.0.0").unwrap();

    /// The slice's OWN router, as a node publishes it (the publisher re-wraps every route for observability): positional.
    @Test
    void oneSliceBothPositions_thePublishedRouterSelectsByPosition() {
        var publisher = HttpRoutePublisher.httpRoutePublisher(SELF, new SilentCluster());
        var both = Artifact.artifact("org.example:both:1.0.0").unwrap();

        publishInto(publisher, both, new SpacerPositionSliceRoutes.BothSlice());
        var router = publisher.getSliceRouter(both).unwrap();

        assertThat(body(router, "/users/42/edit")).as("CONTROL").contains("id-then-edit-42");
        assertThat(body(router, "/users/edit/42")).contains("edit-then-id-42");
    }

    /// Control for the probe above: the SAME routes in a router built directly (no publisher) select by position.
    @Test
    void control_oneSliceBothPositions_aDirectRouterSelectsByPosition() {
        var router = new SpacerPositionSliceRoutes.Both().create(new SpacerPositionSliceRoutes.BothSlice());

        assertThat(body(router, "/users/edit/42")).contains("edit-then-id-42");
    }

    private static String body(org.pragmatica.aether.http.adapter.SliceRouter router, String path) {
        return new String(router.handle(HttpRequestContext.httpRequestContext(path, "GET", Map.of(), Map.of(), "req"))
                                .await(timeSpan(10).seconds())
                                .unwrap()
                                .body());
    }

    /// TRIPWIRE: the host's local resolution (`resolveServed`, over `HttpRouteDefinition`, which carries no slots) still picks a
    /// slice by spacer MEMBERSHIP, so with two slices under one base the request reaches the wrong slice, whose positional router
    /// answers 404. Reddens when the definitions carry positions: delete it and enable the real assertion below.
    @Test
    void currently_twoSlicesSplitBySpacerPosition_hostResolutionPicksByMembership_tripwire() {
        var publisher = HttpRoutePublisher.httpRoutePublisher(SELF, new SilentCluster());

        publishInto(publisher, ID_THEN_EDIT, new SpacerPositionSliceRoutes.IdThenEditSlice());
        publishInto(publisher, EDIT_THEN_ID, new SpacerPositionSliceRoutes.EditThenIdSlice());

        assertThat(servedBody(publisher, "/users/42/edit")).as("CONTROL").contains("id-then-edit-42");
        assertThat(servedBody(publisher, "/users/edit/42"))
            .as("host resolution now positional: delete this tripwire and enable the real assertion")
            .contains("\"status\":404");
    }

    @org.junit.jupiter.api.Disabled("#755 follow-up: HttpRouteDefinition carries no spacer slots; enable when the tripwire above reddens")
    @Test
    void editThenId_isServedByTheSliceThatDeclaredThatPosition() {
        var publisher = HttpRoutePublisher.httpRoutePublisher(SELF, new SilentCluster());

        publishInto(publisher, ID_THEN_EDIT, new SpacerPositionSliceRoutes.IdThenEditSlice());
        publishInto(publisher, EDIT_THEN_ID, new SpacerPositionSliceRoutes.EditThenIdSlice());

        assertThat(servedBody(publisher, "/users/42/edit")).as("CONTROL").contains("id-then-edit-42");
        assertThat(servedBody(publisher, "/users/edit/42")).contains("edit-then-id-42");
    }

    private static final Artifact PUBLIC_ORDERS = Artifact.artifact("org.example:orders-public:1.0.0").unwrap();
    private static final Artifact ADMIN_ORDERS = Artifact.artifact("org.example:orders-admin:1.0.0").unwrap();

    private static void publishInto(HttpRoutePublisher publisher, Artifact artifact, Object slice) {
        publisher.publishRoutes(artifact, SpacerPositionHostResolutionProbeTest.class.getClassLoader(), slice, stubInvokerFacade())
                 .await(timeSpan(30).seconds())
                 .onFailure(cause -> Assertions.fail("route publication must succeed: " + cause.message()));
    }

    /// Dispatch through the router the publisher RESOLVES for this path, as `AppHttpServer` does.
    private static String servedBody(HttpRoutePublisher publisher, String path) {
        var served = publisher.findServingRouter("GET", path)
                              .unwrap()
                              .handle(HttpRequestContext.httpRequestContext(path, "GET", Map.of(), Map.of(), "req"))
                              .await(timeSpan(10).seconds())
                              .unwrap();

        return new String(served.body());
    }

    private static HttpRoutePublisher publish(Object slice) {
        var publisher = HttpRoutePublisher.httpRoutePublisher(SELF, new SilentCluster());

        publisher.publishRoutes(ARTIFACT, SpacerPositionHostResolutionProbeTest.class.getClassLoader(), slice, stubInvokerFacade())
                 .await(timeSpan(30).seconds())
                 .onFailure(cause -> Assertions.fail("route publication must succeed: " + cause.message()));

        return publisher;
    }

    /// CONTROL: the router really serves the named handler for this path, so the policy is judged against the
    /// route that answers, not against a guess.
    private static void assertServedBy(HttpRoutePublisher publisher, String path, String bodyMarker) {
        var served = publisher.getSliceRouter(ARTIFACT)
                              .unwrap()
                              .handle(HttpRequestContext.httpRequestContext(path, "GET", Map.of(), Map.of(), "req"))
                              .await(timeSpan(10).seconds())
                              .unwrap();

        assertThat(new String(served.body())).as("CONTROL: %s is served by the expected handler", path).contains(bodyMarker);
    }

    private static SecurityPolicy policy(HttpRoutePublisher publisher, String path) {
        return publisher.findLocalRoute("GET", path)
                        .map(HttpRoutePublisher.LocalRouteInfo::security)
                        .toResult(Causes.cause("no local route for " + path))
                        .unwrap();
    }

    private static SliceInvokerFacade stubInvokerFacade() {
        return new SliceInvokerFacade() {
            @Override
            public <R, T> Result<MethodHandle<R, T>> methodHandle(String a, String m, TypeToken<T> q, TypeToken<R> r) {
                return Causes.cause("stub").result();
            }
        };
    }

    private static final class SilentCluster implements ClusterNode<KVCommand<AetherKey>> {
        @Override
        public NodeId self() {
            return SELF;
        }

        @Override
        public TopologyManager topologyManager() {
            return Assertions.fail("unused");
        }

        @Override
        public Promise<Unit> start() {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            return (Promise<List<R>>) (Promise<?>) Promise.success(List.of());
        }
    }
}
