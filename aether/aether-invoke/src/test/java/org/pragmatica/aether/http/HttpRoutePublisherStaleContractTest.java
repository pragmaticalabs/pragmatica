// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.http.adapter.SliceRouterFactory;
import org.pragmatica.aether.slice.ObservabilityCellRegistrar;
import org.pragmatica.aether.slice.ObservabilityStrategyCell;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.SliceLoadingFailure;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue.RouteEntry;
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
    private static final Artifact CURRENT = Artifact.artifact("org.example:current-slice:1.0.0").unwrap();
    private static final Artifact STALE_NON_PUBLIC = Artifact.artifact("org.example:stale-nonpublic-slice:1.0.0").unwrap();

    private CapturingCluster cluster;
    private CountingRegistrar registrar;
    private HttpRoutePublisher publisher;

    @BeforeEach
    void setUp() {
        cluster = new CapturingCluster();
        registrar = new CountingRegistrar();
        publisher = HttpRoutePublisher.httpRoutePublisher(SELF, cluster);
        publisher.setObservabilityCellRegistrar(registrar);
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
                                       .containsIgnoringCase("recompile");
        });
        assertThat(cluster.applied).as("no route table entry reaches consensus for a refused slice")
                                   .isEmpty();
        assertThat(registrar.registered.get()).as("refused before the router is built: no observability cell is registered for a slice that never serves")
                                              .isZero();
    }

    /// Control: the same public route from a factory carrying the stamp publishes — the stamp, not
    /// the policy, is what the refusal keys on. Asserted on the published CONTENT: the test
    /// classpath also carries `StubRouteHandlerFactory`, the fallback producer, which would publish
    /// one entry for the same artifact (`GET /stub/`) if the factory match were never taken.
    @Test
    void factoryWithTheContract_declaringAPublicRoute_isPublished() {
        publisher.publishRoutes(CURRENT, getClass().getClassLoader(), new CurrentRouteContractSlice(), stubInvokerFacade())
                 .await(timeSpan(10).seconds())
                 .onFailure(cause -> Assertions.fail("a current build's declared-public route must publish: " + cause.message()));

        var entries = cluster.publishedRouteEntries();

        assertThat(entries).as("exactly this factory's one route, not the fallback stub's")
                           .hasSize(1);
        assertThat(entries.getFirst().pathPrefix()).endsWith("/current/items/");
        assertThat(entries.getFirst().security()).isEqualTo("PUBLIC");
        assertThat(registrar.registered.get()).as("one observability cell per published route")
                                              .isEqualTo(1);
    }

    /// The pass case the refusal is scoped by: a pre-#763 factory whose every route is NON-public
    /// publishes. The old default only ever produced `publicRoute()`, so a non-public policy in a
    /// stale JAR was necessarily declared — refusing it would fail slices that hide nothing.
    @Test
    void factoryWithoutTheContract_declaringOnlyNonPublicRoutes_isPublished() {
        publisher.publishRoutes(STALE_NON_PUBLIC, getClass().getClassLoader(), new StaleNonPublicRouteContractSlice(), stubInvokerFacade())
                 .await(timeSpan(10).seconds())
                 .onFailure(cause -> Assertions.fail("a stale factory with only non-public routes must publish: " + cause.message()));

        var entries = cluster.publishedRouteEntries();

        assertThat(entries).as("both of THIS factory's routes, not the fallback stub's")
                           .hasSize(2);
        assertThat(entries).extracting(RouteEntry::pathPrefix)
                           .anySatisfy(path -> assertThat(path).endsWith("/stale-nonpublic/items/"))
                           .anySatisfy(path -> assertThat(path).endsWith("/stale-nonpublic/admin/"));
        assertThat(entries).extracting(RouteEntry::security)
                           .containsOnly("AUTHENTICATED");
    }

    /// `slice-api` cannot see `http-routing-adapter`, so the cause carries its own copy of the
    /// current contract number for its message; this is the only place both are visible.
    @Test
    void theCauseAndTheFactory_agreeOnTheCurrentContract() {
        assertThat(SliceLoadingFailure.CURRENT_ROUTE_SECURITY_CONTRACT).isEqualTo(SliceRouterFactory.ROUTE_SECURITY_CONTRACT);
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

    private static final class CountingRegistrar implements ObservabilityCellRegistrar {
        private final AtomicInteger registered = new AtomicInteger(0);

        @Override
        public Unit register(ObservabilityStrategyCell cell) {
            registered.incrementAndGet();

            return Unit.unit();
        }

        @Override
        public Unit deregister(ObservabilityStrategyCell cell) {
            return Unit.unit();
        }
    }

    private static final class CapturingCluster implements ClusterNode<KVCommand<AetherKey>> {
        private final List<KVCommand<AetherKey>> applied = new CopyOnWriteArrayList<>();

        /// Every route entry carried by every `NodeRoutesValue` put that reached consensus.
        List<RouteEntry> publishedRouteEntries() {
            return applied.stream()
                          .filter(command -> command instanceof KVCommand.Put<?, ?> put && put.value() instanceof NodeRoutesValue)
                          .map(command -> (NodeRoutesValue) ((KVCommand.Put<?, ?>) command).value())
                          .flatMap(value -> value.routes().stream())
                          .toList();
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
            applied.addAll(commands);

            return (Promise<List<R>>) (Promise<?>) Promise.success(List.of());
        }
    }
}
