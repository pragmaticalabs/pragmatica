// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.cli.AetherCli;
import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.lang.Result;

import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;


/// #241: `aether cluster clear-placement <community> --reason <text>` and its exit codes: 0 cleared, 3 no such operation (the server's 404), 1 any other
/// refusal (not parked, not the leader) or failure, 64 a missing or blank `--reason`. The request seam is replaced so the exit code is the command's,
/// not a socket's; the route and body shape are pinned by the node's route test.
class ClusterClearPlacementCommandTest {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final List<String> requests = new ArrayList<>();
    private PrintStream originalOut;
    private PrintStream originalErr;
    private BiFunction<String, String, Result<String>> originalRequester;

    @BeforeEach
    void capture() {
        originalOut = System.out;
        originalErr = System.err;
        originalRequester = ClusterClearPlacementCommand.requester;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restore() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        ClusterClearPlacementCommand.requester = originalRequester;
    }

    private int run(Result<String> answer, String... args) {
        ClusterClearPlacementCommand.requester = (node, body) -> {
            requests.add(node + "|" + body);

            return answer;
        };

        var all = new ArrayList<String>(List.of("cluster", "clear-placement"));

        all.addAll(List.of(args));

        return new CommandLine(new AetherCli()).execute(all.toArray(String[]::new));
    }

    @Test
    void cleared_exitsZero_andSendsTheCommunityAndTheReason() {
        var code = run(Result.success("{}"), "eu-w-1", "--reason", " target is up, previous node is gone ");

        assertThat(code).isEqualTo(ExitCode.SUCCESS);
        assertThat(requests).containsExactly("eu-w-1|{\"reason\":\"target is up, previous node is gone\"}");
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("eu-w-1").contains("previous node is gone");
    }

    @Test
    void noSuchOperation_exitsNotFound() {
        var code = run(new ClusterHttpClient.HttpError.ApiError(404, "no operation").result(), "eu-w-1", "--reason", "checked");

        assertThat(code).isEqualTo(ExitCode.NOT_FOUND);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("no operation");
    }

    @Test
    void aNotParkedRefusal_exitsError_notNotFound() {
        var code = run(new ClusterHttpClient.HttpError.ApiError(409, "not parked").result(), "eu-w-1", "--reason", "checked");

        assertThat(code).isEqualTo(ExitCode.ERROR);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("not parked");
    }

    @Test
    void aTransportFailure_exitsError() {
        var code = run(new ClusterHttpClient.HttpError.SimpleError("connection refused").result(), "eu-w-1", "--reason", "checked");

        assertThat(code).isEqualTo(ExitCode.ERROR);
    }

    @Test
    void aBlankReason_exitsUsage_andSendsNothing() {
        var code = run(Result.success("{}"), "eu-w-1", "--reason", "   ");

        assertThat(code).isEqualTo(ExitCode.USAGE);
        assertThat(requests).isEmpty();
    }

    /// A missing `--reason` is the same usage error as a blank one (64), not picocli's 2.
    @Test
    void aMissingReason_exitsUsage_andSendsNothing() {
        var code = run(Result.success("{}"), "eu-w-1");

        assertThat(code).isEqualTo(ExitCode.USAGE);
        assertThat(requests).isEmpty();
    }

    /// The literal the node's `CommunityPlacementRoutesTest#theRequestBodyKey_isReason` parses into `ClearRequest(reason)`.
    @Test
    void body_isTheJsonThisRouteReads() {
        assertThat(ClusterClearPlacementCommand.body("checked").unwrap()).isEqualTo("{\"reason\":\"checked\"}");
    }
}
