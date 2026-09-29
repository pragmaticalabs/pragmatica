// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.util.List;
import java.util.Map;

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
class HttpRoutePublisherSiblingRoutePolicyTest {
    private static final NodeId SELF = NodeId.nodeId("self-sibling").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:sibling-orders:1.0.0").unwrap();

    @Test
    void adminSibling_isAuthorizedAsAdmin_whenThePublicSiblingIsListedFirst() {
        var publisher = publish(new SiblingRouteSliceRoutes.PublicFirstSlice());

        assertServedBy(publisher, "/orders/5/admin", "ADMIN-SECRET-5");
        assertThat(policy(publisher, "/orders/5/admin")).isEqualTo(SecurityPolicy.roleRequired("admin"));
    }

    @Test
    void adminSibling_isAuthorizedAsAdmin_whenItIsListedFirst() {
        var publisher = publish(new SiblingRouteSliceRoutes.AdminFirstSlice());

        assertServedBy(publisher, "/orders/5/admin", "ADMIN-SECRET-5");
        assertThat(policy(publisher, "/orders/5/admin")).isEqualTo(SecurityPolicy.roleRequired("admin"));
    }

    /// The other half: the PUBLIC sibling stays public in either order -- the fix must not simply take the
    /// strictest sibling for the whole base path.
    @Test
    void publicSibling_staysPublic_inEitherDeclarationOrder() {
        for (var slice : List.<Object>of(new SiblingRouteSliceRoutes.PublicFirstSlice(), new SiblingRouteSliceRoutes.AdminFirstSlice())) {
            var publisher = publish(slice);

            assertServedBy(publisher, "/orders/5", "public-order-5");
            assertThat(policy(publisher, "/orders/5")).as("%s", slice.getClass().getSimpleName())
                                                      .isEqualTo(SecurityPolicy.publicRoute());
        }
    }

    /// A path neither sibling serves (the router answers 404) resolves to the STRICTEST sibling: an ambiguity
    /// fails closed.
    @Test
    void unmatchedPath_underTheSharedBase_resolvesToTheStrictestSibling() {
        var publisher = publish(new SiblingRouteSliceRoutes.PublicFirstSlice());

        assertThat(policy(publisher, "/orders/5/unknown")).isEqualTo(SecurityPolicy.roleRequired("admin"));
    }

    private static HttpRoutePublisher publish(Object slice) {
        var publisher = HttpRoutePublisher.httpRoutePublisher(SELF, new SilentCluster());

        publisher.publishRoutes(ARTIFACT, HttpRoutePublisherSiblingRoutePolicyTest.class.getClassLoader(), slice, stubInvokerFacade())
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
