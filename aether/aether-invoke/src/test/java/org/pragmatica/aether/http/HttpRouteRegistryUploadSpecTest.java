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
    void aNodeListingTheSameRouteTwice_isHeldToTheStricterLimits_inEitherOrder() {
        var big = RouteEntry.activeRoute("POST", "/files/", "upload", "AUTHENTICATED", "AUTHENTICATED", 0, List.of(), List.of(), true, 9_000, 0);
        var small = RouteEntry.activeRoute("POST", "/files/", "upload", "AUTHENTICATED", "AUTHENTICATED", 0, List.of(), List.of(), true, 1_000, 10_000);

        for (var routes : List.of(List.of(big, small), List.of(small, big))) {
            var registry = HttpRouteRegistry.httpRouteRegistry();

            registry.onNodeRoutesPut(new ValuePut<>(new KVCommand.Put<>(NodeRoutesKey.nodeRoutesKey(NODE_A, FILES),
                                                                        NodeRoutesValue.nodeRoutesValue(routes, Epoch.ZERO)),
                                                    Option.none()));

            assertThat(spec(registry)).isEqualTo(UploadSpec.uploadSpec(true, 1_000, 10_000));
        }
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

    private static ValuePut<NodeRoutesKey, NodeRoutesValue> putParts(NodeId node, long maxFiles, long maxFileBytes, long maxFields, long maxFieldBytes) {
        var route = RouteEntry.activeRoute("POST", "/files/", "upload", "AUTHENTICATED", "AUTHENTICATED", 0, List.of(), List.of(), true, 0, 0,
                                           maxFiles, maxFileBytes, maxFields, maxFieldBytes);
        var value = NodeRoutesValue.nodeRoutesValue(List.of(route), Epoch.ZERO);

        return new ValuePut<>(new KVCommand.Put<>(NodeRoutesKey.nodeRoutesKey(node, FILES), value), Option.none());
    }

    @Test
    void aRoutesPartLimits_reachTheIngress_eachInItsOwnComponent() {
        var registry = HttpRouteRegistry.httpRouteRegistry();

        registry.onNodeRoutesPut(putParts(NODE_A, 3, 2_000_000, 10, 4096));

        assertThat(spec(registry)).isEqualTo(UploadSpec.uploadSpec(true, 0, 0, 3, 2_000_000, 10, 4096));
    }

    @Test
    void nodesDisagreeOnPartLimits_theStricterGovern_inEitherRegistrationOrder_andZeroNeverRelaxes() {
        var aFirst = HttpRouteRegistry.httpRouteRegistry();
        var bFirst = HttpRouteRegistry.httpRouteRegistry();

        aFirst.onNodeRoutesPut(putParts(NODE_A, 2, 0, 50, 100));
        aFirst.onNodeRoutesPut(putParts(NODE_B, 9, 7_000, 5, 0));
        bFirst.onNodeRoutesPut(putParts(NODE_B, 9, 7_000, 5, 0));
        bFirst.onNodeRoutesPut(putParts(NODE_A, 2, 0, 50, 100));

        var merged = UploadSpec.uploadSpec(true, 0, 0, 2, 7_000, 5, 100);

        assertThat(spec(aFirst)).isEqualTo(merged);
        assertThat(spec(bFirst)).isEqualTo(merged);
    }

    @Test
    void entriesBuiltWithEarlierShapes_declareNoPartLimits() {
        var elevenComponents = new RouteEntry("POST", "/files/", "upload", "ACTIVE", 100, 0L, "PUBLIC", "PUBLIC", 0, List.of(), List.of());
        var fourteenComponents = new RouteEntry("POST", "/files/", "upload", "ACTIVE", 100, 0L, "PUBLIC", "PUBLIC", 0, List.of(), List.of(), true, 5_000L, 90_000L);

        assertThat(List.of(elevenComponents.maxFiles(), elevenComponents.maxFileBytes(), elevenComponents.maxFields(), elevenComponents.maxFieldBytes()))
            .as("the shape before the ingest gate").containsOnly(0L);
        assertThat(List.of(fourteenComponents.maxFiles(), fourteenComponents.maxFileBytes(), fourteenComponents.maxFields(), fourteenComponents.maxFieldBytes()))
            .as("the shape with body and duration limits only").containsOnly(0L);
        assertThat(fourteenComponents.maxBodyBytes()).isEqualTo(5_000L);
        assertThat(fourteenComponents.maxDurationMillis()).isEqualTo(90_000L);
        assertThat(fourteenComponents.upload()).isTrue();
    }

    @Test
    void activeRouteWithoutPartLimits_declaresNoPartLimits_andKeepsTheBodyLimits() {
        var entry = RouteEntry.activeRoute("POST", "/files/", "upload", "PUBLIC", "PUBLIC", 0, List.of(), List.of(), true, 5_000L, 90_000L);

        assertThat(List.of(entry.maxFiles(), entry.maxFileBytes(), entry.maxFields(), entry.maxFieldBytes())).containsOnly(0L);
        assertThat(entry.maxBodyBytes()).isEqualTo(5_000L);
        assertThat(entry.maxDurationMillis()).isEqualTo(90_000L);
    }
}
