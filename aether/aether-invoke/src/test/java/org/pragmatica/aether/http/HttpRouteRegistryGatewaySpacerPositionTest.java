// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodHandle;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// #755 / #1103, the GATEWAY half. A slice's own router and the host's local resolution match spacers by position (they hold
/// the route's slots). A node that does not host the route selects over the REPLICATED entry (`AetherValue.HttpRoute`:
/// `pathArity` and `spacers`, #1678), which carries no positions, so the gateway still matches by set membership:
/// `GET /users/edit/42` against `withPath(aLong(), spacer("edit"))` is selected here and dies at the hosting node.
///
/// The fixture is built through the REAL path -- a slice published by `HttpRoutePublisher`, the entry it replicates captured
/// off the cluster, that entry fed to the registry -- so it carries whatever the publisher replicates today. When positions are
/// carried through `AetherValue.HttpRoute`, `HttpRoutePublisher`, `HttpRouteRegistry` and `RouteSource` (a wire change), the
/// fixture picks them up unchanged and the tripwire reddens.
///
/// The enabled test is a TRIPWIRE asserting today's WRONG behaviour; the real assertion is disabled beside it, because until
/// then it would fail. Disabled alone would stay silent forever.
class HttpRouteRegistryGatewaySpacerPositionTest {
    private static final NodeId SELF = NodeId.nodeId("self-gateway").unwrap();
    private static final Artifact ID_THEN_EDIT = Artifact.artifact("org.example:id-then-edit:1.0.0").unwrap();

    private static HttpRouteRegistry.RouteInfo replicatedRoute() {
        var cluster = new RecordingCluster();
        var publisher = HttpRoutePublisher.httpRoutePublisher(SELF, cluster);

        publisher.publishRoutes(ID_THEN_EDIT,
                                HttpRouteRegistryGatewaySpacerPositionTest.class.getClassLoader(),
                                new SpacerPositionSliceRoutes.IdThenEditSlice(),
                                stubInvokerFacade())
                 .await(timeSpan(30).seconds())
                 .onFailure(cause -> Assertions.fail("route publication must succeed: " + cause.message()));

        var registry = HttpRouteRegistry.httpRouteRegistry();
        var replicated = cluster.nodeRoutePuts();

        assertThat(replicated).as("the publisher must have replicated its routes").isNotEmpty();
        registry.onNodeRoutesPut(replicated.getLast());

        return registry.allRoutes().getFirst();
    }

    /// Control: the declared position resolves through the replicated entry, before and after the wire change.
    @Test
    void gateway_spacerAtItsDeclaredSlot_resolves() {
        assertThat(replicatedRoute().matchingShape("/users/42/edit").isPresent()).isTrue();
    }

    @Test
    void gatewayStillMatchesSpacersByMembership_tripwireFor755() {
        assertThat(replicatedRoute().matchingShape("/users/edit/42").isPresent())
            .as("gateway path now positional: delete this tripwire and enable the real assertion")
            .isTrue();
    }

    @Test
    @Disabled("#755 follow-up (B): positions are not carried through AetherValue.HttpRoute yet; enable when the tripwire above reddens")
    void gateway_spacerAtTheWrongSlot_isNoMatch() {
        assertThat(replicatedRoute().matchingShape("/users/edit/42").isEmpty()).isTrue();
    }

    private static SliceInvokerFacade stubInvokerFacade() {
        return new SliceInvokerFacade() {
            @Override
            public <R, T> Result<MethodHandle<R, T>> methodHandle(String a, String m, TypeToken<T> q, TypeToken<R> r) {
                return Causes.cause("stub").result();
            }
        };
    }

    private static final class RecordingCluster implements ClusterNode<KVCommand<AetherKey>> {
        private final List<ValuePut<NodeRoutesKey, NodeRoutesValue>> puts = new ArrayList<>();

        List<ValuePut<NodeRoutesKey, NodeRoutesValue>> nodeRoutePuts() {
            return puts;
        }

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
            for (var command : commands) {
                if (command instanceof KVCommand.Put<AetherKey, ?> put
                    && put.key() instanceof NodeRoutesKey key
                    && put.value() instanceof NodeRoutesValue value) {
                    puts.add(new ValuePut<>(new KVCommand.Put<>(key, value), Option.none()));
                }
            }

            return (Promise<List<R>>) (Promise<?>) Promise.success(List.of());
        }
    }
}
