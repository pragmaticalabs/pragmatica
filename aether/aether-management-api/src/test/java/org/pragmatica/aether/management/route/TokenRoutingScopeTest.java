// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.management.route;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;


/// Round-trip pins for the 16 identity-first stream routes (management-api-versioning-spec.md
/// §3.2/§3.3) that require a literal path segment after or between params -- the shape the old
/// "literal-prefix then all-params-at-tail" `ManagementRoute` constructor cannot express, and the
/// reason for the token-based generalization ([PathToken], [RouteMatcher], [RouteAssembler]).
///
/// 8 rows come from §3.2 (`STREAM_GET`, `STREAM_PARTITION`, `STREAM_READ`, `STREAM_REPLICAS`,
/// `STREAM_CONSUMERS`, `CONSUMER_GROUP_JOIN`, `CONSUMER_GROUP_LEAVE`, `CONSUMER_GROUP_STATUS`) and
/// 8 from §3.3 (`STREAMS_LATEST`, `STREAMS_TAIL`, `STREAMS_EVENTS`, `STREAMS_GROUPS_LIST`,
/// `STREAMS_PUBLISH`, `STREAMS_PUBLISH_BATCH`, `STREAMS_GROUP_CREATE`, `STREAMS_GROUP_DELETE`) --
/// 16 total. (The other rows in both sections keep the old tail-only shape: params only, or a
/// single literal run immediately before them, so they don't need this mechanism.)
///
/// None of these are real `ManagementRoute` enum entries yet (that's Commit 2b); these pins
/// exercise `RouteAssembler.render` / `RouteMatcher.matchLiteralCount` / `extractParamValues`
/// directly against the exact token sequences Commit 2b will use, so the mechanism is proven
/// before the enum redesign lands.
class TokenRoutingScopeTest {

    private static void assertRoundTrips(List<PathToken> tokens, List<String> values, String expectedPath) {
        var rendered = RouteAssembler.render(tokens, values);
        assertThat(rendered).isEqualTo(expectedPath);

        var segments = segments(rendered);
        var expectedLiteralCount = tokens.stream().filter(t -> t instanceof PathToken.Spacer).count();

        assertThat(RouteMatcher.matchLiteralCount(tokens, segments)).isEqualTo((int) expectedLiteralCount);
        assertThat(RouteMatcher.extractParamValues(tokens, segments)).isEqualTo(values);
    }

    private static List<String> segments(String path) {
        var segments = new ArrayList<String>();

        for (var seg : path.split("/")) {
            if (!seg.isEmpty()) {
                segments.add(seg);
            }
        }

        return segments;
    }

    // -- §3.2: engine diagnostics become sub-resources of the catalog identity --

    @Test
    void streamGet_roundTrips() {
        // GET /api/v1/streams/{ns}/{stream}/{ver}/partitions
        var tokens = List.<PathToken>of(
                PathToken.spacer("api"), PathToken.spacer("v1"), PathToken.spacer("streams"),
                PathToken.param("ns"), PathToken.param("stream"), PathToken.param("ver"),
                PathToken.spacer("partitions"));
        assertRoundTrips(tokens, List.of("acme", "orders", "3"),
                          "/api/v1/streams/acme/orders/3/partitions");
    }

    @Test
    void streamPartition_roundTrips() {
        // GET /api/v1/streams/{ns}/{stream}/{ver}/partitions/{p}
        var tokens = List.<PathToken>of(
                PathToken.spacer("api"), PathToken.spacer("v1"), PathToken.spacer("streams"),
                PathToken.param("ns"), PathToken.param("stream"), PathToken.param("ver"),
                PathToken.spacer("partitions"), PathToken.param("p"));
        assertRoundTrips(tokens, List.of("acme", "orders", "3", "0"),
                          "/api/v1/streams/acme/orders/3/partitions/0");
    }

