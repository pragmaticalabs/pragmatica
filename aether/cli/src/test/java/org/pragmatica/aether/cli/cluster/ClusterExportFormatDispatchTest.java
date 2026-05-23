// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.OutputOptions;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Regression tests for `aether cluster export` format routing — Phase 3 item C7.
///
/// Closes §3.3 I (first bullet) in `aether/docs/internal/audits/integration-test-audit-2026-05-21.md`:
/// the `--format json` flag was previously a silent no-op for the body. After the
/// fix, JSON requests emit the canonical management-API envelope verbatim, while
/// the default (TABLE) format continues to emit the embedded `tomlContent`.
class ClusterExportFormatDispatchTest {
    private static final String SAMPLE_RESPONSE = """
            {
              "tomlContent": "[cluster]\\nname = \\"demo\\"\\n",
              "clusterName": "demo",
              "configVersion": "3",
              "coreCount": "5"
            }
            """;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private PrintStream originalOut;

    @BeforeEach
    void redirectStdout() {
        originalOut = System.out;
        System.setOut(new PrintStream(out));
    }

    @AfterEach
    void restoreStdout() {
        System.setOut(originalOut);
    }

    @Test
    void shouldEmitTomlBody_tableFormat_returnsTrue() {
        assertTrue(ClusterExportCommand.shouldEmitTomlBody(parseOptions()));
    }

    @Test
    void shouldEmitTomlBody_jsonFormat_returnsFalse() {
        assertFalse(ClusterExportCommand.shouldEmitTomlBody(parseOptions("--format", "json")));
    }

    @Test
    void shouldEmitTomlBody_valueFormat_returnsFalse() {
        assertFalse(ClusterExportCommand.shouldEmitTomlBody(parseOptions("--format", "value")));
    }

    @Test
    void renderTomlBody_default_emitsEmbeddedToml() {
        var exitCode = ClusterExportCommand.renderTomlBody(SAMPLE_RESPONSE, false);

        assertEquals(ExitCode.SUCCESS, exitCode);

        var stdout = out.toString();

        assertTrue(stdout.contains("[cluster]"), () -> "Expected TOML section header, got: " + stdout);
        assertTrue(stdout.contains("name = \"demo\""), () -> "Expected TOML body, got: " + stdout);
        assertFalse(stdout.contains("\"tomlContent\""), () -> "JSON envelope leaked into TOML output: " + stdout);
    }

    @Test
    void renderTomlBody_withStatus_emitsHeaderComments() {
        ClusterExportCommand.renderTomlBody(SAMPLE_RESPONSE, true);

        var stdout = out.toString();

        assertTrue(stdout.contains("Exported from cluster \"demo\""),
                   () -> "Expected status header line, got: " + stdout);
        assertTrue(stdout.contains("Config version: 3"),
                   () -> "Expected configVersion comment, got: " + stdout);
        assertTrue(stdout.contains("Core count: 5"),
                   () -> "Expected coreCount comment, got: " + stdout);
    }

    @Test
    void renderTomlBody_malformedResponse_returnsErrorCode() {
        var exitCode = ClusterExportCommand.renderTomlBody("{not valid json", false);

        assertEquals(ExitCode.ERROR, exitCode);
    }

    private static OutputOptions parseOptions(String... args) {
        var options = new OutputOptions();
        new CommandLine(new Holder(options)).parseArgs(args);

        return options;
    }

    @CommandLine.Command(name = "holder")
    private static class Holder {
        @CommandLine.Mixin
        private final OutputOptions options;

        Holder(OutputOptions options) {
            this.options = options;
        }
    }
}
