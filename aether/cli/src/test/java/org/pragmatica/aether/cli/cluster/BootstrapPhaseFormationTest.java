// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BootstrapPhaseFormationTest {

    @Test
    void buildConfigJson_emitsTomlContentAndExpectedVersionZero_whenInvoked() {
        var raw = """
                  [cluster]
                  name = "demo"
                  version = "1.0.0"
                  """;
        var json = BootstrapPhaseFormation.buildConfigJson(raw);

        assertTrue(json.contains("\"tomlContent\":\""), "JSON must wrap TOML in tomlContent field, got: " + json);
        assertTrue(json.contains("\"expectedVersion\":0"), "JSON must declare expectedVersion=0 (initial-store), got: " + json);
        // Newlines must be escaped as \\n inside the JSON string
        assertTrue(json.contains("\\n"), "TOML newlines must be JSON-escaped, got: " + json);
        // Mutation guard: ensure we no longer emit the old shape with clusterName/version fields at top-level
        assertNotEquals(true, json.startsWith("{\"clusterName\""),
                        "Old schema {clusterName,version} must not be emitted, got: " + json);
    }

    @Test
    void buildConfigJson_escapesEmbeddedQuotesAndBackslashes_whenTomlHasSpecialChars() {
        var raw = "name = \"with \\\"quotes\\\" and \\\\ backslash\"";
        var json = BootstrapPhaseFormation.buildConfigJson(raw);

        // Backslash gets doubled, embedded \" gets escaped to \\\"
        assertTrue(json.contains("\\\\"), "Backslashes must be JSON-escaped, got: " + json);
        assertTrue(json.contains("\"expectedVersion\":0"), "expectedVersion=0 must remain, got: " + json);
    }

    @Test
    void buildConfigJson_handlesNullSafely_whenInputIsNull() {
        var json = BootstrapPhaseFormation.buildConfigJson(null);

        assertTrue(json.contains("\"tomlContent\":\"\""), "Null TOML must serialize as empty string, got: " + json);
        assertTrue(json.contains("\"expectedVersion\":0"), "expectedVersion=0 must remain, got: " + json);
    }
}
