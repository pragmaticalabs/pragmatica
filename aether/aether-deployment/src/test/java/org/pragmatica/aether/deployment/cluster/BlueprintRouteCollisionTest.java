// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceManifest;
import org.pragmatica.aether.slice.blueprint.ExpanderError;
import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// #1206: an identical-prefix route collision is refused at blueprint admission, naming both slices and the route, whether
/// the two slices are in one blueprint or in two blueprints published one after the other. Real slice jars carry a
/// `META-INF/slice/*.manifest` topology whose routes are what admission reads.
class BlueprintRouteCollisionTest {
    private static final Cause NOT_IN_REPOSITORY = (Repository.Absent) () -> "Artifact not present in local repository";

    @TempDir
    Path tempDir;

    private BlueprintPublishOwnershipTest.TestKVStore store;
    private BlueprintPublishOwnershipTest.TestClusterNode cluster;
    private final Map<Artifact, Path> jars = new HashMap<>();

    @BeforeEach
    void setUp() {
        store = new BlueprintPublishOwnershipTest.TestKVStore();
        cluster = new BlueprintPublishOwnershipTest.TestClusterNode(store);
    }

    private void slice(String coordinates, String... methodAndPath) throws IOException {
        versionedSlice(coordinates, 0, methodAndPath);
    }

