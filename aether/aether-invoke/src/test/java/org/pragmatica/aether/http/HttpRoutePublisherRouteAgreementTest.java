// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

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

/// #884, the half [HttpRoutePublisherNestedPrefixTest] does not reach: the route whose policy
/// ADMITS a request must be the route that SERVES it.
///
/// The ticket owner recorded the two halves as both-or-neither. Making only the policy search
/// (`findLocalRoute`) longest-prefix while dispatch kept its own first-match scan over
/// `allLocalRoutes()` would have converted an intermittent disagreement into a systematic one:
/// that set is a `Set.copyOf` result, an `ImmutableCollections.SetN` whose iteration order is
/// derived from a `SALT` seeded once per JVM, so the two halves disagreed on roughly half of node
/// starts. Dispatch now resolves through the same `findLocalRoute` call, and the router lookup
/// through the same order, so the tests below assert an agreement that has one implementation
/// rather than two that happen to match.
///
/// **On the `SALT`.** A test inside ONE JVM cannot vary it, so no test here witnesses the old
/// coin flip. It does not need to: the fix REMOVES `Set.copyOf` from the resolution path, and what
/// remains -- `ConcurrentHashMap` iteration order over the artifact keys -- does vary in-process
/// and is varied below by generating fresh artifact coordinates each round. The claim that
/// dispatch no longer consults the salted set is structural (one call site, in the diff) and is
/// pinned by mutation, not by this class.
class HttpRoutePublisherRouteAgreementTest {
    private static final NodeId SELF = NodeId.nodeId("self-agreement").unwrap();
    private static final String METHOD = "GET";

    private static final Artifact OUTER = Artifact.artifact("org.example:agree-outer:1.0.0").unwrap();
    private static final Artifact INNER = Artifact.artifact("org.example:agree-inner:1.0.0").unwrap();
    private static final String OUTER_PREFIX = "/api/agree/";
    private static final String INNER_PREFIX = "/api/agree/inner/";

    private static final Artifact DUP_LOW = Artifact.artifact("org.example:aaa-dup:1.0.0").unwrap();
    private static final Artifact DUP_HIGH = Artifact.artifact("org.example:zzz-dup:1.0.0").unwrap();
    private static final String DUP_PREFIX = "/api/dup/";

    @Test
    void nestedPrefixes_policyPickAndServingRouterAgree_outerPublishedFirst() {
        var publisher = publish(List.of(entry(OUTER, OUTER_PREFIX), entry(INNER, INNER_PREFIX)));

        assertPolicyAndServingRouterAgree(publisher, "/api/agree/inner/report", INNER);
    }

    @Test
    void nestedPrefixes_policyPickAndServingRouterAgree_innerPublishedFirst() {
        var publisher = publish(List.of(entry(INNER, INNER_PREFIX), entry(OUTER, OUTER_PREFIX)));

        assertPolicyAndServingRouterAgree(publisher, "/api/agree/inner/report", INNER);
    }

    /// The discriminator the nested cases need: a path under the OUTER prefix but outside the
    /// inner one must still resolve -- and be served by -- the outer slice. Without this an
    /// implementation that always returns the globally longest published prefix passes the two
    /// tests above.
    @Test
    void pathOutsideTheNestedPrefix_policyPickAndServingRouterAgreeOnTheOuterRoute() {
        var publisher = publish(List.of(entry(OUTER, OUTER_PREFIX), entry(INNER, INNER_PREFIX)));

        assertPolicyAndServingRouterAgree(publisher, "/api/agree/items/42", OUTER);
    }

    /// #884 SF-1: the identical-prefix tie-break, which nothing pinned. Two artifacts publishing
    /// the SAME method and prefix is a publication collision the publisher does not adjudicate; it
    /// breaks the tie on the lexically smaller artifact coordinate so the answer is stable across
    /// restarts. The direction is asserted here, and so is the consequence that matters: the
    /// ROUTER lookup must land on the same artifact. A first match over `publishedRoutes` -- what
    /// `findLocalRouter` did -- picks by artifact hash instead, which is unrelated to the
    /// coordinate order the policy half uses, so the request would be authorized under one slice's
    /// policy and answered by the other's.
    @Test
    void identicalPrefixes_resolveToTheLexicallySmallerCoordinate_andTheRouterAgrees_lowPublishedFirst() {
        var publisher = publish(List.of(entry(DUP_LOW, DUP_PREFIX), entry(DUP_HIGH, DUP_PREFIX)));

        assertPolicyAndServingRouterAgree(publisher, "/api/dup/thing", DUP_LOW);
    }