    @Test
    void streamRead_roundTrips() {
        // GET /api/v1/streams/{ns}/{stream}/{ver}/read/{p}
        var tokens = List.<PathToken>of(
                PathToken.spacer("api"), PathToken.spacer("v1"), PathToken.spacer("streams"),
                PathToken.param("ns"), PathToken.param("stream"), PathToken.param("ver"),
                PathToken.spacer("read"), PathToken.param("p"));
        assertRoundTrips(tokens, List.of("acme", "orders", "3", "0"),
                          "/api/v1/streams/acme/orders/3/read/0");
    }

    @Test
    void streamReplicas_roundTrips() {
        // GET /api/v1/streams/{ns}/{stream}/{ver}/replicas/{p}
        var tokens = List.<PathToken>of(
                PathToken.spacer("api"), PathToken.spacer("v1"), PathToken.spacer("streams"),
                PathToken.param("ns"), PathToken.param("stream"), PathToken.param("ver"),
                PathToken.spacer("replicas"), PathToken.param("p"));
        assertRoundTrips(tokens, List.of("acme", "orders", "3", "0"),
                          "/api/v1/streams/acme/orders/3/replicas/0");
    }

    @Test
    void streamConsumers_roundTrips() {
        // GET /api/v1/streams/{ns}/{stream}/{ver}/consumers
        var tokens = List.<PathToken>of(
                PathToken.spacer("api"), PathToken.spacer("v1"), PathToken.spacer("streams"),
                PathToken.param("ns"), PathToken.param("stream"), PathToken.param("ver"),
                PathToken.spacer("consumers"));
        assertRoundTrips(tokens, List.of("acme", "orders", "3"),
                          "/api/v1/streams/acme/orders/3/consumers");
    }

    @Test
    void consumerGroupJoin_roundTrips() {
        // POST /api/v1/streams/{ns}/{stream}/{ver}/groups/join
        var tokens = List.<PathToken>of(
                PathToken.spacer("api"), PathToken.spacer("v1"), PathToken.spacer("streams"),
                PathToken.param("ns"), PathToken.param("stream"), PathToken.param("ver"),
                PathToken.spacer("groups"), PathToken.spacer("join"));
        assertRoundTrips(tokens, List.of("acme", "orders", "3"),
                          "/api/v1/streams/acme/orders/3/groups/join");
    }

    @Test
    void consumerGroupLeave_roundTrips() {
        // POST /api/v1/streams/{ns}/{stream}/{ver}/groups/leave
        var tokens = List.<PathToken>of(
                PathToken.spacer("api"), PathToken.spacer("v1"), PathToken.spacer("streams"),
                PathToken.param("ns"), PathToken.param("stream"), PathToken.param("ver"),
                PathToken.spacer("groups"), PathToken.spacer("leave"));
        assertRoundTrips(tokens, List.of("acme", "orders", "3"),
                          "/api/v1/streams/acme/orders/3/groups/leave");
    }

    @Test
    void consumerGroupStatus_roundTrips() {
        // GET /api/v1/streams/{ns}/{stream}/{ver}/groups/{id}
        var tokens = List.<PathToken>of(
                PathToken.spacer("api"), PathToken.spacer("v1"), PathToken.spacer("streams"),
                PathToken.param("ns"), PathToken.param("stream"), PathToken.param("ver"),
                PathToken.spacer("groups"), PathToken.param("id"));
        assertRoundTrips(tokens, List.of("acme", "orders", "3", "g1"),
                          "/api/v1/streams/acme/orders/3/groups/g1");
    }

    // -- §3.3: identity-first resource shapes, verb-prefix paths eliminated --

    @Test
    void streamsLatest_roundTrips() {
        // GET /api/v1/streams/{ns}/{stream}/latest
        var tokens = List.<PathToken>of(
                PathToken.spacer("api"), PathToken.spacer("v1"), PathToken.spacer("streams"),
                PathToken.param("ns"), PathToken.param("stream"), PathToken.spacer("latest"));
        assertRoundTrips(tokens, List.of("acme", "orders"),
                          "/api/v1/streams/acme/orders/latest");
    }

