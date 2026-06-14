// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

/// #308: the CLI must honor `--format json` on error paths so a scripted client can parse
/// failures. `OutputFormatter.printError(Cause, options)` emits a structured `{"error":...}`
/// object under `--format json` and the human-readable `Error: <message>` form otherwise.
class OutputFormatterErrorTest {
    private PrintStream originalErr;
    private ByteArrayOutputStream errCapture;

    @BeforeEach
    void redirectErr() {
        originalErr = System.err;
        errCapture = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errCapture, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreErr() {
        System.setErr(originalErr);
    }

    @Test
    void printError_emitsJsonObject_whenFormatJson() {
        var exit = OutputFormatter.printError(new TestCause("boom"), parseOptions("--format", "json"));

        assertThat(exit).isEqualTo(ExitCode.ERROR);
        assertThat(errCapture.toString(StandardCharsets.UTF_8)).contains("{\"error\":\"boom\"}");
    }

    @Test
    void printError_emitsPlainText_whenFormatTable() {
        var exit = OutputFormatter.printError(new TestCause("boom"), parseOptions("--format", "table"));

        assertThat(exit).isEqualTo(ExitCode.ERROR);
        assertThat(errCapture.toString(StandardCharsets.UTF_8)).contains("Error: boom");
    }

    @Test
    void printError_escapesQuotes_whenFormatJson() {
        var exit = OutputFormatter.printError(new TestCause("bad \"value\""), parseOptions("--format", "json"));

        assertThat(exit).isEqualTo(ExitCode.ERROR);
        assertThat(errCapture.toString(StandardCharsets.UTF_8)).contains("\\\"value\\\"");
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

    private record TestCause(String message) implements Cause {}
}
