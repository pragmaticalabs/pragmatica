// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #1033: `aether nodes live` against a cluster the CLI cannot reach printed a table header, no
/// rows, and exited 0 — a transport failure rendered as "no nodes". `AetherCli.fetch` folds the
/// failed send into a `{"error":"…"}` envelope, and `OutputFormatter.printQuery` in table mode
/// navigated that envelope to a missing `nodes` array and drew an empty table over it.
///
/// Pinned through the REAL entrypoint (a child JVM running `AetherCli.main`, as
/// [AetherCliEndpointPrecedenceTest] does) because the exit code is what the operator's script
/// sees, and `main` reaches it through `System.exit`. stdout and stderr are captured separately:
/// the claim is "the cause is on stderr and the table is NOT on stdout", which a merged stream
/// cannot distinguish. The endpoint is a loopback port that was bound and released just before
/// the run, so the connection is refused at once rather than timing out.
///
/// The sibling list commands share the one producer (`fetch`) and the one consumer
/// (`printQuery`), so they are pinned here too: one shape, one fix.
///
/// Round 2 (review of #1410): the ACTION commands had the inverse defect — `printAction` printed
/// its success line and exited 0 over the same envelope, so `scale foo -n 3` against a refused
/// port said `Scaled foo to 3 instances`. And `formatErrorResponse` passed any non-2xx body that
/// starts with `{` through as a document, so a `500 {"message":"boom"}` (a gateway shape, not
/// Aether's) drew the empty table after the round-1 fix. Both are pinned below against a scripted
/// server answering a 404 ProblemDetail or that 500, and against the refused port.
@SuppressWarnings("JBCT-EX-01")
class UnreachableClusterExitTest {
    private static final String PROBLEM_404 = "{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404,\"detail\":\"no such thing\"}";

    private static final String GATEWAY_500 = "{\"message\":\"boom\"}";

    @TempDir
    Path home;

    private String unreachable;
    private HttpServer scripted;

