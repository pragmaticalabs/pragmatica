// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterStateKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeclaredRouteKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.RuntimeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeclaredRoute;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeclaredRouteValue;
import org.pragmatica.serialization.SliceCodec;

import io.netty.buffer.Unpooled;

import static org.assertj.core.api.Assertions.assertThat;

/// #3389 A1: the declared-route key family, its value and the `AppBlueprintValue` field, on the text form and on the wire.
///
/// The key text carries a path template, which holds the separator `/` and the `{id}` braces; the round trip must give the same
/// key back or the factory must refuse the input. The codec tests run the BINARY path consensus replication uses, over the
/// registry the backup tests layer from every slice-side codec set (`BackupFixtures.codec()`), the blueprint's `ResolvedSlice` included.
class DeclaredRouteWireTest {
    private static final SliceCodec CODEC = BackupFixtures.codec();
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:orders-slice:1.2.3").unwrap();
    private static final Artifact OTHER = Artifact.artifact("org.example:inventory-slice:2.0.0").unwrap();

    @Nested
    class KeyText {
        @Test
        void key_roundTripsThroughItsText_forEveryPathShape() {
            var paths = List.of("/orders",
                                "/orders/{id}",
                                "/orders/{id}/items/{itemId}",
                                "/orders/",
                                "orders",
                                "/",
                                "/declared-route/GET//x",
                                "/a b/ü?x=1&y={z}");

            for (var path : paths) {
                var key = DeclaredRouteKey.declaredRouteKey(ARTIFACT, "GET", path).unwrap();
                var parsed = DeclaredRouteKey.declaredRouteKey(key.asString()).unwrap();

                assertThat(parsed).as("path '%s' must round-trip exactly", path).isEqualTo(key);
                assertThat(parsed.pathTemplate()).isEqualTo(path);
                assertThat(parsed.httpMethod()).isEqualTo("GET");
                assertThat(parsed.artifact()).isEqualTo(ARTIFACT);
            }
        }

        @Test
        void key_startsWithItsLiteralPrefix_andNamesTheArtifactMethodAndPath() {
            var key = DeclaredRouteKey.declaredRouteKey(ARTIFACT, "POST", "/orders/{id}").unwrap();

            assertThat(key.asString()).isEqualTo("declared-route/org.example:orders-slice:1.2.3/POST//orders/{id}");
            assertThat(key.toString()).isEqualTo(key.asString());
        }

        @Test
        void keys_differingInOneComponent_neverShareText() {
            var base = DeclaredRouteKey.declaredRouteKey(ARTIFACT, "GET", "/orders").unwrap();

            assertThat(DeclaredRouteKey.declaredRouteKey(OTHER, "GET", "/orders").unwrap().asString()).isNotEqualTo(base.asString());
            assertThat(DeclaredRouteKey.declaredRouteKey(ARTIFACT, "PUT", "/orders").unwrap().asString()).isNotEqualTo(base.asString());
            assertThat(DeclaredRouteKey.declaredRouteKey(ARTIFACT, "GET", "/orders/").unwrap().asString()).isNotEqualTo(base.asString());
        }

        @Test
        void factory_refusesAComponentThatWouldNotRoundTrip() {
            assertThat(DeclaredRouteKey.declaredRouteKey(ARTIFACT, "GE/T", "/orders").isFailure()).as("method holding the separator").isTrue();
            assertThat(DeclaredRouteKey.declaredRouteKey(ARTIFACT, "", "/orders").isFailure()).as("blank method").isTrue();
            assertThat(DeclaredRouteKey.declaredRouteKey(ARTIFACT, "GET ", "/orders").isFailure()).as("method with whitespace").isTrue();
            assertThat(DeclaredRouteKey.declaredRouteKey(ARTIFACT, "GET", "").isFailure()).as("empty path").isTrue();
            assertThat(DeclaredRouteKey.declaredRouteKey(ARTIFACT, "GET", "  ").isFailure()).as("blank path").isTrue();
        }

        @Test
        void parse_refusesMalformedText() {
            assertThat(DeclaredRouteKey.declaredRouteKey("").isFailure()).isTrue();
            assertThat(DeclaredRouteKey.declaredRouteKey("declared-route").isFailure()).isTrue();
            assertThat(DeclaredRouteKey.declaredRouteKey("declared-route/").isFailure()).isTrue();
            assertThat(DeclaredRouteKey.declaredRouteKey("declared-route/org.example:orders-slice:1.2.3").isFailure()).isTrue();
            assertThat(DeclaredRouteKey.declaredRouteKey("declared-route/org.example:orders-slice:1.2.3/GET").isFailure()).isTrue();
            assertThat(DeclaredRouteKey.declaredRouteKey("declared-route/org.example:orders-slice:1.2.3/GET/").isFailure()).isTrue();
            assertThat(DeclaredRouteKey.declaredRouteKey("declared-route//GET//orders").isFailure()).isTrue();
            assertThat(DeclaredRouteKey.declaredRouteKey("declared-route/not-an-artifact/GET//orders").isFailure()).isTrue();
            assertThat(DeclaredRouteKey.declaredRouteKey("node-routes/core-7/org.example:orders-slice:1.2.3").isFailure()).isTrue();
        }

        @Test
        void key_isARuntimeKey_neverBackedUp() {
            var key = DeclaredRouteKey.declaredRouteKey(ARTIFACT, "GET", "/orders").unwrap();

            assertThat(key).isInstanceOf(RuntimeKey.class);
            assertThat(key).isNotInstanceOf(ClusterStateKey.class);
        }
    }

