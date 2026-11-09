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
/// #2946: a node serving one route shape both as `OPTIONAL` and as undeclared (`UNSPECIFIED`, inherits the global mode)
/// must resolve it to UNSPECIFIED, never weaken the inherited policy to the anonymous-tolerant `OPTIONAL` -- in either
/// declaration order.
class HttpRoutePublisherOptionalVsUnspecifiedTest {
    private static final NodeId SELF = NodeId.nodeId("self-optional").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:optional-things:1.0.0").unwrap();

    @Test
    void optionalDeclaration_neverWeakensAnInheritedPolicy_inEitherOrder() {
        for (var slice : List.<Object>of(new OptionalVsUnspecifiedSliceRoutes.OptionalFirstSlice(),
                                         new OptionalVsUnspecifiedSliceRoutes.InheritedFirstSlice())) {
            var publisher = publish(slice);

            assertThat(publisher.findLocalRoute("GET", "/things/5")
                                .map(HttpRoutePublisher.LocalRouteInfo::security)
                                .or(SecurityPolicy.publicRoute())).as("%s", slice.getClass().getSimpleName())
                                                                  .isEqualTo(SecurityPolicy.unspecified());
        }
    }

    @Test
    void optionalDeclaration_neverWeakensAnAuthenticatedSibling_inEitherOrder() {
        for (var slice : List.<Object>of(new OptionalVsUnspecifiedSliceRoutes.OptionalThenAuthenticatedSlice(),
                                         new OptionalVsUnspecifiedSliceRoutes.AuthenticatedThenOptionalSlice())) {
            var publisher = publish(slice);

            assertThat(publisher.findLocalRoute("GET", "/things/5")
                                .map(HttpRoutePublisher.LocalRouteInfo::security)
                                .or(SecurityPolicy.publicRoute())).as("%s", slice.getClass().getSimpleName())
                                                                  .isEqualTo(SecurityPolicy.authenticated());
        }
    }

    private static HttpRoutePublisher publish(Object slice) {
        var publisher = HttpRoutePublisher.httpRoutePublisher(SELF, new SilentCluster());

        publisher.publishRoutes(ARTIFACT, HttpRoutePublisherOptionalVsUnspecifiedTest.class.getClassLoader(), slice, stubInvokerFacade())
                 .await(timeSpan(30).seconds())
                 .onFailure(cause -> Assertions.fail("route publication must succeed: " + cause.message()));

        return publisher;
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
        final List<KVCommand<AetherKey>> applied = new java.util.concurrent.CopyOnWriteArrayList<>();

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
            applied.addAll(commands);

            return (Promise<List<R>>) (Promise<?>) Promise.success(List.of());
        }
    }
}
