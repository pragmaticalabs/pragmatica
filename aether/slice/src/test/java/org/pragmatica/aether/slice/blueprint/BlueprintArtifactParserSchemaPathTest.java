// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.pragmatica.lang.Result;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// #2100 E-B (T3 parser rows): `@` is reserved in a datasource key for derived keys, so a schema path
/// carrying it is refused; every other path still parses, its checksum reached through `migrationEntry`.
class BlueprintArtifactParserSchemaPathTest {
    private static final String SQL = "CREATE TABLE orders(id BIGINT PRIMARY KEY);";

    private static byte[] jar(String... schemaPaths) {
        var bytes = new ByteArrayOutputStream();

        try (var zip = new ZipOutputStream(bytes)) {
            put(zip, "META-INF/blueprint.toml", "id = \"org.example:orders-app:1.0.0\"\n\n[[slices]]\nartifact = \"org.example:orders-api:1.0.0\"\ninstances = 3\n");
            for (var schemaPath : schemaPaths) {
                put(zip, schemaPath, SQL);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build test blueprint jar", e);
        }

        return bytes.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String refusal(Result<?> result) {
        return result.<String>fold(cause -> cause.message(), _ -> "");
    }

    @Test
    void parse_atInDatasourceDirectory_isRefusedNamingThePath() {
        var result = BlueprintArtifactParser.parse(jar("schema/main@entity_orders/V1__a.sql"));

        assertThat(result.isFailure()).isTrue();
        assertThat(refusal(result)).contains("schema/main@entity_orders/V1__a.sql").contains("'@'");
    }

    @Test
    void parse_pathWithoutAt_stillParsesWithChecksumFromMigrationEntry() {
        var crc = new CRC32();

        crc.update(SQL.getBytes(StandardCharsets.UTF_8));

        var artifact = BlueprintArtifactParser.parse(jar("schema/main/V1__a.sql")).unwrap();
        var entry = artifact.schemaMigrations().get("database.main").getFirst();

        assertThat(entry).isEqualTo(MigrationEntry.migrationEntry("V1__a.sql", SQL));
        assertThat(entry.checksum()).isEqualTo(crc.getValue());
    }

    @Test
    void parse_severalRefusedPaths_reportsTheFirstInArchiveOrder() {
        var result = BlueprintArtifactParser.parse(jar("schema/first@a/V1__a.sql", "schema/second@b/V1__b.sql"));

        assertThat(result.isFailure()).isTrue();
        assertThat(refusal(result)).contains("schema/first@a/V1__a.sql").doesNotContain("schema/second@b/V1__b.sql");
    }

    @Test
    void parse_atInFilename_isStillAccepted() {
        var artifact = BlueprintArtifactParser.parse(jar("schema/main/V1__a@b.sql")).unwrap();

        assertThat(artifact.schemaMigrations().get("database.main")).extracting(MigrationEntry::filename).containsExactly("V1__a@b.sql");
    }

    @Test
    void parse_atInNestedSubdirectory_isStillAccepted() {
        var artifact = BlueprintArtifactParser.parse(jar("schema/main/sub@dir/V1__a.sql")).unwrap();

        assertThat(artifact.schemaMigrations().get("database.main")).extracting(MigrationEntry::filename).containsExactly("V1__a.sql");
    }

    @Test
    void parse_nonAsciiSql_checksumIsCrcOfUtf8Bytes() throws IOException {
        var sql = "-- \u00e9\u00e8\u4e2d\nCREATE TABLE t(id BIGINT);";
        var crc = new CRC32();
        var bytes = new ByteArrayOutputStream();

        crc.update(sql.getBytes(StandardCharsets.UTF_8));

        try (var zip = new ZipOutputStream(bytes)) {
            put(zip, "META-INF/blueprint.toml", "id = \"org.example:orders-app:1.0.0\"\n\n[[slices]]\nartifact = \"org.example:orders-api:1.0.0\"\ninstances = 3\n");
            put(zip, "schema/main/V1__a.sql", sql);
        }

        var entry = BlueprintArtifactParser.parse(bytes.toByteArray()).unwrap().schemaMigrations().get("database.main").getFirst();

        assertThat(entry.sql()).isEqualTo(sql);
        assertThat(entry.checksum()).isEqualTo(crc.getValue());
    }
}