    @BeforeEach
    void releasedLoopbackPort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            unreachable = "127.0.0.1:" + socket.getLocalPort();
        }
    }

    @AfterEach
    void stopScripted() {
        if (scripted != null) {
            scripted.stop(0);
        }
    }

    /// A server that answers every request with one fixed status and body — the shapes a
    /// management endpoint, or something in front of it, can hand the CLI.
    private String scripted(int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);

        scripted = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        scripted.createContext("/",
                               exchange -> {
                                   exchange.getResponseHeaders()
                                           .add("Content-Type", "application/json");
                                   exchange.sendResponseHeaders(status, bytes.length);
                                   exchange.getResponseBody()
                                           .write(bytes);
                                   exchange.close();
                               });
        scripted.start();

        return "127.0.0.1:" + scripted.getAddress()
                                      .getPort();
    }

    @Test
    void nodesLive_unreachable_exitsNonZero_withCauseOnStderr_andNoTable() throws Exception {
        assertRefusedNotEmpty(run("nodes", "live"), "NODE ID");
    }

    /// `--only-alive` runs the response through `LiveNodesFilter.onlyAlive`, which rebuilt an
    /// error envelope into `{"nodes":[],…}` — erasing the error before the formatter saw it.
    @Test
    void nodesLiveOnlyAlive_unreachable_exitsNonZero_withCauseOnStderr_andNoTable() throws Exception {
        assertRefusedNotEmpty(run("nodes", "live", "--only-alive"), "NODE ID");
    }

    @Test
    void nodesLiveJson_unreachable_exitsNonZero_andNothingOnStdout() throws Exception {
        var run = run("--format", "json", "nodes", "live");

        assertThat(run.exit).as("exit code; " + run.streams()).isNotEqualTo(ExitCode.SUCCESS);
        assertThat(run.err).as("stderr carries the structured error").contains("{\"error\":");
        assertThat(run.out).as("an error envelope is not a result document").isEmpty();
    }

    @Test
    void nodesList_unreachable_exitsNonZero_withCauseOnStderr() throws Exception {
        assertRefusedNotEmpty(run("nodes"), "{");
    }

    @Test
    void slicesList_unreachable_exitsNonZero_withCauseOnStderr() throws Exception {
        assertRefusedNotEmpty(run("slices"), "{");
    }

    @Test
    void status_unreachable_exitsNonZero_withCauseOnStderr() throws Exception {
        assertRefusedNotEmpty(run("status"), "{");
    }

    /// MEDIUM-2: a non-2xx whose body is JSON but neither `{"error":…}` nor a ProblemDetail used to
    /// pass through `formatErrorResponse` as a document — the ticket's symptom, after round 1.
    @Test
    void nodesLive_gateway500_exitsNonZero_withCauseOnStderr_andNoTable() throws Exception {
        var run = runAgainst(scripted(500, GATEWAY_500), "nodes", "live");

        assertThat(run.exit).as("exit code; " + run.streams()).isNotEqualTo(ExitCode.SUCCESS);
        assertThat(run.err).contains("Error:").contains("HTTP 500");
        assertThat(run.out).doesNotContain("NODE ID");
    }

    @Test
    void scale_refused_exitsNonZero_andNeverClaimsSuccess() throws Exception {
        assertRefusedNoSuccess(runAgainst(unreachable, "scale", "com.example:foo", "-n", "3"), "Scaled");
    }

    @Test
    void scale_404Problem_exitsNotFound_andNeverClaimsSuccess() throws Exception {
        var run = runAgainst(scripted(404, PROBLEM_404), "scale", "com.example:foo", "-n", "3");

        assertThat(run.exit).as("exit code; " + run.streams()).isEqualTo(ExitCode.NOT_FOUND);
        assertThat(run.err).contains("Error:").contains("no such thing");
        assertThat(run.out).doesNotContain("Scaled");
    }

    @Test
    void scale_gateway500_exitsNonZero_andNeverClaimsSuccess() throws Exception {
        assertServerErrorNoSuccess(runAgainst(scripted(500, GATEWAY_500), "scale", "com.example:foo", "-n", "3"),
                                   "Scaled");
    }

    @Test
    void scaleJson_refused_exitsNonZero_andNothingOnStdout() throws Exception {
        var run = runAgainst(unreachable, "--format", "json", "scale", "com.example:foo", "-n", "3");

        assertThat(run.exit).as("exit code; " + run.streams()).isNotEqualTo(ExitCode.SUCCESS);
        assertThat(run.err).contains("{\"error\":");
        assertThat(run.out).isEmpty();
    }

    @Test
    void scaleQuiet_refused_stillExitsNonZero() throws Exception {
        var run = runAgainst(unreachable, "--quiet", "scale", "com.example:foo", "-n", "3");

        assertThat(run.exit).as("--quiet suppresses output, never the exit code; " + run.streams())
                  .isNotEqualTo(ExitCode.SUCCESS);
        assertThat(run.out).isEmpty();
    }

    /// `scale --wait` used to print the false success line, then poll `unknown / N` to the
    /// deadline and exit 2. The refusal must come BEFORE the wait: no success line, no
    /// `Waiting for` line, and well inside the timeout.
    @Test
    void scaleWait_refused_exitsNonZero_beforeAnyPolling() throws Exception {
        var started = System.nanoTime();
        var run = runAgainst(unreachable, "scale", "com.example:foo", "-n", "3", "--wait", "--timeout", "30");
        var elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000L;

        assertRefusedNoSuccess(run, "Scaled");
        assertThat(run.out).as("the wait never starts").doesNotContain("Waiting for");
        assertThat(elapsedSeconds).as("refused at once, not at the 30 s deadline").isLessThan(15);
    }

    @Test
    void configSet_refused_exitsNonZero_andNeverClaimsSuccess() throws Exception {
        assertRefusedNoSuccess(runAgainst(unreachable, "config", "set", "a.b", "1"), "Config set");
    }

    @Test
    void configSet_404Problem_exitsNotFound_andNeverClaimsSuccess() throws Exception {
        var run = runAgainst(scripted(404, PROBLEM_404), "config", "set", "a.b", "1");

        assertThat(run.exit).as("exit code; " + run.streams()).isEqualTo(ExitCode.NOT_FOUND);
        assertThat(run.out).doesNotContain("Config set");
    }

    @Test
    void configSet_gateway500_exitsNonZero_andNeverClaimsSuccess() throws Exception {
        assertServerErrorNoSuccess(runAgainst(scripted(500, GATEWAY_500), "config", "set", "a.b", "1"), "Config set");
    }

    @Test
    void scheduledTasksTrigger_refused_exitsNonZero_andNeverClaimsSuccess() throws Exception {
        assertRefusedNoSuccess(runAgainst(unreachable,
                                          "scheduled-tasks",
                                          "trigger",
                                          "jobs",
                                          "com.example:foo:1.0",
                                          "run"),
                               "Task triggered");
    }

    @Test
    void scheduledTasksTrigger_404Problem_exitsNotFound_andNeverClaimsSuccess() throws Exception {
        var run = runAgainst(scripted(404, PROBLEM_404),
                             "scheduled-tasks",
                             "trigger",
                             "jobs",
                             "com.example:foo:1.0",
                             "run");

        assertThat(run.exit).as("exit code; " + run.streams()).isEqualTo(ExitCode.NOT_FOUND);
        assertThat(run.out).doesNotContain("Task triggered");
    }

    @Test
    void scheduledTasksTrigger_gateway500_exitsNonZero_andNeverClaimsSuccess() throws Exception {
        assertServerErrorNoSuccess(runAgainst(scripted(500, GATEWAY_500),
                                              "scheduled-tasks",
                                              "trigger",
                                              "jobs",
                                              "com.example:foo:1.0",
                                              "run"),
                                   "Task triggered");
    }

    /// The control for the action path: a 2xx still prints the success line and exits 0.
    @Test
    void scale_200_printsSuccessLine_andExitsSuccess() throws Exception {
        var run = runAgainst(scripted(200, "{\"status\":\"ok\"}"), "scale", "com.example:foo", "-n", "3");

        assertThat(run.exit).as("exit code; " + run.streams()).isEqualTo(ExitCode.SUCCESS);
        assertThat(run.out).contains("Scaled com.example:foo to 3 instances");
        assertThat(run.err).isEmpty();
    }

    private static void assertRefusedNoSuccess(Run run, String successMarker) {
        assertThat(run.exit).as("exit code — an operation that never ran is not a success; " + run.streams())
                  .isNotEqualTo(ExitCode.SUCCESS);
        assertThat(run.err).as("stderr names the transport failure").contains("Error:").containsIgnoringCase("connect");
        assertThat(run.out).as("no success line for a request that never ran").doesNotContain(successMarker);
    }

    private static void assertServerErrorNoSuccess(Run run, String successMarker) {
        assertThat(run.exit).as("exit code; " + run.streams()).isNotEqualTo(ExitCode.SUCCESS);
        assertThat(run.err).as("stderr names the status").contains("Error:").contains("HTTP 500");
        assertThat(run.out).doesNotContain(successMarker);
    }

    private static void assertRefusedNotEmpty(Run run, String resultMarker) {
        assertThat(run.exit).as("exit code — a cluster that could not be asked is not an empty cluster; " + run.streams())
                  .isNotEqualTo(ExitCode.SUCCESS);
        assertThat(run.err).as("stderr names the transport failure").contains("Error:").containsIgnoringCase("connect");
        assertThat(run.out).as("stdout must not carry a result document for a request that never ran")
                  .doesNotContain(resultMarker);
    }

    private record Run(int exit, String out, String err) {
        String streams() {
            return "stdout=<" + out + "> stderr=<" + err + ">";
        }
    }

    /// Runs the real entrypoint in a child JVM: `main` calls `System.exit`, and the registry path is a
    /// static read of `user.home`, so neither can be driven in-process. stdout and stderr go to
    /// separate files and stdin comes from /dev/null so `waitFor` is the first blocking call.
    private Run run(String... args) throws IOException, InterruptedException {
        return runAgainst(unreachable, args);
    }

    private Run runAgainst(String target, String... args) throws IOException, InterruptedException {
        var command = new ArrayList<String>();

        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Duser.home=" + home);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(AetherCli.class.getName());
        command.add("--connect");
        command.add(target);
        command.addAll(List.of(args));
        var out = Files.createTempFile(home, "cli-", ".out");
        var err = Files.createTempFile(home, "cli-", ".err");
        var builder = new ProcessBuilder(command).redirectOutput(out.toFile())
                                                 .redirectError(err.toFile())
                                                 .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));

        builder.environment().remove("AETHER_API_KEY");
        var process = builder.start();

        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("CLI did not exit within 60 s; stdout so far:\n" + Files.readString(out)
                + "\nstderr so far:\n" + Files.readString(err));
        }

        return new Run(process.exitValue(), Files.readString(out), Files.readString(err));
    }
}
