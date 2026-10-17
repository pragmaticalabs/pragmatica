// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
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

/// Owner ruling: value objects travel unwrapped on every slice route JSON path. The mapper a published slice's
/// router receives (the generated codecs' Jackson fallback, and the whole JSON seam of a slice without generated
/// codecs) is therefore the value-object mapper, never the shared default.
class HttpRoutePublisherWireMapperTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final Artifact WIRE = Artifact.artifact("org.example:wire-slice:1.0.0").unwrap();

    record Code(String value) {
        public static Result<Code> code(String value) {
            return Result.success(new Code(value));
        }
    }

    @Test
    void publishedSliceRouter_receivesTheValueObjectMapper() {
        WireMapperSliceRoutes.RECEIVED.set(null);
        HttpRoutePublisher.httpRoutePublisher(SELF, new AcceptingCluster())
                          .publishRoutes(WIRE, getClass().getClassLoader(), new WireMapperSlice(), stubInvokerFacade())
                          .await(timeSpan(10).seconds())
                          .onFailure(cause -> Assertions.fail("publish failed: " + cause.message()));

        assertThat(WireMapperSliceRoutes.RECEIVED.get()).as("the publisher must have built the router").isNotNull();
        assertThat(WireMapperSliceRoutes.RECEIVED.get().writeAsString(new Code("x")).unwrap()).isEqualTo("\"x\"");
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

    private static final class AcceptingCluster implements ClusterNode<KVCommand<AetherKey>> {
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
