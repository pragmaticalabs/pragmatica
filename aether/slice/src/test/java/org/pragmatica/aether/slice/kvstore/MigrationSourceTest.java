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
        var expected = FromArtifact.fromArtifact(Artifact.artifact(COORDS).unwrap());

        assertThat(outcome(MigrationSource.migrationSource(COORDS))).isEqualTo(expected);
    }

    @Test
    void migrationSource_setString_readsFromSet() {
        assertThat(outcome(MigrationSource.migrationSource(SET))).isEqualTo(FromSet.fromSet("durable-entity-pg", 1).unwrap());
    }

    @Test
    void migrationSource_bothArms_roundTripThroughDisplayInputs() {
        var artifact = FromArtifact.fromArtifact(Artifact.artifact(COORDS).unwrap());

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
            assertThat(outcome(MigrationSource.migrationSource(raw))).describedAs("raw=%s", raw)
                                                                      .isEqualTo(MigrationSourceError.Malformed.malformed(raw));
        }
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