    @Nested
    class Wire {
        @Test
        void key_roundTrips_onTheBinaryPath() {
            var original = DeclaredRouteKey.declaredRouteKey(ARTIFACT, "GET", "/orders/{id}/items").unwrap();

            assertThat(roundTrip(original)).isEqualTo(original);
        }

        @Test
        void declaredState_roundTrips() {
            assertValueRoundTrips(RouteState.Declared.declared());
        }

        @Test
        void publishedState_roundTrips() {
            assertValueRoundTrips(RouteState.Published.published());
        }

        @Test
        void failedState_roundTrips_withItsReason() {
            var decoded = assertValueRoundTrips(RouteState.Failed.failed("slice failed to load: ünïcødé"));

            assertThat(decoded.state()).isEqualTo(RouteState.Failed.failed("slice failed to load: ünïcødé"));
        }

        /// The arming counterpart. A codec that wrote every state under one tag would still return an equal record for the
        /// state it was tested with, so the three states must stay three distinct values across the wire.
        @Test
        void threeStates_staySeparateAcrossTheWire() {
            var declared = roundTrip(DeclaredRouteValue.declaredRouteValue("org.example:orders-app:1.2.3", "attempt-1", RouteState.Declared.declared()));
            var published = roundTrip(DeclaredRouteValue.declaredRouteValue("org.example:orders-app:1.2.3", "attempt-1", RouteState.Published.published()));
            var failed = roundTrip(DeclaredRouteValue.declaredRouteValue("org.example:orders-app:1.2.3", "attempt-1", RouteState.Failed.failed("x")));

            assertThat(declared.state()).isInstanceOf(RouteState.Declared.class);
            assertThat(published.state()).isInstanceOf(RouteState.Published.class);
            assertThat(failed.state()).isInstanceOf(RouteState.Failed.class);
        }

        @Test
        void appBlueprintValue_roundTrips_withoutRoutes() {
            var original = AppBlueprintValue.appBlueprintValue(blueprint(), true, "attempt-7");

            var decoded = roundTrip(original);

            assertThat(decoded).isEqualTo(original);
            assertThat(decoded.declaredRoutes()).isEmpty();
        }

        @Test
        void appBlueprintValue_roundTrips_withRoutes() {
            var routes = Map.of(ARTIFACT,
                                List.of(DeclaredRoute.declaredRoute("GET", "/orders/{id}"), DeclaredRoute.declaredRoute("POST", "/orders")),
                                OTHER,
                                List.of(DeclaredRoute.declaredRoute("GET", "/stock")));
            var original = AppBlueprintValue.appBlueprintValue(blueprint(), false, "attempt-8", routes);

            var decoded = roundTrip(original);

            assertThat(decoded).isEqualTo(original);
            assertThat(decoded.declaredRoutes()).containsExactlyInAnyOrderEntriesOf(routes);
            assertThat(decoded.declaredRoutes().get(ARTIFACT)).containsExactly(DeclaredRoute.declaredRoute("GET", "/orders/{id}"),
                                                                              DeclaredRoute.declaredRoute("POST", "/orders"));
        }

        @Test
        void appBlueprintValue_conveniencePaths_defaultTheRoutesToEmpty() {
            assertThat(AppBlueprintValue.appBlueprintValue(blueprint()).declaredRoutes()).isEmpty();
            assertThat(AppBlueprintValue.appBlueprintValue(blueprint(), true).declaredRoutes()).isEmpty();
            assertThat(AppBlueprintValue.appBlueprintValue(blueprint(), true, "a").declaredRoutes()).isEmpty();
            assertThat(new AppBlueprintValue(blueprint()).declaredRoutes()).isEmpty();
            assertThat(new AppBlueprintValue(blueprint(), true).declaredRoutes()).isEmpty();
        }

        @Test
        void appBlueprintValue_doesNotSeeLaterChangesToTheRouteLists() {
            var source = new ArrayList<>(List.of(DeclaredRoute.declaredRoute("GET", "/orders")));
            var value = AppBlueprintValue.appBlueprintValue(blueprint(), false, "a", Map.of(ARTIFACT, source));

            source.add(DeclaredRoute.declaredRoute("DELETE", "/injected"));

            assertThat(value.declaredRoutes().get(ARTIFACT)).containsExactly(DeclaredRoute.declaredRoute("GET", "/orders"));
        }

        private DeclaredRouteValue assertValueRoundTrips(RouteState state) {
            var original = DeclaredRouteValue.declaredRouteValue("org.example:orders-app:1.2.3", "attempt-1", state);
            var decoded = roundTrip(original);

            assertThat(decoded).isEqualTo(original);
            assertThat(decoded.blueprintId()).isEqualTo("org.example:orders-app:1.2.3");
            assertThat(decoded.attemptId()).isEqualTo("attempt-1");

            return decoded;
        }
    }

    private static ExpandedBlueprint blueprint() {
        var id = BlueprintId.blueprintId("org.example:orders-app:1.2.3").unwrap();
        var slice = ResolvedSlice.resolvedSlice(ARTIFACT, 1, false).unwrap();

        return ExpandedBlueprint.expandedBlueprint(id, List.of(slice));
    }

    private static <T> T roundTrip(T original) {
        var buffer = Unpooled.buffer();

        CODEC.write(buffer, original);

        return CODEC.read(buffer);
    }
}
