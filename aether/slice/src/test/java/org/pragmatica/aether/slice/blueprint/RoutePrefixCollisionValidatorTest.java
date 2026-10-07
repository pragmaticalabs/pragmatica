// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import java.util.List;

import org.pragmatica.aether.slice.topology.SliceTopology;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1206: two slices of different artifacts that declare the same route are refused at admission. Only IDENTICAL routes
/// collide; overlapping but different prefixes are admitted (the longer prefix wins for its own paths, as designed).
class RoutePrefixCollisionValidatorTest {
    private static SliceTopology slice(String artifact, String... methodAndPath) {
        var routes = new java.util.ArrayList<SliceTopology.Route>();

        for (int i = 0; i < methodAndPath.length; i += 2) {
            routes.add(new SliceTopology.Route(methodAndPath[i], methodAndPath[i + 1], "handler" + i));
        }

        return new SliceTopology(artifact.substring(artifact.indexOf(':') + 1, artifact.lastIndexOf(':')),
                                 artifact,
                                 routes,
                                 List.of(),
                                 List.of(),
                                 List.of(),
                                 List.of());
    }

    private static ExpanderError.RoutePrefixCollisions refusal(List<SliceTopology> admitted, List<SliceTopology> existing) {
        var result = RoutePrefixCollisionValidator.validate(admitted, existing);

        assertThat(result.isFailure()).as("expected a refusal").isTrue();

        return (ExpanderError.RoutePrefixCollisions) result.fold(cause -> cause, _ -> null);
    }

    @Test
    void identicalRouteInTwoSlicesOfOneBlueprint_isRefused_namingBothSlicesAndTheRoute() {
        var refused = refusal(List.of(slice("org.a:one:1.0.0", "GET", "/api/echo/health"),
                                      slice("org.b:two:1.0.0", "GET", "/api/echo/health")),
                              List.of());

        assertThat(refused.collisions()).singleElement().satisfies(collision -> {
            assertThat(collision.method()).isEqualTo("GET");
            assertThat(collision.path()).isEqualTo("/api/echo/health");
            assertThat(collision.first()).isEqualTo("org.a:one:1.0.0");
            assertThat(collision.second()).isEqualTo("org.b:two:1.0.0");
        });
        assertThat(refused.message()).contains("GET /api/echo/health", "org.a:one:1.0.0", "org.b:two:1.0.0");
    }

    @Test
    void pathParameterNamesAndTrailingSlashesAreNotSignificant() {
        var refused = refusal(List.of(slice("org.a:one:1.0.0", "get", "/orders/{id}/"),
                                      slice("org.b:two:1.0.0", "GET", "/orders/{orderId}")),
                              List.of());

        assertThat(refused.collisions()).singleElement().extracting(ExpanderError.RouteCollision::path).isEqualTo("/orders/{}");
    }

    @Test
    void collisionWithAnAlreadyStoredBlueprint_isRefused() {
        var refused = refusal(List.of(slice("org.b:two:1.0.0", "POST", "/api/x")),
                              List.of(slice("org.a:one:1.0.0", "POST", "/api/x")));

        assertThat(refused.collisions()).singleElement().satisfies(collision -> {
            assertThat(collision.first()).isEqualTo("org.a:one:1.0.0");
            assertThat(collision.second()).isEqualTo("org.b:two:1.0.0");
        });
    }

    /// The control: overlapping but NOT identical routes are admitted, so the longest prefix keeps winning for the paths it
    /// covers. Different methods on one path are different routes too.
    @Test
    void overlappingButDifferentRoutes_areAdmitted() {
        var result = RoutePrefixCollisionValidator.validate(List.of(slice("org.a:one:1.0.0",
                                                                          "GET", "/orders",
                                                                          "GET", "/orders/{id}"),
                                                                    slice("org.b:two:1.0.0",
                                                                          "GET", "/orders/export",
                                                                          "GET", "/orders/{id}/items",
                                                                          "POST", "/orders")),
                                                           List.of());

        assertThat(result.isSuccess()).isTrue();
    }

    /// A rolling redeploy publishes two versions of one slice at once: not a collision with itself.
    @Test
    void twoVersionsOfOneArtifact_doNotCollide() {
        var result = RoutePrefixCollisionValidator.validate(List.of(slice("org.a:one:2.0.0", "GET", "/api/x")),
                                                            List.of(slice("org.a:one:1.0.0", "GET", "/api/x")));

        assertThat(result.isSuccess()).isTrue();
    }

    /// A collision already present between two STORED blueprints does not block an unrelated publish.
    @Test
    void collisionBetweenTwoStoredBlueprints_doesNotBlockAnUnrelatedPublish() {
        var result = RoutePrefixCollisionValidator.validate(List.of(slice("org.c:three:1.0.0", "GET", "/api/other")),
                                                            List.of(slice("org.a:one:1.0.0", "GET", "/api/x"),
                                                                    slice("org.b:two:1.0.0", "GET", "/api/x")));

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void everyCollisionIsReported_notOnlyTheFirst() {
        var refused = refusal(List.of(slice("org.a:one:1.0.0", "GET", "/a", "GET", "/b"),
                                      slice("org.b:two:1.0.0", "GET", "/a", "GET", "/b")),
                              List.of());

        assertThat(refused.collisions()).extracting(ExpanderError.RouteCollision::path).containsExactly("/a", "/b");
    }
}