    /// A slice whose routes were declared under API `version` (its manifest path composed `{apiPrefix}/v{version}{template}`).
    private void versionedSlice(String coordinates, int version, String... methodAndPath) throws IOException {
        var artifact = Artifact.artifact(coordinates).unwrap();
        var manifest = new Manifest();
        var attributes = manifest.getMainAttributes();

        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(SliceManifest.SLICE_ARTIFACT_ATTR, artifact.asString());
        attributes.putValue(SliceManifest.SLICE_CLASS_ATTR, "org.example.Slice" + jars.size());
        attributes.putValue(SliceManifest.ENVELOPE_VERSION_ATTR, "1000");

        var topology = new StringBuilder("slice.name=Slice").append(jars.size())
                                                            .append("\nroutes.count=")
                                                            .append(methodAndPath.length / 2)
                                                            .append('\n');

        for (int i = 0; i < methodAndPath.length; i += 2) {
            topology.append("route.").append(i / 2).append(".method=").append(methodAndPath[i]).append('\n')
                    .append("route.").append(i / 2).append(".path=").append(methodAndPath[i + 1]).append('\n')
                    .append("route.").append(i / 2).append(".handler=h").append(i / 2).append('\n')
                    .append("route.").append(i / 2).append(".version=").append(version).append('\n');
        }

        var target = tempDir.resolve(artifact.artifactId().id() + "-" + artifact.version().withQualifier() + ".jar");

        try (var out = new JarOutputStream(Files.newOutputStream(target), manifest)) {
            out.putNextEntry(new ZipEntry("META-INF/slice/Slice" + jars.size() + ".manifest"));
            out.write(topology.toString().getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        jars.put(artifact, target);
    }

    private Repository repository() {
        return artifact -> jars.containsKey(artifact)
                           ? Result.lift(Causes::fromThrowable, () -> jars.get(artifact).toUri().toURL())
                                   .flatMap(url -> Location.location(artifact, url))
                                   .async()
                           : NOT_IN_REPOSITORY.promise();
    }

    private Result<PublishedBlueprint> publish(String blueprintId, String... slices) {
        return publish(false, blueprintId, slices);
    }

    private Result<PublishedBlueprint> publish(boolean versionInHeader, String blueprintId, String... slices) {
        var dsl = new StringBuilder("id = \"").append(blueprintId).append("\"\n");

        for (var slice : slices) {
            dsl.append("\n[[slices]]\nartifact = \"").append(slice).append("\"\ninstances = 3\n");
        }

        return new BlueprintServiceInstance(cluster,
                                            store,
                                            repository(),
                                            Option.empty(),
                                            Option.empty(),
                                            OperatorWarningSink.logOnly(),
                                            versionInHeader).publish(dsl.toString())
                                                            .await();
    }

    private static ExpanderError.RoutePrefixConflictsWithStored conflictOf(Result<PublishedBlueprint> result) {
        assertThat(result.isFailure()).as("the publish must be refused").isTrue();
        var cause = result.fold(failure -> failure, _ -> null);

        assertThat(cause).isInstanceOf(BlueprintConflict.class);
        assertThat(((HttpStatusAware) cause).httpStatus()).as("a conflict with a stored blueprint is a 409").isEqualTo(HttpStatus.CONFLICT);

        return (ExpanderError.RoutePrefixConflictsWithStored) ((BlueprintConflict) cause).origin();
    }

    private static ExpanderError.RoutePrefixCollisions collisionsOf(Result<PublishedBlueprint> result) {
        assertThat(result.isFailure()).as("the publish must be refused").isTrue();
        var cause = result.fold(failure -> failure, _ -> null);

        assertThat(cause).isInstanceOf(BlueprintRejected.class);
        assertThat(((HttpStatusAware) cause).httpStatus()).as("a collision inside one blueprint is a malformed request: 400")
                                                          .isEqualTo(HttpStatus.BAD_REQUEST);

        return (ExpanderError.RoutePrefixCollisions) ((BlueprintRejected) cause).origin();
    }

    @Test
    void oneBlueprintWithACollision_isRefused_namingBothSlicesAndTheRoute_andStoresNothing() throws IOException {
        slice("org.example:alpha:1.0.0", "GET", "/api/echo/health");
        slice("org.example:beta:1.0.0", "GET", "/api/echo/health");

        var refused = collisionsOf(publish("org.example:app:1.0.0", "org.example:alpha:1.0.0", "org.example:beta:1.0.0"));

        assertThat(refused.collisions()).singleElement().satisfies(collision -> {
            assertThat(collision.method()).isEqualTo("GET");
            assertThat(collision.path()).isEqualTo("/api/echo/health");
            assertThat(collision.first()).isEqualTo("org.example:alpha:1.0.0");
            assertThat(collision.second()).isEqualTo("org.example:beta:1.0.0");
        });
        assertThat(cluster.batches).as("a refused publish applies nothing").isEmpty();
    }

    @Test
    void secondBlueprintCollidingWithTheFirst_isRefusedAtTheLaterPublish() throws IOException {
        slice("org.example:alpha:1.0.0", "GET", "/api/echo/health");
        slice("org.example:beta:1.0.0", "GET", "/api/echo/health");

        assertThat(publish("org.example:first:1.0.0", "org.example:alpha:1.0.0").isSuccess()).as("the first blueprint is admitted").isTrue();
        var batchesAfterFirst = cluster.batches.size();

        var refused = conflictOf(publish("org.example:second:1.0.0", "org.example:beta:1.0.0"));

        assertThat(refused.conflicts()).singleElement().satisfies(conflict -> {
            assertThat(conflict.path()).isEqualTo("/api/echo/health");
            assertThat(conflict.first()).as("this blueprint's slice").isEqualTo("org.example:beta:1.0.0");
            assertThat(conflict.second()).as("the stored slice").isEqualTo("org.example:alpha:1.0.0");
            assertThat(conflict.storedBlueprint()).as("the stored blueprint is named").isEqualTo("org.example:first:1.0.0");
        });
        assertThat(cluster.batches).as("the refused publish applies nothing").hasSize(batchesAfterFirst);
    }

    /// Overlapping but not identical routes are admitted, in one blueprint and across two.
    @Test
    void overlappingButDifferentPrefixes_areAdmitted_inOneBlueprintAndAcrossTwo() throws IOException {
        slice("org.example:alpha:1.0.0", "GET", "/api/orders", "GET", "/api/orders/{id}");
        slice("org.example:beta:1.0.0", "GET", "/api/orders/export", "GET", "/api/orders/{id}/items");
        slice("org.example:gamma:1.0.0", "POST", "/api/orders");

        assertThat(publish("org.example:one:1.0.0", "org.example:alpha:1.0.0", "org.example:beta:1.0.0").isSuccess()).isTrue();
        assertThat(publish("org.example:two:1.0.0", "org.example:gamma:1.0.0").isSuccess()).isTrue();
    }

    /// Republishing a blueprint replaces its own earlier version: its own routes are not a collision with itself.
    @Test
    void republishingTheSameBlueprint_isNotACollisionWithItself() throws IOException {
        slice("org.example:alpha:1.0.0", "GET", "/api/echo/health");

        assertThat(publish("org.example:first:1.0.0", "org.example:alpha:1.0.0").isSuccess()).isTrue();
        assertThat(publish("org.example:first:1.0.0", "org.example:alpha:1.0.0").isSuccess()).isTrue();
    }

    /// A republish REPLACES the blueprint: a slice it drops no longer claims its route, so the slice that takes the route over
    /// is not refused because of the version being replaced.
    @Test
    void republishingTheSameBlueprint_withAnotherSliceOnTheSameRoute_replacesTheOldClaim() throws IOException {
        slice("org.example:alpha:1.0.0", "GET", "/api/echo/health");
        slice("org.example:beta:1.0.0", "GET", "/api/echo/health");

        assertThat(publish("org.example:first:1.0.0", "org.example:alpha:1.0.0").isSuccess()).isTrue();
        assertThat(publish("org.example:first:1.0.0", "org.example:beta:1.0.0").isSuccess()).as("alpha is dropped by the republish").isTrue();
    }

    /// F1: a NEWER VERSION of a blueprint replaces it, so it never conflicts with itself: `first:2.0.0` moving a route from slice
    /// alpha to slice beta is admitted although `first:1.0.0` is still stored, while a DIFFERENT blueprint declaring that route
    /// still gets a 409 naming the stored blueprint.
    @Test
    void aNewerVersionOfTheSameBlueprint_replacesItsOwnClaim_whileADifferentBlueprintStillConflicts() throws IOException {
        slice("org.example:alpha:1.0.0", "GET", "/api/echo/health");
        slice("org.example:beta:1.0.0", "GET", "/api/echo/health");
        slice("org.example:gamma:1.0.0", "GET", "/api/echo/health");

        assertThat(publish("org.example:first:1.0.0", "org.example:alpha:1.0.0").isSuccess()).isTrue();
        assertThat(publish("org.example:first:2.0.0", "org.example:beta:1.0.0").isSuccess()).as("first:2.0.0 replaces first:1.0.0").isTrue();

        var conflict = conflictOf(publish("org.example:other:1.0.0", "org.example:gamma:1.0.0"));

        assertThat(conflict.conflicts()).singleElement().extracting(ExpanderError.RouteCollision::storedBlueprint).asString().startsWith("org.example:first:");
    }

    /// F3: mid-segment placeholders are one route at runtime: 400 inside one blueprint, 409 against a stored one.
    @Test
    void midSegmentPlaceholders_areRefused_through_publish() throws IOException {
        slice("org.example:alpha:1.0.0", "GET", "/files/x{a}");
        slice("org.example:beta:1.0.0", "GET", "/files/x{b}");

        assertThat(collisionsOf(publish("org.example:one:1.0.0", "org.example:alpha:1.0.0", "org.example:beta:1.0.0")).collisions()).hasSize(1);

        assertThat(publish("org.example:two:1.0.0", "org.example:alpha:1.0.0").isSuccess()).isTrue();
        assertThat(conflictOf(publish("org.example:three:1.0.0", "org.example:beta:1.0.0")).conflicts()).hasSize(1);
    }

    /// F2: versioned paths of two slices are two routes in PATH mode and one in HEADER mode, where the runtime mounts both at
    /// the same path: admitted in one, refused in the other, by the same publish.
    @Test
    void versionedPathsOfTwoSlices_areAdmittedInPathMode_andRefusedInHeaderMode() throws IOException {
        versionedSlice("org.example:alpha:1.0.0", 1, "GET", "/api/v1/orders");
        versionedSlice("org.example:beta:1.0.0", 2, "GET", "/api/v2/orders");

        assertThat(publish(false, "org.example:one:1.0.0", "org.example:alpha:1.0.0", "org.example:beta:1.0.0").isSuccess()).as("PATH mode").isTrue();
        assertThat(collisionsOf(publish(true, "org.example:two:1.0.0", "org.example:alpha:1.0.0", "org.example:beta:1.0.0")).collisions())
            .as("HEADER mode")
            .hasSize(1);
    }
}
