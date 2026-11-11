// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.util.List;

import org.pragmatica.aether.artifact.Artifact;
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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.assertj.core.api.Assertions.assertThat;


/// #3021 probe 2 seam: with a route-publication delay set, the publication step returns at ONCE (so slice activation is not
/// held back) while the routes appear on the node only after the delay. A readiness condition on routes is what closes that
/// window; without the delay the routes are there when the step returns (the control).
@SuppressWarnings("JBCT-EX-01")
class HttpRoutePublisherPublicationDelayTest {
    private static final Artifact OUTER = Artifact.artifact("org.example:pricing-catalog:1.0.0").unwrap();
    private static final NodeId SELF = NodeId.nodeId("self-delay").unwrap();
    private static final long DELAY_MS = 1_500;

    @Test
    void withoutDelay_routesAreLocalWhenPublicationReturns() {
        var publisher = HttpRoutePublisher.httpRoutePublisher(SELF, new SilentCluster());

        publish(publisher);
        assertThat(publisher.allLocalRoutes()).as("CONTROL: no delay, so the route is there at once").hasSize(1);
    }

    @Test
    @Timeout(30)
    void withDelay_publicationReturnsAtOnce_andRoutesAppearOnlyAfterTheDelay() {
        var publisher = HttpRoutePublisher.httpRoutePublisher(SELF, new SilentCluster());

        publisher.setRoutePublicationDelay(timeSpan(DELAY_MS).millis());
        var startedAt = System.nanoTime();

        publish(publisher);
        var returnedAfterMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(returnedAfterMs).as("publication must not wait for the delay").isLessThan(DELAY_MS);
        assertThat(publisher.allLocalRoutes()).as("the route must not be visible before the delay elapsed").isEmpty();
        awaitRoutes(publisher);
        assertThat(publisher.allLocalRoutes()).hasSize(1);
        var visibleAfterMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(visibleAfterMs).as("the route became visible no earlier than the configured delay")
                  .isGreaterThanOrEqualTo(DELAY_MS);
    }

    private static void awaitRoutes(HttpRoutePublisher publisher) {
        var deadline = System.nanoTime() + 20_000_000_000L;

        while (publisher.allLocalRoutes().isEmpty() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    private void publish(HttpRoutePublisher publisher) {
        publisher.publishRoutes(OUTER,
                                getClass().getClassLoader(),
                                new NestedPrefixRouteFactories.OuterSlice(),
                                stubInvokerFacade())
                 .await(timeSpan(30).seconds())
                 .onFailure(cause -> Assertions.fail("route publication must succeed: " + cause.message()));
    }

    private static SliceInvokerFacade stubInvokerFacade() {
        return new SliceInvokerFacade() {
            @Override
            public <R, T> Result<MethodHandle<R, T>> methodHandle(String sliceArtifact,
                                                                  String methodName,
                                                                  TypeToken<T> requestType,
                                                                  TypeToken<R> responseType) {
                return Causes.cause("stub invoker facade").result();
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
            return Assertions.fail("topologyManager() is not exercised by route publication");
        }

        @Override
        public Promise<Unit> start() {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            return (Promise<List<R>>)(Promise<?>) Promise.success(List.of());
        }
    }
}
