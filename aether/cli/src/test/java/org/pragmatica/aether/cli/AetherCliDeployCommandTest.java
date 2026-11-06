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

/// v2713 round 3 (R3d): what `aether deploy` sends, observed through the real entrypoint in a child JVM against a recording
/// listener, and the help text of the two instance options. Kills new_cli c01-c07 (the request body: the `.or("")` default, the
/// `instances` field, the absent default, the trailing comma, the per-strategy prefix) and new_clidoc c10, c11 (help and refusal text).
class AetherCliDeployCommandTest {
    private static final String BLUEPRINT = "org.example:app:1.0.0";
    private static final String THRESHOLDS = "\"thresholds\":{\"maxErrorRate\":0.01,\"maxLatencyMs\":500}";
    private static final String CANARY = "\"canary\":{\"stages\":[{\"trafficPercent\":5,\"observationMinutes\":10}]},";
    private static final String BLUE_GREEN = "\"blueGreen\":{\"drainTimeoutMs\":30000},";
    private static final String ROLLING = "\"rolling\":{\"requireManualApproval\":false},";

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

    private int runDeploy(String... deployArgs) throws IOException, InterruptedException {
        var command = new ArrayList<String>();

        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Duser.home=" + home);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(AetherCli.class.getName());
        command.add("--connect");
        command.add("http://127.0.0.1:" + server.getAddress().getPort());
        command.add("deploy");
        command.addAll(List.of(deployArgs));
        var output = Files.createTempFile(home, "deploy-", ".out");
        var process = new ProcessBuilder(command).redirectErrorStream(true)
                                                 .redirectOutput(output.toFile())
                                                 .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                                                 .start();

        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("CLI did not exit within 60 s; output:\n" + Files.readString(output));
        }

        return process.exitValue();
    }

    private void assertSentBody(String expected, String... deployArgs) throws Exception {
        assertThat(runDeploy(deployArgs)).isZero();
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst()).startsWith("POST ");
        assertThat(requests.getFirst().substring(requests.getFirst().indexOf(" {") + 1)).isEqualTo(expected);
    }

    private static String strategyBody(String strategy, String instancesField, String strategyPart) {
        return "{\"blueprint\":\"" + BLUEPRINT + "\",\"strategy\":\"" + strategy + "\"," + instancesField + strategyPart + THRESHOLDS + "}";
    }

    @Test
    void immediate_sendsOnlyTheArtifact() throws Exception {
        assertSentBody("{\"artifact\":\"" + BLUEPRINT + "\"}", BLUEPRINT);
    }

    @Test
    void canary_withoutInstances_sendsNoInstancesField() throws Exception {
        assertSentBody(strategyBody("CANARY", "", CANARY), BLUEPRINT, "--canary");
    }

    @Test
    void canary_withInstances_sendsTheCountWithATrailingComma() throws Exception {
        assertSentBody(strategyBody("CANARY", "\"instances\":4,", CANARY), BLUEPRINT, "--canary", "-n", "4");
    }

    @Test
    void blueGreen_withoutInstances_sendsNoInstancesField() throws Exception {
        assertSentBody(strategyBody("BLUE_GREEN", "", BLUE_GREEN), BLUEPRINT, "--blue-green");
    }

    @Test
    void blueGreen_withInstances_sendsTheCountWithATrailingComma() throws Exception {
        assertSentBody(strategyBody("BLUE_GREEN", "\"instances\":4,", BLUE_GREEN), BLUEPRINT, "--blue-green", "--instances", "4");
    }

    @Test
    void rolling_withoutInstances_sendsNoInstancesField() throws Exception {
        assertSentBody(strategyBody("ROLLING", "", ROLLING), BLUEPRINT, "--rolling");
    }

    @Test
    void rolling_withInstances_sendsTheCountWithATrailingComma() throws Exception {
        assertSentBody(strategyBody("ROLLING", "\"instances\":4,", ROLLING), BLUEPRINT, "--rolling", "-n", "4");
    }

    @Test
    void instancesOption_hasNoDefault() {
        var deploy = new CommandLine(new AetherCli()).getSubcommands().get("deploy").getCommandSpec();

        assertThat(deploy.findOption("--instances").defaultValue()).isNull();
        assertThat(deploy.findOption("--instances").getValue()).isNull();
    }

    /// c11: the deploy `--instances` text says it is refused and names the command to use instead.
    @Test
    void deployInstancesOption_helpTextNamesTheRefusalAndTheAlternative() {
        var deploy = new CommandLine(new AetherCli()).getSubcommands().get("deploy").getCommandSpec();

        assertThat(String.join(" ", deploy.findOption("--instances").description()))
            .isEqualTo("Refused: a deployment keeps the slice's instance count; use 'aether scale -n'");
    }

    /// c10: the scale `--release` text says the slice keeps its count until the autoscaler's requirement falls.
    @Test
    void scaleReleaseOption_helpTextStatesTheCountIsKept() {
        var scale = new CommandLine(new AetherCli()).getSubcommands().get("scale").getCommandSpec();

        assertThat(String.join(" ", scale.findOption("--release").description()))
            .isEqualTo("Remove the operator instance requirement; the slice keeps its count until the autoscaler's requirement falls");
    }
}
