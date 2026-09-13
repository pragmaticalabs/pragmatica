// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.routes.StreamManager;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.management.route.MatchedRoute;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.StreamReadRouter;
import org.pragmatica.aether.stream.forward.StreamReadForwardMetrics;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #1039 owner-forwarding dispatch: the identity reduction and the owner lookup behind
/// `RouteTarget.PartitionOwner`.
///
/// The constraint under test is that `STREAM_REPLICAS` has ONE derivation of stream identity, shared
/// with the handler that answers it. `StreamApiRoutes.replicaDetail` computes
/// `StreamManager.engineKey(resourceAddress(ns, stream, version))`; the dispatch path computes
/// `ManagementServerImpl.resolveEngineKey` off the matched route. Two engine keys for one declaration
/// is the defect tracked by #1040, so these pin the two against each other rather than each against a
/// literal — a change to the reduction that moves only one of them reddens here.
class ManagementServerPartitionOwnerTest {
    private static final NodeId OWNER = new NodeId("node-owner");
    private static final int PARTITION_PARAM_INDEX = 3;

    @Test
    void resolveEngineKey_agreesWithTheHandlerDerivation_forAppNamespaceStreams() {
        assertAgreesWithHandler("myns", "mystream", "1.0.0");
    }

    @Test
    void resolveEngineKey_agreesWithTheHandlerDerivation_forSystemNamespaceStreams() {
        // The shape that actually differs: `system` streams reduce to the BARE name, everything else
        // to `ns:stream:version`. A dispatch path that forwarded on `system:cluster-events:1.0.0`
        // while the handler answered about `cluster-events` would resolve a different HRW owner for
        // the same URL, and the mismatch would read as ordinary membership skew.
        assertAgreesWithHandler(StreamManager.SYSTEM_NAMESPACE, "cluster-events", "1.0.0");
    }

    private static void assertAgreesWithHandler(String namespace, String stream, String version) {
        var address = ResourceAddress.resourceAddress(namespace, stream, version)
                                     .onFailure(cause -> fail("address must resolve: " + cause))
                                     .unwrap();

        assertThat(ManagementServerImpl.resolveEngineKey(matched(namespace, stream, version, "7")))
                .as("dispatch-path identity must equal the handler's StreamManager.engineKey for %s/%s/%s",
                    namespace,
                    stream,
                    version)
                .isEqualTo(Option.some(StreamManager.engineKey(address)));
    }

    @Test
    void partitionOwner_asksTheRouterForTheAddressedEngineKeyAndPartition() {
        var resolver = new RecordingOwnerResolver(Option.some(OWNER));
        var manager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);

        try {
            var owner = ManagementServerImpl.partitionOwner(matched("myns", "mystream", "1.0.0", "7"),
                                                            PARTITION_PARAM_INDEX,
                                                            router(manager, resolver));

            assertThat(owner).isEqualTo(Option.some(OWNER));
            assertThat(resolver.calls())
                    .as("the owner must be resolved for the engine key AND the partition the URL names — "
                       + "a correct key paired with the version slot read as a partition resolves a "
                       + "well-formed owner for the wrong partition")
                    .containsExactly("myns:mystream:1.0.0/7");
        } finally {
            manager.close();
        }
    }

    @Test
    void partitionOwner_isEmpty_whenThePartitionParamIsNotNumeric() {
        // Reachable: RouteMatcher extracts raw path segments, so `.../replicas/abc` matches the route
        // and only fails to parse here. Empty means the forwarder answers PartitionOwnerUnresolved
        // rather than silently forwarding to the owner of partition 0.
        assertEmptyOwner(matched("myns", "mystream", "1.0.0", "abc"), PARTITION_PARAM_INDEX);
    }

    @Test
    void partitionOwner_isEmpty_whenThePartitionParamIndexIsOutOfRange() {
        assertEmptyOwner(matched("myns", "mystream", "1.0.0", "7"), 9);
    }

    @Test
    void partitionOwner_isEmpty_forARouteWithNoResolvableIdentity() {
        // A route outside resolveEngineKey's case list has no identity to hash, so no owner can be
        // computed — it must not fall back to some other key.
        assertEmptyOwner(MatchedRoute.matchedRoute(ManagementRoute.STREAM_REPLICAS_LOCAL,
                                                    List.of("mystream", "7")),
                         1);
    }

    private static void assertEmptyOwner(MatchedRoute matched, int partitionParamIndex) {
        var resolver = new RecordingOwnerResolver(Option.some(OWNER));
        var manager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);

        try {
            assertThat(ManagementServerImpl.partitionOwner(matched, partitionParamIndex, router(manager, resolver)))
                    .as("an unresolvable address must yield no owner")
                    .isEqualTo(Option.<NodeId> none());
            assertThat(resolver.calls())
                    .as("control: the router must not be consulted at all, so a resolver that answers "
                       + "for every input cannot mask the miss")
                    .isEmpty();
        } finally {
            manager.close();
        }
    }

    @Test
    void answersPartitionLocally_onlyWhenThisNodeIsTheResolvedOwner() {
        var self = new NodeId("node-self");

        assertThat(ManagementServerImpl.answersPartitionLocally(Option.some(self), self)).isTrue();
        assertThat(ManagementServerImpl.answersPartitionLocally(Option.some(OWNER), self)).isFalse();
    }

    @Test
    void answersPartitionLocally_forwardsWhenNoOwnerCouldBeResolved() {
        // The inversion this pins is silent: defaulting an unresolved owner to "answer locally" gives
        // servedByOwner=false with an empty ring, which reads exactly like a genuinely empty
        // partition — #1039 restored. Forwarding instead lets the forwarder say
        // PartitionOwnerUnresolved out loud.
        assertThat(ManagementServerImpl.answersPartitionLocally(Option.none(), new NodeId("node-self")))
                .as("an unresolvable owner must forward, so the failure is named rather than faked")
                .isFalse();
    }

    private static MatchedRoute matched(String namespace, String stream, String version, String partition) {
        return MatchedRoute.matchedRoute(ManagementRoute.STREAM_REPLICAS,
                                         List.of(namespace, stream, version, partition));
    }

    private static StreamReadRouter router(StreamPartitionManager manager, RecordingOwnerResolver resolver) {
        return StreamReadRouter.streamReadRouter(manager,
                                                 Option.none(),
                                                 Option.none(),
                                                 new NodeId("node-self"),
                                                 resolver::resolve,
                                                 StreamReadForwardMetrics.NOOP);
    }

    private static final class RecordingOwnerResolver {
        private final Option<NodeId> owner;
        private final List<String> calls = new ArrayList<>();

        RecordingOwnerResolver(Option<NodeId> owner) {
            this.owner = owner;
        }

        List<String> calls() {return List.copyOf(calls);}

        Option<NodeId> resolve(String streamName, int partition) {
            calls.add(streamName + "/" + partition);

            return owner;
        }
    }
}
