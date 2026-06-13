// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Regression tests for `MavenMetadataFormatter` — Phase 3 item C8.
///
/// Verifies that the formatter projects a Maven `maven-metadata.xml` body
/// down to a stable JSON envelope `{"groupId","artifactId","versions"}`
/// that downstream `--format json` consumers (test harnesses, scripts) can rely on.
class MavenMetadataFormatterTest {

    @Test
    void toJson_canonicalServerOutput_emitsEnvelope() {
        var xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <metadata>
                  <groupId>com.example</groupId>
                  <artifactId>demo</artifactId>
                  <versioning>
                    <latest>2.0.0</latest>
                    <release>2.0.0</release>
                    <versions>
                      <version>1.0.0</version>
                      <version>1.5.0</version>
                      <version>2.0.0</version>
                    </versions>
                    <lastUpdated>20260521120000</lastUpdated>
                  </versioning>
                </metadata>
                """;

        var json = MavenMetadataFormatter.toJson(xml);

        assertEquals(
                "{\"groupId\":\"com.example\",\"artifactId\":\"demo\",\"versions\":[\"1.0.0\",\"1.5.0\",\"2.0.0\"]}",
                json);
    }

    @Test
    void toJson_singleVersion_emitsSingleEntryArray() {
        var xml = """
                <metadata>
                  <groupId>org.acme</groupId>
                  <artifactId>solo</artifactId>
                  <versioning><versions><version>0.1.0</version></versions></versioning>
                </metadata>
                """;

        var json = MavenMetadataFormatter.toJson(xml);

        assertEquals(
                "{\"groupId\":\"org.acme\",\"artifactId\":\"solo\",\"versions\":[\"0.1.0\"]}",
                json);
    }

    @Test
    void toJson_noVersions_emitsEmptyArray() {
        var xml = """
                <metadata>
                  <groupId>org.acme</groupId>
                  <artifactId>empty</artifactId>
                  <versioning><versions></versions></versioning>
                </metadata>
                """;

        var json = MavenMetadataFormatter.toJson(xml);

        assertEquals(
                "{\"groupId\":\"org.acme\",\"artifactId\":\"empty\",\"versions\":[]}",
                json);
    }

    @Test
    void toJson_xmlEntitiesInVersions_unescaped() {
        var xml = """
                <metadata>
                  <groupId>com.example</groupId>
                  <artifactId>demo</artifactId>
                  <versioning><versions>
                    <version>1.0.0&amp;qa</version>
                  </versions></versioning>
                </metadata>
                """;

        var json = MavenMetadataFormatter.toJson(xml);

        assertTrue(json.contains("\"1.0.0&qa\""), () -> "Expected unescaped '&', got: " + json);
    }

    @Test
    void toJson_missingFields_emitsEmptyStrings() {
        var xml = "<metadata><versioning><versions></versions></versioning></metadata>";

        var json = MavenMetadataFormatter.toJson(xml);

        assertEquals(
                "{\"groupId\":\"\",\"artifactId\":\"\",\"versions\":[]}",
                json);
    }

    @Test
    void toJson_preservesVersionOrder() {
        var xml = """
                <metadata>
                  <groupId>g</groupId>
                  <artifactId>a</artifactId>
                  <versioning><versions>
                    <version>9.9.9</version>
                    <version>0.0.1</version>
                    <version>5.0.0</version>
                  </versions></versioning>
                </metadata>
                """;

        var json = MavenMetadataFormatter.toJson(xml);

        assertEquals(
                "{\"groupId\":\"g\",\"artifactId\":\"a\",\"versions\":[\"9.9.9\",\"0.0.1\",\"5.0.0\"]}",
                json);
    }
}