    @Test
    void identicalPrefixes_resolveToTheLexicallySmallerCoordinate_andTheRouterAgrees_highPublishedFirst() {
        var publisher = publish(List.of(entry(DUP_HIGH, DUP_PREFIX), entry(DUP_LOW, DUP_PREFIX)));

        assertPolicyAndServingRouterAgree(publisher, "/api/dup/thing", DUP_LOW);
    }

    /// The property, not an example. A single hand-built table cannot tell a fix from a
    /// coincidence -- the defect it replaces was found as a DISTRIBUTION over 12 JVM runs -- so
    /// this generates ambiguous tables instead: a chain of nested prefixes 2 to 5 deep, each
    /// segment owned by a different artifact whose coordinate is fresh every round, published in a
    /// shuffled order. Fresh coordinates are what varies `ConcurrentHashMap` iteration order
    /// in-process; shuffling is what refutes any dependence on insertion order.
    ///
    /// For every round, EVERY depth in the chain is queried, not just the leaf: each path must
    /// resolve to the deepest prefix that contains it, and the serving router must belong to that
    /// same artifact.
    @Test
    void generatedAmbiguousTables_resolveToTheDeepestContainingPrefix_andPolicyAndRouterAlwaysAgree() {
        var random = new Random(884L);
        var rounds = 40;

        for (var round = 0; round < rounds; round++) {
            var depth = 2 + random.nextInt(4);
            var chain = generateChain(round, depth, random);
            var order = new ArrayList<>(chain);

            Collections.shuffle(order, random);

            var publisher = publish(order);

            for (var level = 0; level < chain.size(); level++) {
                var probe = chain.get(level).prefix() + "leaf-" + level;

                assertPolicyAndServingRouterAgree(publisher, probe, chain.get(level).artifact());
            }
        }
    }

    /// One request, both halves: the policy search names a route, and the router that would serve
    /// the prefix it named belongs to the same artifact. `isSameAs` against the publisher's own
    /// registry is the strongest available statement -- it is the very instance
    /// `AppHttpServer.handleLocalRoute` invokes.
    private static void assertPolicyAndServingRouterAgree(HttpRoutePublisher publisher, String path, Artifact expected) {
        var pick = publisher.findLocalRoute(METHOD, path);

        assertThat(pick.isPresent()).as("a local route must match %s", path)
                                    .isTrue();

        var info = pick.unwrap();

        assertThat(info.artifactCoord()).as("the policy search must resolve %s to the deepest containing prefix", path)
                                        .isEqualTo(expected.asString());

        var serving = publisher.findLocalRouter(info.httpMethod(), info.pathPrefix());

        assertThat(serving.isPresent()).as("the prefix the policy search named (%s) must have a router", info.pathPrefix())
                                       .isTrue();
        assertThat(serving.unwrap()).as("the router SERVING %s must belong to %s -- the artifact whose policy ADMITTED the request",
                                        path,
                                        expected.asString())
                                    .isSameAs(publisher.getSliceRouter(expected)
                                                       .unwrap());
    }

    private record Published(Artifact artifact, String prefix) {}

    private static Published entry(Artifact artifact, String prefix) {
        return new Published(artifact, prefix);
    }

    /// A chain of strictly nested prefixes, shallowest first, each owned by its own artifact.
    /// Coordinates carry the round so every round hashes differently.
    private static List<Published> generateChain(int round, int depth, Random random) {
        var chain = new ArrayList<Published>();
        var prefix = new StringBuilder("/api/g").append(round)
                                                .append('/');

        for (var level = 0; level < depth; level++) {
            prefix.append("s")
                  .append(random.nextInt(1_000))
                  .append('-')
                  .append(level)
                  .append('/');
            chain.add(new Published(Artifact.artifact("org.example:gen-" + round + "-" + level + "-" + random.nextInt(1_000) + ":1.0.0")
                                            .unwrap(),
                                    prefix.toString()));
        }

        return List.copyOf(chain);
    }

    /// Publishes every entry through the PRODUCTION 4-arg path
    /// (`publishRoutes` -> `publishViaSliceRouterFactory`), so `sliceRouters` is populated and
    /// `findLocalRouter` has something to return.
    private HttpRoutePublisher publish(List<Published> entries) {
        var publisher = HttpRoutePublisher.httpRoutePublisher(SELF, new SilentCluster());

        for (var published : entries) {
            RouteAgreementSliceRoutes.nextRoutePath(published.prefix());
            publisher.publishRoutes(published.artifact(),
                                    getClass().getClassLoader(),
                                    new RouteAgreementSliceRoutes.Slice(),
                                    stubInvokerFacade())
                     .await(timeSpan(30).seconds())
                     .onFailure(cause -> Assertions.fail("route publication must succeed: " + cause.message()));
        }

        return publisher;
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
            return (Promise<List<R>>) (Promise<?>) Promise.success(List.of());
        }
    }
}
