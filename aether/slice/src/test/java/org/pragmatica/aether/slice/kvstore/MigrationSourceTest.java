// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.kvstore.MigrationSource.FromArtifact;
import org.pragmatica.aether.slice.kvstore.MigrationSource.FromSet;
import org.pragmatica.aether.slice.kvstore.MigrationSourceError.Absent;
import org.pragmatica.aether.slice.kvstore.MigrationSourceError.Malformed;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;


/// T1 (#2100 E-B): the typed reading of `SchemaVersionValue.artifactCoords`, and the carry-forward of a
/// `set:` string through `withStatus`.
class MigrationSourceTest {
    private static final String COORDS = "org.example:orders-schema:1.2.3";
    private static final String SET = "set:durable-entity-pg@1";
    private static final BlueprintId OWNER = BlueprintId.blueprintId("org.example:orders-app:1.2.3").unwrap();

    private static Object outcome(Result<?> result) {
        return result.<Object>fold(cause -> cause, value -> value);
    }

    private static SchemaVersionValue recordWith(String coords) {
        return SchemaVersionValue.schemaVersionValue("database.main", 1, "V1__a.sql", SchemaStatus.PENDING, coords, OWNER);
    }

    @Test
    void migrationSource_artifactCoords_readsFromArtifact() {
        var expected = FromArtifact.fromArtifact(Artifact.artifact(COORDS).unwrap(), COORDS);

        assertThat(outcome(MigrationSource.migrationSource(COORDS))).isEqualTo(expected);
    }

    @Test
    void migrationSource_setString_readsFromSet() {
        assertThat(outcome(MigrationSource.migrationSource(SET))).isEqualTo(FromSet.fromSet("durable-entity-pg", 1).unwrap());
    }

    @Test
    void migrationSource_bothArms_roundTripThroughDisplayInputs() {
        var artifact = FromArtifact.fromArtifact(Artifact.artifact(COORDS).unwrap(), COORDS);

        assertThat(artifact.display()).isEqualTo(COORDS);
        assertThat(FromSet.fromSet("a-b", 12).unwrap().display()).isEqualTo("migration set a-b v12");
        assertThat(outcome(MigrationSource.migrationSource("set:a-b@12"))).isEqualTo(FromSet.fromSet("a-b", 12).unwrap());
    }

    @Test
    void migrationSource_blank_isAbsent() {
        for (var raw : Arrays.asList(null, "", " ", "\t\n")) {
            assertThat(outcome(MigrationSource.migrationSource(raw))).describedAs("raw=%s", raw)
                                                                      .isSameAs(MigrationSourceError.Absent.ABSENT);
        }
    }

    @Test
    void migrationSource_unreadable_isMalformedKeepingRaw() {
        var unreadable = List.of("nonsense", "set:", "set:x", "set:x@", "set:@1", "set:x@0", "set:x@-1", "set:x@one",
                                 "set:X@1", "set:1x@1", "set:x_y@1", "set:x@1@2", "org.example:orders-schema", "SET:x@1");

        for (var raw : unreadable) {
            assertMalformedKeeping(raw);
        }
    }

    /// Expected raw and message are built here by hand, not through `Malformed.malformed`, so a broken
    /// factory cannot hide behind a test that shares it.
    private static void assertMalformedKeeping(String raw) {
        var cause = outcome(MigrationSource.migrationSource(raw));

        assertThat(cause).describedAs("raw=%s", raw).isInstanceOf(Malformed.class);
        assertThat(((Malformed) cause).raw()).describedAs("raw kept for %s", raw).isEqualTo(raw);
        assertThat(((Malformed) cause).message()).isEqualTo("Malformed migration source '" + raw + "'");
    }

    @Test
    void migrationSource_nonCanonicalSetVersion_isMalformedKeepingRaw() {
        var nonCanonical = List.of("set:x@+1", "set:x@01", "set:x@001", "set:a@ 1", "set:a@1 ", "set: a@1", "set:x@\u0663",
                                   "set:x@99999999999", "set:x@2147483648", "set:x@1\n");

        for (var raw : nonCanonical) {
            assertMalformedKeeping(raw);
        }
    }

