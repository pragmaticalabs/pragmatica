// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// rc6 §3.1 `aether scale --release` / `--instances`: what the command sends and how it exits, observed through the
/// real entrypoint in a child JVM against a recording listener (the fake HTTP side). Pins the request body of each
/// option, that `--release` never waits, the action message, the exactly-one rule with a non-zero exit, and that
/// `--instances` has no default (a default would make a bare `--release` look like "both given").
class AetherCliScaleCommandTest {
    private static final String ARTIFACT = "org.example:echo:1.0.0";

    @TempDir
    Path home;

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void listener() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/",
                             exchange -> {
                                 var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

                                 requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                                              + " " + body);
                                 var reply = "{}".getBytes(StandardCharsets.UTF_8);

                                 exchange.sendResponseHeaders(200, reply.length);
                                 exchange.getResponseBody().write(reply);
                                 exchange.close();
                             });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private record Run(int exit, String output) {}

    private Run runScale(String... scaleArgs) throws IOException, InterruptedException {
        var command = new ArrayList<String>();

        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Duser.home=" + home);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(AetherCli.class.getName());
        command.add("--connect");
        command.add("http://127.0.0.1:" + server.getAddress().getPort());
        command.add("scale");
        command.addAll(List.of(scaleArgs));
        var output = Files.createTempFile(home, "scale-", ".out");
        var process = new ProcessBuilder(command).redirectErrorStream(true)
                                                 .redirectOutput(output.toFile())
                                                 .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                                                 .start();

        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("CLI did not exit within 60 s; output:\n" + Files.readString(output));
        }

        return new Run(process.exitValue(), Files.readString(output));
    }

    @Test
    void release_sendsReleaseTrueAndNoInstances_andDoesNotWait() throws Exception {
        var run = runScale(ARTIFACT, "--release", "--wait", "--timeout", "30");

        assertThat(run.exit()).isZero();
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst()).startsWith("POST ").endsWith("{\"artifact\":\"" + ARTIFACT + "\",\"release\":true}");
        assertThat(run.output()).doesNotContain("Waiting for");
    }

    @Test
    void release_printsTheReleaseActionMessage() throws Exception {
        var run = runScale(ARTIFACT, "--release");

        assertThat(run.output()).contains("Released the operator instance requirement of " + ARTIFACT);
    }

    @Test
    void instances_sendsInstancesAndNoRelease() throws Exception {
        var run = runScale(ARTIFACT, "--instances", "5");

        assertThat(run.exit()).isZero();
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst()).endsWith("{\"artifact\":\"" + ARTIFACT + "\",\"instances\":5}");
        assertThat(requests.getFirst()).doesNotContain("release");
        assertThat(run.output()).contains("Set the operator instance requirement of " + ARTIFACT + " to 5");
    }

    @Test
    void bothGiven_exitsNonZero_withTheMessage_andSendsNothing() throws Exception {
        var run = runScale(ARTIFACT, "--instances", "5", "--release");

        assertThat(run.exit()).isEqualTo(ExitCode.ERROR);
        assertThat(run.output()).contains("Specify exactly one of --instances <n> or --release");
        assertThat(requests).isEmpty();
    }

    @Test
    void neitherGiven_exitsNonZero_withTheMessage_andSendsNothing() throws Exception {
        var run = runScale(ARTIFACT);

        assertThat(run.exit()).isEqualTo(ExitCode.ERROR);
        assertThat(run.output()).contains("Specify exactly one of --instances <n> or --release");
        assertThat(requests).isEmpty();
    }

    @Test
    void instancesOption_hasNoDefault_soItIsOptionalAlongsideRelease() {
        var spec = new CommandLine(new AetherCli.ScaleCommand()).getCommandSpec();

        assertThat(spec.findOption("--instances").defaultValue()).isNull();
        assertThat(spec.findOption("--instances").required()).isFalse();
        assertThat(spec.findOption("--release").required()).isFalse();
    }
}
