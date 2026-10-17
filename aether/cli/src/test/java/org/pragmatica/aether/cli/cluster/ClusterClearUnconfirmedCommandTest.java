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


/// #2062: `aether cluster clear-unconfirmed <node> --instance <id|unknown>` and its exit codes: 0 cleared, 3 no such mark (the server's 404), 1 any other
/// refusal (a live node, or an instance the provider still lists, is the server's 409) or failure, 64 a missing or blank `--instance` (`ExitCode.USAGE`, as
/// `cluster bootstrap` and `cluster destroy` return for their own argument checks). The request seam is replaced so the exit code is the
/// command's, not a socket's; the route and body shape are pinned by the node's route test.
class ClusterClearUnconfirmedCommandTest {
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
        originalRequester = ClusterClearUnconfirmedCommand.requester;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restore() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        ClusterClearUnconfirmedCommand.requester = originalRequester;
    }

    private int run(Result<String> answer, String... args) {
        ClusterClearUnconfirmedCommand.requester = (node, body) -> {
            requests.add(node + "|" + body);

            return answer;
        };

        var all = new ArrayList<String>(List.of("cluster", "clear-unconfirmed"));

        all.addAll(List.of(args));

        return new CommandLine(new AetherCli()).execute(all.toArray(String[]::new));
    }

    @Test
    void cleared_exitsZero_andSendsTheNodeAndTheInstance() {
        var code = run(Result.success("{}"), "core-old", "--instance", " i-42 ");

        assertThat(code).isEqualTo(ExitCode.SUCCESS);
        assertThat(requests).containsExactly("core-old|{\"instance\":\"i-42\",\"force\":false}");
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("core-old").contains("i-42");
    }

    @Test
    void noSuchMark_exitsNotFound() {
        var code = run(new ClusterHttpClient.HttpError.ApiError(404, "no mark").result(), "core-old", "--instance", "unknown");

        assertThat(code).isEqualTo(ExitCode.NOT_FOUND);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("no mark");
    }

    @Test
    void aLiveNodeRefusal_exitsError_notNotFound() {
        var code = run(new ClusterHttpClient.HttpError.ApiError(409, "node shows life").result(), "core-old", "--instance", "i-1");

        assertThat(code).isEqualTo(ExitCode.ERROR);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("node shows life");
    }

    @Test
    void aTransportFailure_exitsError() {
        var code = run(new ClusterHttpClient.HttpError.SimpleError("connection refused").result(), "core-old", "--instance", "i-1");

        assertThat(code).isEqualTo(ExitCode.ERROR);
    }

    @Test
    void aBlankInstance_exitsUsage_andSendsNothing() {
        var code = run(Result.success("{}"), "core-old", "--instance", "   ");

        assertThat(code).isEqualTo(ExitCode.USAGE);
        assertThat(requests).isEmpty();
    }

    /// A missing `--instance` is the same usage error as a blank one (64, the convention of `cluster bootstrap` / `cluster destroy`), not picocli's 2.
    @Test
    void aMissingInstance_exitsUsage_andSendsNothing() {
        var code = run(Result.success("{}"), "core-old");

        assertThat(code).isEqualTo(ExitCode.USAGE);
        assertThat(requests).isEmpty();
    }

    @Test
    void force_isSentInTheBody() {
        var code = run(Result.success("{}"), "core-old", "--instance", "unknown", "--force");

        assertThat(code).isEqualTo(ExitCode.SUCCESS);
        assertThat(requests).containsExactly("core-old|{\"instance\":\"unknown\",\"force\":true}");
    }

    /// The literals the node's `UnconfirmedMarkRoutesTest#theRequestBodyKeys_areInstanceAndForce` parses into `ClearRequest(instance, force)`.
    @Test
    void body_isTheJsonThisRouteReads() {
        assertThat(ClusterClearUnconfirmedCommand.body("i-42", true).unwrap()).isEqualTo("{\"instance\":\"i-42\",\"force\":true}");
        assertThat(ClusterClearUnconfirmedCommand.body("i-42", false).unwrap()).isEqualTo("{\"instance\":\"i-42\",\"force\":false}");
    }
}
