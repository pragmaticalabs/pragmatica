// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.SliceLoadingFailure;
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

/// #882: a slice JAR compiled with a slice-processor from BEFORE the #763 fix carries
/// `SecurityPolicy.publicRoute()` baked into every route whose `routes.toml` had no `[security]`
/// section; redeployed onto an upgraded node it stays silently public under `API_KEY`/`JWT`. The
/// node never re-reads `routes.toml`, so the only thing it can consult is what the generated class
/// says about itself. A generated factory that predates the route-security contract and declares a
/// PUBLIC route is refused at publish — which fails the activation chain, so the slice never
/// serves — with a cause that names the artifact, the routes and the recompile needed.
class HttpRoutePublisherStaleContractTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final Artifact STALE = Artifact.artifact("org.example:stale-slice:1.0.0").unwrap();

    private CountingCluster cluster;
    private HttpRoutePublisher publisher;

    @BeforeEach
    void setUp() {
        cluster = new CountingCluster();
        publisher = HttpRoutePublisher.httpRoutePublisher(SELF, cluster);
    }

    @Test
    void factoryWithoutTheContract_declaringAPublicRoute_isRefused_andNothingIsPublished() {
        var result = publisher.publishRoutes(STALE, getClass().getClassLoader(), new StaleRouteContractSlice(), stubInvokerFacade())
                              .await(timeSpan(10).seconds());

        assertThat(result.isFailure()).as("#882: a pre-#763 build with a PUBLIC route must be refused, never published silently")
                                      .isTrue();
        result.onFailure(cause -> {
            assertThat(cause).as("a deterministic refusal, so the leader rolls back instead of retrying")
                             .isInstanceOf(SliceLoadingFailure.Fatal.class);
            assertThat(cause.message()).contains(STALE.asString())
                                       .contains("GET /stale/items")
                                       .doesNotContain("/stale/admin")
                                       .contains("recompile");
        });
        assertThat(cluster.applyCount()).as("no route table entry reaches consensus for a refused slice")
                                        .isZero();
    }

    private static SliceInvokerFacade stubInvokerFacade() {
        return new SliceInvokerFacade() {
            @Override
            public <R, T> Result<org.pragmatica.aether.slice.MethodHandle<R, T>> methodHandle(String sliceArtifact,
                                                                                            String methodName,
                                                                                            TypeToken<T> requestType,
                                                                                            TypeToken<R> responseType) {
                return Causes.cause("stub invoker facade").result();
            }
        };
    }

    private static final class CountingCluster implements ClusterNode<KVCommand<AetherKey>> {
        private final AtomicInteger applyCount = new AtomicInteger(0);

        int applyCount() {
            return applyCount.get();
        }

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
            applyCount.incrementAndGet();

            return (Promise<List<R>>) (Promise<?>) Promise.success(List.of());
        }
    }
}
