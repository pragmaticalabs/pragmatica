// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.aether.slice.topology.SliceTopology;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1206: two slices of different artifacts that declare the same route (the identity the runtime uses) are refused at admission.
/// Only IDENTICAL routes collide; overlapping but different prefixes are admitted (the longer prefix wins for its own paths).
class RoutePrefixCollisionValidatorTest {
    private static SliceTopology slice(String artifact, String... methodAndPath) {
        var routes = new ArrayList<SliceTopology.Route>();

        for (int i = 0; i < methodAndPath.length; i += 2) {
            routes.add(new SliceTopology.Route(methodAndPath[i], methodAndPath[i + 1], "handler" + i));
        }

        return topology(artifact, routes);
    }

    /// A slice whose one route was declared under API version `version`, its path composed as the manifest composes it.
    private static SliceTopology versioned(String artifact, int version, String method, String path) {
        return topology(artifact, List.of(new SliceTopology.Route(method, path, "handler", version)));
    }

    private static SliceTopology topology(String artifact, List<SliceTopology.Route> routes) {
        return new SliceTopology(artifact.substring(artifact.indexOf(':') + 1, artifact.lastIndexOf(':')),
                                 artifact,
                                 routes,
                                 List.of(),
                                 List.of(),
                                 List.of(),
                                 List.of());
    }

    private static List<RoutePrefixCollisionValidator.StoredSlices> stored(SliceTopology... slices) {
        return Stream.of(slices)
                     .map(slice -> RoutePrefixCollisionValidator.StoredSlices.storedSlices("stored:" + slice.sliceName() + ":1.0.0",
                                                                                           List.of(slice)))
                     .toList();
    }

    private static ExpanderError.RoutePrefixCollisions refusal(boolean versionInHeader, SliceTopology... admitted) {
        var result = RoutePrefixCollisionValidator.validate(List.of(admitted), List.of(), versionInHeader);

        assertThat(result.isFailure()).as("expected a refusal").isTrue();

        return (ExpanderError.RoutePrefixCollisions) result.fold(cause -> cause, _ -> null);
    }

    private static ExpanderError.RoutePrefixConflictsWithStored conflict(boolean versionInHeader,
                                                                         SliceTopology admitted,
                                                                         SliceTopology stored) {
        var result = RoutePrefixCollisionValidator.validate(List.of(admitted), stored(stored), versionInHeader);

        assertThat(result.isFailure()).as("expected a conflict").isTrue();

        return (ExpanderError.RoutePrefixConflictsWithStored) result.fold(cause -> cause, _ -> null);
    }

    private static boolean admitted(boolean versionInHeader, SliceTopology... slices) {
        return RoutePrefixCollisionValidator.validate(List.of(slices), List.of(), versionInHeader).isSuccess();
    }

    @Test
    void identicalRouteInTwoSlicesOfOneBlueprint_isRefused_namingBothSlicesAndTheRoute() {
        var refused = refusal(false,
                              slice("org.a:one:1.0.0", "GET", "/api/echo/health"),
                              slice("org.b:two:1.0.0", "GET", "/api/echo/health"));

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
        var refused = refusal(false, slice("org.a:one:1.0.0", "get", "/orders/{id}/"), slice("org.b:two:1.0.0", "GET", "/orders/{orderId}"));

        assertThat(refused.collisions()).singleElement().satisfies(collision -> {
            assertThat(collision.path()).isEqualTo("/orders/{id}/");
            assertThat(collision.otherPath()).isEqualTo("/orders/{orderId}");
        });
        assertThat(refused.message()).as("both templates are shown when they differ").contains("/orders/{id}/", "/orders/{orderId}", "the same route");
    }

    /// F3: a placeholder in the middle of a segment: the runtime prefix is the template up to the first `{`, so `/files/x{a}` and
    /// `/files/x{b}` are one route, and are refused (400 inside one blueprint, 409 against a stored one).
    @Test
    void midSegmentPlaceholders_areTheSameRoute_refusedAs400InsideOneBlueprint_and409AgainstAStoredOne() {
        var inside = refusal(false, slice("org.a:one:1.0.0", "GET", "/files/x{a}"), slice("org.b:two:1.0.0", "GET", "/files/x{b}"));
        var against = conflict(false, slice("org.b:two:1.0.0", "GET", "/files/x{b}"), slice("org.a:one:1.0.0", "GET", "/files/x{a}"));

        assertThat(inside.collisions()).singleElement().extracting(ExpanderError.RouteCollision::otherPath).isEqualTo("/files/x{b}");
        assertThat(against.conflicts()).singleElement().extracting(ExpanderError.RouteCollision::storedBlueprint).isEqualTo("stored:one:1.0.0");
    }

