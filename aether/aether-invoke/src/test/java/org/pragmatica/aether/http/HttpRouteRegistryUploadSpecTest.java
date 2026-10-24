// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue.RouteEntry;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.routing.UploadSpec;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// An ingress that does not host a route enforces the limits the serving nodes declared: the registry reports, per route,
/// the STRICTEST limits among the nodes currently serving it (the smaller declared limit, upload if any says upload), so a
/// request is never admitted past what a serving node declared, and the limits follow the serving set as nodes come and go.
class HttpRouteRegistryUploadSpecTest {
    private static final NodeId NODE_A = NodeId.nodeId("node-a").unwrap();
    private static final NodeId NODE_B = NodeId.nodeId("node-b").unwrap();
    private static final Artifact FILES = Artifact.artifact("com.example:files:1.0.0").unwrap();

    private static ValuePut<NodeRoutesKey, NodeRoutesValue> put(NodeId node, boolean upload, long maxBytes, long maxMillis) {
        var route = RouteEntry.activeRoute("POST", "/files/", "upload", "AUTHENTICATED", "AUTHENTICATED", 0, List.of(), List.of(), upload, maxBytes, maxMillis);
        var value = NodeRoutesValue.nodeRoutesValue(List.of(route), Epoch.ZERO);

        return new ValuePut<>(new KVCommand.Put<>(NodeRoutesKey.nodeRoutesKey(node, FILES), value), Option.none());
    }

    private static UploadSpec spec(HttpRouteRegistry registry) {
        return registry.findRoute("POST", "/files/x").unwrap().uploadSpec();
    }

    @Test
    void aRoutesDeclaredLimits_reachTheIngress() {
        var registry = HttpRouteRegistry.httpRouteRegistry();

        registry.onNodeRoutesPut(put(NODE_A, true, 5_000_000, 90_000));

        assertThat(spec(registry)).isEqualTo(UploadSpec.uploadSpec(true, 5_000_000, 90_000));
    }

    @Test
    void nodesDisagree_theStricterLimitsGovern_inEitherRegistrationOrder() {
        var smallFirst = HttpRouteRegistry.httpRouteRegistry();
        var smallLast = HttpRouteRegistry.httpRouteRegistry();

        smallFirst.onNodeRoutesPut(put(NODE_A, true, 1_000, 10_000));
        smallFirst.onNodeRoutesPut(put(NODE_B, true, 9_000, 0));
        smallLast.onNodeRoutesPut(put(NODE_B, true, 9_000, 0));
        smallLast.onNodeRoutesPut(put(NODE_A, true, 1_000, 10_000));

        assertThat(spec(smallFirst)).isEqualTo(UploadSpec.uploadSpec(true, 1_000, 10_000));
        assertThat(spec(smallLast)).isEqualTo(UploadSpec.uploadSpec(true, 1_000, 10_000));
    }

    @Test
    void aNodeThatDeclaresNothing_doesNotRelaxAnotherNodesLimit() {
        var registry = HttpRouteRegistry.httpRouteRegistry();

        registry.onNodeRoutesPut(put(NODE_A, false, 0, 0));
        registry.onNodeRoutesPut(put(NODE_B, true, 2_000, 0));

        assertThat(spec(registry)).isEqualTo(UploadSpec.uploadSpec(true, 2_000, 0));
    }

    @Test
    void aRepublishReplacesTheNodesOwnLimits_andADepartedNodeStopsPinningThem() {
        var registry = HttpRouteRegistry.httpRouteRegistry();

        registry.onNodeRoutesPut(put(NODE_A, true, 1_000, 0));
        registry.onNodeRoutesPut(put(NODE_B, true, 9_000, 0));
        registry.onNodeRoutesPut(put(NODE_A, true, 3_000, 0));

        assertThat(spec(registry)).as("node A's republish replaced its own entry").isEqualTo(UploadSpec.uploadSpec(true, 3_000, 0));

        registry.onNodeRoutesRemove(new ValueRemove<>(new KVCommand.Remove<>(NodeRoutesKey.nodeRoutesKey(NODE_A, FILES)), Option.none()));

        assertThat(spec(registry)).as("with A gone, B's declaration governs").isEqualTo(UploadSpec.uploadSpec(true, 9_000, 0));
    }

    @Test
    void anEntryPublishedWithoutLimits_declaresNone() {
        var registry = HttpRouteRegistry.httpRouteRegistry();
        var legacy = RouteEntry.activeRoute("POST", "/files/", "upload", "PUBLIC");

        registry.onNodeRoutesPut(new ValuePut<>(new KVCommand.Put<>(NodeRoutesKey.nodeRoutesKey(NODE_A, FILES),
                                                                    NodeRoutesValue.nodeRoutesValue(List.of(legacy), Epoch.ZERO)),
                                                Option.none()));

        assertThat(spec(registry)).isEqualTo(UploadSpec.NONE);
    }
}
