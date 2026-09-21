// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
@SuppressWarnings("JBCT-EX-01")
class UnreachableClusterExitTest {
    @TempDir
    Path home;

    private String unreachable;

    @BeforeEach
    void releasedLoopbackPort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            unreachable = "127.0.0.1:" + socket.getLocalPort();
        }
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
        var command = new ArrayList<String>();

        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Duser.home=" + home);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(AetherCli.class.getName());
        command.add("--connect");
        command.add(unreachable);
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
