// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2156 side finding: the raw `HttpRequestHandlerFactory` fallback is removed. A slice that no registered
/// `SliceRouterFactory` serves publishes NO routes: before the removal the publisher fell back to the first
/// `HttpRequestHandlerFactory` on the class loader and advertised its routes cluster-wide, although no node ever
/// dispatched to the handler it built. The test class path registers several router factories
/// (`StubRouteSliceRoutes` among them), so a fallback to "any provider" would be visible here.
class HttpRoutePublisherUnmatchedSliceTest {
    private static final NodeId SELF = NodeId.nodeId("self-unmatched").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:unmatched:1.0.0").unwrap();

    /// A slice type no `SliceRouterFactory` declares.
    private static final class UnmatchedSlice {}

    @Test
    void publishRoutes_noFactoryMatchesTheSlice_publishesNothing() {
        var cluster = new CountingCluster();
        var publisher = HttpRoutePublisher.httpRoutePublisher(SELF, cluster);

        publisher.publishRoutes(ARTIFACT, getClass().getClassLoader(), new UnmatchedSlice(), stubInvokerFacade())
                 .await(timeSpan(10).seconds())
                 .onFailure(cause -> Assertions.fail("an unmatched slice must not fail publication: " + cause.message()));

        assertThat(cluster.applies.get()).as("no route entry may reach consensus for a slice nothing serves")
                                         .isZero();
        assertThat(publisher.allLocalRoutes()).as("no local route may be advertised for a slice nothing serves")
                                              .isEmpty();
    }

    @Test
    void hasRoutes_noFactoryMatchesTheSlice_isFalse() {
        var publisher = HttpRoutePublisher.httpRoutePublisher(SELF, new CountingCluster());

        assertThat(publisher.hasRoutes(getClass().getClassLoader(), new UnmatchedSlice())).isFalse();
    }

    @Test
    void control_matchedSlice_publishesThroughTheRouterFactory() {
        var cluster = new CountingCluster();
        var publisher = HttpRoutePublisher.httpRoutePublisher(SELF, cluster);

        assertThat(publisher.hasRoutes(getClass().getClassLoader(), new StubRouteSlice())).isTrue();
        publisher.publishRoutes(ARTIFACT, getClass().getClassLoader(), new StubRouteSlice(), stubInvokerFacade())
                 .await(timeSpan(10).seconds())
                 .onFailure(cause -> Assertions.fail("publish: " + cause.message()));

        assertThat(cluster.applies.get()).isEqualTo(1);
        assertThat(publisher.getSliceRouter(ARTIFACT).isPresent()).isTrue();
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

    private static final class CountingCluster implements ClusterNode<KVCommand<AetherKey>> {
        private final AtomicInteger applies = new AtomicInteger();

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
            applies.incrementAndGet();

            return (Promise<List<R>>) (Promise<?>) Promise.success(List.of());
        }
    }
}
