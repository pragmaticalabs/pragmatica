// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.pragmatica.lang.Cause;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;


/// #308: the CLI must honor `--format json` on error paths so a scripted client can parse
/// failures. `OutputFormatter.printError(Cause, options)` emits a structured `{"error":...}`
/// object under `--format json` and the human-readable `Error: <message>` form otherwise.
class OutputFormatterErrorTest {
    private PrintStream originalErr;
    private PrintStream originalOut;
    private ByteArrayOutputStream errCapture;
    private ByteArrayOutputStream outCapture;

    @BeforeEach
    void redirectStreams() {
        originalErr = System.err;
        originalOut = System.out;
        errCapture = new ByteArrayOutputStream();
        outCapture = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errCapture, true, StandardCharsets.UTF_8));
        System.setOut(new PrintStream(outCapture, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStreams() {
        System.setErr(originalErr);
        System.setOut(originalOut);
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

    /// #1033: a query whose response is an error envelope — `AetherCli.fetch` folds a failed send
    /// into `{"error":"…"}` — must not be rendered as a (possibly empty) result document with exit 0.
    /// The envelope goes to stderr and the exit code is non-zero, in every format, quiet or not.
    private static final String ENVELOPE = "{\"error\":\"Connection failed: Connection refused\"}";

    private static final OutputFormatter.TableSpec NODES_TABLE = new OutputFormatter.TableSpec("Nodes",
                                                                                               List.of(new OutputFormatter.Column("NODE ID",
                                                                                                                                  "nodeId",
                                                                                                                                  30)),
                                                                                               "nodes");

    @Test
    void printQuery_errorEnvelope_tableFormat_exitsError_andDrawsNoTable() {
        var exit = OutputFormatter.printQuery(ENVELOPE, parseOptions("--format", "table"), NODES_TABLE);

        assertThat(exit).isEqualTo(ExitCode.ERROR);
        assertThat(errCapture.toString(StandardCharsets.UTF_8)).contains("Error: Connection failed: Connection refused");
        assertThat(outCapture.toString(StandardCharsets.UTF_8)).as("no header over an empty table").isEmpty();
    }

    @Test
    void printQuery_errorEnvelope_csvFormat_exitsError_andDrawsNoHeader() {
        var exit = OutputFormatter.printQuery(ENVELOPE, parseOptions("--format", "csv"), NODES_TABLE);

        assertThat(exit).isEqualTo(ExitCode.ERROR);
        assertThat(errCapture.toString(StandardCharsets.UTF_8)).contains("Error: Connection failed: Connection refused");
        assertThat(outCapture.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void printQuery_errorEnvelope_jsonFormat_exitsError_withEnvelopeOnStderr() {
        var exit = OutputFormatter.printQuery(ENVELOPE, parseOptions("--format", "json"));

        assertThat(exit).isEqualTo(ExitCode.ERROR);
        assertThat(errCapture.toString(StandardCharsets.UTF_8)).contains("{\"error\":\"Connection failed: Connection refused\"}");
        assertThat(outCapture.toString(StandardCharsets.UTF_8)).as("an error envelope is not a result document")
                  .isEmpty();
    }

    @Test
    void printQuery_errorEnvelope_quiet_stillExitsError() {
        var exit = OutputFormatter.printQuery(ENVELOPE, parseOptions("--quiet"), NODES_TABLE);

        assertThat(exit).as("--quiet suppresses output, never the exit code").isEqualTo(ExitCode.ERROR);
        assertThat(outCapture.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    /// A 404 ProblemDetail (RFC 9457) routes to `NOT_FOUND`, as `checkResponseError` already does
    /// for the commands that call it — the two paths must agree on the exit code.
    @Test
    void printQuery_notFoundProblemDetail_exitsNotFound() {
        var problem = "{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404,\"detail\":\"no such slice\"}";
        var exit = OutputFormatter.printQuery(problem, parseOptions("--format", "table"), NODES_TABLE);

        assertThat(exit).isEqualTo(ExitCode.NOT_FOUND);
        assertThat(errCapture.toString(StandardCharsets.UTF_8)).contains("Error: no such slice");
        assertThat(outCapture.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    /// The control: a genuine document with zero rows still renders its header and exits 0 — the
    /// fix distinguishes "could not ask" from "asked, and there are none".
    @Test
    void printQuery_emptyNodesDocument_rendersHeader_andExitsSuccess() {
        var exit = OutputFormatter.printQuery("{\"nodes\":[],\"liveCount\":0,\"zombieCount\":0}",
                                              parseOptions("--format", "table"),
                                              NODES_TABLE);

        assertThat(exit).isEqualTo(ExitCode.SUCCESS);
        assertThat(outCapture.toString(StandardCharsets.UTF_8)).contains("NODE ID");
        assertThat(errCapture.toString(StandardCharsets.UTF_8)).isEmpty();
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
