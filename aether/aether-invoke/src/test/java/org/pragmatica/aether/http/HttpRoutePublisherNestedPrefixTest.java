// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.http.handler.HttpRequestHandlerFactory;
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
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #884: `findLocalRoute` returned the first prefix match while iterating `publishedRoutes` — a
/// `ConcurrentHashMap` — so with two slices on one node declaring NESTED prefixes
/// (`examples/pricing-engine`: `/api/v1/pricing` and `/api/v1/pricing/analytics`) the route a
/// request resolved to was decided by hash iteration order. The rule is now longest matching
/// prefix.
///
/// Two things about the instrument. (1) `ConcurrentHashMap` iteration order is a function of the
/// keys' hashes, not of insertion order, so "register in both orders" alone cannot witness the
/// defect; the artifact ids below were chosen so that the OUTER artifact iterates FIRST, which is
/// the losing order for first-match, and [#outerIteratesFirst_inAFreshConcurrentHashMap] pins that
/// precondition against the same map type. (2) Both insertion orders are still exercised, as the
/// ticket asks, so an implementation that depends on insertion order cannot pass either.
@SuppressWarnings("JBCT-EX-01")
class HttpRoutePublisherNestedPrefixTest {
    static final Artifact OUTER = Artifact.artifact("org.example:pricing-catalog:1.0.0").unwrap();
    static final Artifact INNER = Artifact.artifact("org.example:pricing-analytics:1.0.0").unwrap();
    private static final NodeId SELF = NodeId.nodeId("self-nested").unwrap();
    private static final String REQUEST_PATH = "/api/v1/pricing/analytics/report";

    @TempDir
    Path tempDir;

    @Test
    void outerIteratesFirst_inAFreshConcurrentHashMap() {
        var map = new ConcurrentHashMap<Artifact, String>();

        map.put(INNER, "inner");
        map.put(OUTER, "outer");

        assertThat(map.keySet().iterator().next()).as("instrument check: the outer artifact must hash ahead of the inner one, "
                                                      + "or a first-match scan would pass this test by luck")
                                                  .isEqualTo(OUTER);
    }

    @Test
    void nestedPrefixes_resolveToTheLongestPrefix_outerPublishedFirst() throws IOException {
        var publisher = publisherWith(List.of(OUTER, INNER));

        assertResolvesToInner(publisher);
    }

    @Test
    void nestedPrefixes_resolveToTheLongestPrefix_innerPublishedFirst() throws IOException {
        var publisher = publisherWith(List.of(INNER, OUTER));

        assertResolvesToInner(publisher);
    }

    /// A request under the outer prefix but outside the inner one still resolves to the outer route.
    @Test
    void pathOutsideTheNestedPrefix_resolvesToTheOuterRoute() throws IOException {
        var publisher = publisherWith(List.of(OUTER, INNER));

        var resolved = publisher.findLocalRoute(NestedPrefixRouteFactories.METHOD, "/api/v1/pricing/items/42");

        assertThat(resolved.map(HttpRoutePublisher.LocalRouteInfo::artifactCoord).or("<none>")).isEqualTo(OUTER.asString());
    }

    private static void assertResolvesToInner(HttpRoutePublisher publisher) {
        var resolved = publisher.findLocalRoute(NestedPrefixRouteFactories.METHOD, REQUEST_PATH);

        assertThat(resolved.isPresent()).as("some route must match " + REQUEST_PATH).isTrue();
        assertThat(resolved.map(HttpRoutePublisher.LocalRouteInfo::artifactCoord).or("<none>"))
                .as("the longest matching prefix (%s) must win, whatever the map's iteration order", NestedPrefixRouteFactories.INNER_PREFIX)
                .isEqualTo(INNER.asString());
        assertThat(resolved.map(HttpRoutePublisher.LocalRouteInfo::pathPrefix).or("<none>")).isEqualTo(NestedPrefixRouteFactories.INNER_PREFIX);
    }

    private HttpRoutePublisher publisherWith(List<Artifact> order) throws IOException {
        var publisher = HttpRoutePublisher.httpRoutePublisher(SELF, new SilentCluster());

        for (var artifact : order) {
            publisher.publishRoutes(artifact, loaderServing(artifact), stubInvokerFacade())
                     .await(timeSpan(30).seconds())
                     .onFailure(cause -> Assertions.fail("route publication must succeed: " + cause.message()));
        }

        return publisher;
    }

    /// A class loader whose `ServiceLoader` lookup for [HttpRequestHandlerFactory] names exactly the
    /// factory for `artifact`, so each artifact publishes its own single route through the real
    /// 3-arg `publishRoutes` path. Class loading itself is delegated to the test class loader.
    private ClassLoader loaderServing(Artifact artifact) throws IOException {
        var factory = artifact.equals(OUTER)
                      ? NestedPrefixRouteFactories.OuterFactory.class
                      : NestedPrefixRouteFactories.InnerFactory.class;
        var servicesFile = tempDir.resolve(artifact.artifactId().toString() + ".services");

        Files.writeString(servicesFile, factory.getName() + "\n", StandardCharsets.UTF_8);

        var serviceResource = "META-INF/services/" + HttpRequestHandlerFactory.class.getName();
        var url = servicesFile.toUri().toURL();

        return new ClassLoader(getClass().getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                return name.equals(serviceResource)
                       ? Collections.enumeration(List.of(url))
                       : super.getResources(name);
            }
        };
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