    @Test
    void streamsTail_roundTrips() {
        // GET /api/v1/streams/{ns}/{stream}/{ver}/tail
        var tokens = List.<PathToken>of(
                PathToken.spacer("api"), PathToken.spacer("v1"), PathToken.spacer("streams"),
                PathToken.param("ns"), PathToken.param("stream"), PathToken.param("ver"),
                PathToken.spacer("tail"));
        assertRoundTrips(tokens, List.of("acme", "orders", "3"),
                          "/api/v1/streams/acme/orders/3/tail");
    }

    @Test
    void streamsEvents_roundTrips() {
        // GET /api/v1/streams/{ns}/{stream}/{ver}/events
        var tokens = List.<PathToken>of(
                PathToken.spacer("api"), PathToken.spacer("v1"), PathToken.spacer("streams"),
                PathToken.param("ns"), PathToken.param("stream"), PathToken.param("ver"),
                PathToken.spacer("events"));
        assertRoundTrips(tokens, List.of("acme", "orders", "3"),
                          "/api/v1/streams/acme/orders/3/events");
    }

    @Test
    void streamsGroupsList_roundTrips() {
        // GET /api/v1/streams/{ns}/{stream}/{ver}/groups
        var tokens = List.<PathToken>of(
                PathToken.spacer("api"), PathToken.spacer("v1"), PathToken.spacer("streams"),
                PathToken.param("ns"), PathToken.param("stream"), PathToken.param("ver"),
                PathToken.spacer("groups"));
        assertRoundTrips(tokens, List.of("acme", "orders", "3"),
                          "/api/v1/streams/acme/orders/3/groups");
    }

    @Test
    void streamsPublish_roundTrips() {
        // POST /api/v1/streams/{ns}/{stream}/{ver}/publish
        var tokens = List.<PathToken>of(
                PathToken.spacer("api"), PathToken.spacer("v1"), PathToken.spacer("streams"),
                PathToken.param("ns"), PathToken.param("stream"), PathToken.param("ver"),
                PathToken.spacer("publish"));
        assertRoundTrips(tokens, List.of("acme", "orders", "3"),
                          "/api/v1/streams/acme/orders/3/publish");
    }

    @Test
    void streamsPublishBatch_roundTrips() {
        // POST /api/v1/streams/{ns}/{stream}/{ver}/publish-batch
        var tokens = List.<PathToken>of(
                PathToken.spacer("api"), PathToken.spacer("v1"), PathToken.spacer("streams"),
                PathToken.param("ns"), PathToken.param("stream"), PathToken.param("ver"),
                PathToken.spacer("publish-batch"));
        assertRoundTrips(tokens, List.of("acme", "orders", "3"),
                          "/api/v1/streams/acme/orders/3/publish-batch");
    }

    @Test
    void streamsGroupCreate_roundTrips() {
        // POST /api/v1/streams/{ns}/{stream}/{ver}/groups (same shape as streamsGroupsList, but
        // POST -- HTTP-method-disambiguated, not shape-disambiguated; the token mechanism doesn't
        // need to tell them apart, RouteMatcher's per-method bucketing does).
        var tokens = List.<PathToken>of(
                PathToken.spacer("api"), PathToken.spacer("v1"), PathToken.spacer("streams"),
                PathToken.param("ns"), PathToken.param("stream"), PathToken.param("ver"),
                PathToken.spacer("groups"));
        assertRoundTrips(tokens, List.of("acme", "orders", "3"),
                          "/api/v1/streams/acme/orders/3/groups");
    }

    @Test
    void streamsGroupDelete_roundTrips() {
        // DELETE /api/v1/streams/{ns}/{stream}/{ver}/groups/{group}
        var tokens = List.<PathToken>of(
                PathToken.spacer("api"), PathToken.spacer("v1"), PathToken.spacer("streams"),
                PathToken.param("ns"), PathToken.param("stream"), PathToken.param("ver"),
                PathToken.spacer("groups"), PathToken.param("group"));
        assertRoundTrips(tokens, List.of("acme", "orders", "3", "g1"),
                          "/api/v1/streams/acme/orders/3/groups/g1");
    }
}