    /// F2: a versioned route's manifest path carries `/v{N}`, its PATH-mode mount. In PATH mode `/api/v1/orders` and `/api/v2/orders`
    /// are two routes; in HEADER mode the runtime mounts both at `/api/orders` and tells versions apart by a header, so two
    /// slices declaring them collide: 400 inside one blueprint, 409 against a stored one.
    @Test
    void versionedPaths_areDistinctInPathMode_andOneRouteInHeaderMode() {
        var alpha = versioned("org.a:one:1.0.0", 1, "GET", "/api/v1/orders");
        var beta = versioned("org.b:two:1.0.0", 2, "GET", "/api/v2/orders");

        assertThat(admitted(false, alpha, beta)).as("PATH mode: two different paths").isTrue();

        var inside = refusal(true, alpha, beta);

        assertThat(inside.collisions()).singleElement().satisfies(collision -> {
            assertThat(collision.path()).isEqualTo("/api/v1/orders");
            assertThat(collision.otherPath()).isEqualTo("/api/v2/orders");
        });
        assertThat(conflict(true, beta, alpha).conflicts()).singleElement().extracting(ExpanderError.RouteCollision::storedBlueprint).isEqualTo("stored:one:1.0.0");
    }

    @Test
    void withoutVersionSegment_removesOnlyTheMountsOwnSegment() {
        assertThat(RoutePrefixCollisionValidator.withoutVersionSegment("/api/v2/orders", 2)).isEqualTo("/api/orders");
        assertThat(RoutePrefixCollisionValidator.withoutVersionSegment("/api/v2", 2)).isEqualTo("/api");
        assertThat(RoutePrefixCollisionValidator.withoutVersionSegment("/api/v22/orders", 2)).as("a longer segment is not the version").isEqualTo("/api/v22/orders");
    }

    /// Routes that differ only in where a literal sits are two routes.
    @Test
    void routesDifferingOnlyInTheLiteralsPosition_areAdmitted() {
        assertThat(admitted(false, slice("org.a:one:1.0.0", "GET", "/a/{id}/b/{x}"), slice("org.b:two:1.0.0", "GET", "/a/{id}/{x}/b"))).isTrue();
    }

    @Test
    void collisionWithAnAlreadyStoredBlueprint_isRefused() {
        var refused = conflict(false, slice("org.b:two:1.0.0", "POST", "/api/x"), slice("org.a:one:1.0.0", "POST", "/api/x"));

        assertThat(refused.conflicts()).singleElement().satisfies(conflict -> {
            assertThat(conflict.first()).as("the slice of the blueprint being published").isEqualTo("org.b:two:1.0.0");
            assertThat(conflict.second()).as("the stored slice").isEqualTo("org.a:one:1.0.0");
            assertThat(conflict.storedBlueprint()).isEqualTo("stored:one:1.0.0");
        });
        assertThat(refused.message()).contains("stored blueprint stored:one:1.0.0", "org.a:one:1.0.0", "POST /api/x");
    }

    /// The control: overlapping but NOT identical routes are admitted, so the longest prefix keeps winning for the paths it
    /// covers. Different methods on one path are different routes too.
    @Test
    void overlappingButDifferentRoutes_areAdmitted() {
        assertThat(admitted(false,
                            slice("org.a:one:1.0.0", "GET", "/orders", "GET", "/orders/{id}"),
                            slice("org.b:two:1.0.0", "GET", "/orders/export", "GET", "/orders/{id}/items", "POST", "/orders"))).isTrue();
    }

    /// A rolling redeploy publishes two versions of one slice at once: not a collision with itself.
    @Test
    void twoVersionsOfOneArtifact_doNotCollide() {
        var result = RoutePrefixCollisionValidator.validate(List.of(slice("org.a:one:2.0.0", "GET", "/api/x")),
                                                            stored(slice("org.a:one:1.0.0", "GET", "/api/x")),
                                                            false);

        assertThat(result.isSuccess()).isTrue();
    }

    /// A collision already present between two STORED blueprints does not block an unrelated publish.
    @Test
    void collisionBetweenTwoStoredBlueprints_doesNotBlockAnUnrelatedPublish() {
        var result = RoutePrefixCollisionValidator.validate(List.of(slice("org.c:three:1.0.0", "GET", "/api/other")),
                                                            stored(slice("org.a:one:1.0.0", "GET", "/api/x"),
                                                                   slice("org.b:two:1.0.0", "GET", "/api/x")),
                                                            false);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void everyCollisionIsReported_notOnlyTheFirst() {
        var refused = refusal(false, slice("org.a:one:1.0.0", "GET", "/a", "GET", "/b"), slice("org.b:two:1.0.0", "GET", "/a", "GET", "/b"));

        assertThat(refused.collisions()).extracting(ExpanderError.RouteCollision::path).containsExactly("/a", "/b");
    }
}