    @Test
    void migrationSource_canonicalSetVersion_readsAtTheBounds() {
        assertThat(outcome(MigrationSource.migrationSource("set:x@2147483647"))).isEqualTo(FromSet.fromSet("x", Integer.MAX_VALUE).unwrap());
        assertThat(outcome(MigrationSource.migrationSource("set:x@10"))).isEqualTo(FromSet.fromSet("x", 10).unwrap());
    }

    @Test
    void display_artifactCoordsWithNonCanonicalVersion_isByteIdenticalToRaw() {
        for (var raw : List.of("org.example:a:1.0.0.RELEASE", "org.example:a:01.2.3", "org.example:a:+1.2.3")) {
            var source = MigrationSource.migrationSource(raw);

            assertThat(source.isSuccess()).describedAs("raw=%s reads", raw).isTrue();
            assertThat(outcome(source)).describedAs("raw=%s", raw).isInstanceOf(FromArtifact.class);
            assertThat(source.unwrap().display()).describedAs("source display of %s", raw).isEqualTo(raw);
            assertThat(MigrationSource.display(raw)).describedAs("static display of %s", raw).isEqualTo(raw);
        }
    }

    @Test
    void migrationSource_artifactCoordsContainingSetMidString_readsAsArtifact() {
        var raw = "org.set:app:1.0.0";

        assertThat(outcome(MigrationSource.migrationSource(raw))).isEqualTo(FromArtifact.fromArtifact(Artifact.artifact(raw).unwrap(), raw));
    }

    @Test
    void absentAndMalformed_messages_areTheDocumentedText() {
        assertThat(Absent.ABSENT.message()).isEqualTo("Migration source is absent");
        assertThat(Malformed.malformed("q").message()).isEqualTo("Malformed migration source 'q'");
        assertThat(Malformed.malformed("q").raw()).isEqualTo("q");
    }

    @Test
    void migrationSource_setIdLength_boundaryIs63Characters() {
        var longest = "a".repeat(63);

        assertThat(MigrationSource.migrationSource("set:" + longest + "@1").isSuccess()).isTrue();
        assertThat(MigrationSource.migrationSource("set:" + longest + "a@1").isFailure()).isTrue();
    }

    @Test
    void display_artifactCoords_isByteIdenticalToRaw() {
        assertThat(MigrationSource.display(COORDS)).isEqualTo(COORDS);
    }

    @Test
    void display_rawString_neverFails() {
        assertThat(MigrationSource.display(SET)).isEqualTo("migration set durable-entity-pg v1");
        assertThat(MigrationSource.display("set:broken")).isEqualTo("set:broken");
        assertThat(MigrationSource.display(null)).isEmpty();
        assertThat(MigrationSource.display("  ")).isEmpty();
    }

    @Test
    void artifact_setString_isRefused() {
        assertThat(Artifact.artifact("set:x@1").isFailure()).isTrue();
    }

    @Test
    void source_recordAccessor_readsBothArms() {
        assertThat(outcome(recordWith(SET).source())).isEqualTo(FromSet.fromSet("durable-entity-pg", 1).unwrap());
        assertThat(outcome(recordWith(COORDS).source())).isInstanceOf(FromArtifact.class);
        assertThat(outcome(recordWith("").source())).isSameAs(MigrationSourceError.Absent.ABSENT);
    }

    @Test
    void withStatus_setString_carriesCoordsByteEqual() {
        var carried = recordWith(SET).withStatus(SchemaStatus.COMPLETED);

        assertThat(carried.artifactCoords()).isEqualTo(SET);
        assertThat(outcome(carried.source())).isEqualTo(FromSet.fromSet("durable-entity-pg", 1).unwrap());
    }
}
