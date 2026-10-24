// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.blueprint.SecurityOverridePolicy;
import org.pragmatica.aether.slice.blueprint.SecurityOverrides;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue.RouteEntry;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.http.routing.UploadSpec;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// A route's declared ingest limits travel from the slice to the node that accepts the request: the route the slice
/// builds (`.withUpload(...)`), through the PRODUCTION publisher (which wraps each route's handler), into the local
/// route lookup and into the replicated entry that an ingress not hosting the route reads. A security override rebuilds
/// the definition; it must not drop the limits.
class HttpRoutePublisherUploadSpecTest {
    private static final NodeId SELF = NodeId.nodeId("self-upload").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:upload-slice:1.0.0").unwrap();

    private CapturingCluster cluster;
    private HttpRoutePublisher publisher;

    @BeforeEach
    void setUp() {
        cluster = new CapturingCluster();
        publisher = HttpRoutePublisher.httpRoutePublisher(SELF, cluster);
        publisher.publishRoutes(ARTIFACT, getClass().getClassLoader(), new UploadIngestSlice(), stubInvokerFacade())
                 .await(timeSpan(10).seconds())
                 .onFailure(cause -> Assertions.fail("route publication must succeed: " + cause.message()));
    }

    private RouteEntry published(String prefix) {
        return cluster.publishedRouteEntries()
                      .reversed()
                      .stream()
                      .filter(entry -> entry.pathPrefix().endsWith(prefix))
                      .findFirst()
                      .orElseThrow(() -> new AssertionError("no published entry for " + prefix));
    }

    @Test
    void theLocalRouteLookup_carriesTheDeclaredLimits_andAPlainRouteCarriesNone() {
        assertThat(publisher.findLocalRoute("POST", "/uploads/x").unwrap().uploadSpec()).isEqualTo(UploadIngestSliceRoutes.DECLARED);
        assertThat(publisher.findLocalRoute("GET", "/plain/x").unwrap().uploadSpec()).isEqualTo(UploadSpec.NONE);
    }

    @Test
    void theReplicatedEntry_carriesTheDeclaredLimits_andAPlainRouteCarriesNone() {
        var upload = published("/uploads/");
        var plain = published("/plain/");

        assertThat(upload.upload()).isTrue();
        assertThat(upload.maxBodyBytes()).isEqualTo(5L * 1024 * 1024);
        assertThat(upload.maxDurationMillis()).isEqualTo(90_000L);
        assertThat(plain.upload()).isFalse();
        assertThat(plain.maxBodyBytes()).isZero();
        assertThat(plain.maxDurationMillis()).isZero();
    }

    @Test
    void aSecurityOverride_republishesTheEntryAndKeepsTheLocalLookup_withTheLimitsIntact() {
        publisher.updateSecurityOverrides(SecurityOverrides.securityOverrides(List.of(SecurityOverrides.Entry.entry("POST /uploads/",
                                                                                                                      "role:admin")),
                                                                              SecurityOverridePolicy.STRENGTHEN_ONLY));

        var republished = published("/uploads/");

        assertThat(republished.security()).as("control: the override reached the entry").isEqualTo("ROLE:admin");
        assertThat(republished.maxBodyBytes()).isEqualTo(5L * 1024 * 1024);
        assertThat(republished.maxDurationMillis()).isEqualTo(90_000L);
        assertThat(republished.upload()).isTrue();
        assertThat(publisher.findLocalRoute("POST", "/uploads/x").unwrap().uploadSpec()).isEqualTo(UploadIngestSliceRoutes.DECLARED);
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

    private static final class CapturingCluster implements ClusterNode<KVCommand<AetherKey>> {
        private final List<KVCommand<AetherKey>> applied = new CopyOnWriteArrayList<>();

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
