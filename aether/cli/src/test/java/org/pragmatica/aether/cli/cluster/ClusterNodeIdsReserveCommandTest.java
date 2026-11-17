// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.cli.AetherCli;
import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.json.JsonMapper;

import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #1715: `aether cluster node-ids reserve`. CLI half of the `POST /api/cluster/node-ids/reserve` contract;
/// `NodeIdRoutesTest` in `aether/node` is the server half, over `ReserveNodeIdsRequest`.
class ClusterNodeIdsReserveCommandTest {
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();
    private static final String ANSWER = "{\"source\":\"s\",\"role\":\"core\",\"ids\":[\"s-core-1\",\"s-core-2\"],\"highWater\":2}";

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final AtomicReference<String> requestLine = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String answer = ANSWER;
    private HttpServer server;
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestLine.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            var bytes = answer.getBytes(StandardCharsets.UTF_8);

            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        ClusterHttpClient.setEndpointOverride("http://127.0.0.1:" + server.getAddress().getPort());
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void stop() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        server.stop(0);
        ClusterHttpClient.setEndpointOverride("");
    }

    private int run(String... args) {
        var all = new ArrayList<>(List.of("cluster", "node-ids"));

        all.addAll(List.of(args));

        return new CommandLine(new AetherCli()).execute(all.toArray(String[]::new));
    }

    @Test
    void reserve_postsTheRequestToTheReserveRoute_andPrintsTheReservedIds() {
        var code = run("reserve", "--source", "s", "--role", "worker", "--count", "2");

        assertThat(code).isEqualTo(ExitCode.SUCCESS);
        assertThat(requestLine.get()).isEqualTo("POST /api/v1/cluster/node-ids/reserve");
        assertThat(requestBody.get()).isEqualTo("{\"source\":\"s\",\"role\":\"worker\",\"count\":2}");
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Reserved: s-core-1, s-core-2");
    }

    @Test
    void reserve_defaultsTheRoleToCore() {
        run("reserve", "--source", "s", "--count", "1");

        assertThat(requestBody.get()).isEqualTo("{\"source\":\"s\",\"role\":\"core\",\"count\":1}");
    }

    @Test
    void reserve_refusedByTheLeader_exitsError_andPrintsTheCause() {
        status = 409;
        answer = "{\"error\":{\"message\":\"Current core leader required to reserve node ids\"}}";

        var code = run("reserve", "--source", "s", "--count", "1");

        assertThat(code).isEqualTo(ExitCode.ERROR);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("Current core leader required");
    }

    @Test
    void reserve_refusesAMissingCount_withTheUsageExitCode() {
        var code = run("reserve", "--source", "s");

        assertThat(code).isEqualTo(CommandLine.ExitCode.USAGE);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("--count");
        assertThat(requestLine.get()).isNull();
    }

    @Test
    void nodeIds_withoutASubcommand_printsItsUsage() {
        run();

        assertThat(out.toString(StandardCharsets.UTF_8)).contains("reserve");
    }

    @Test
    void buildReserveJson_emitsExactlyTheFieldNamesReserveNodeIdsRequestReads() {
        MAPPER.readTree(ClusterNodeIdsReserveCommand.buildReserveJson("eu-central", "worker", 3))
              .onSuccess(node -> {
                  assertThat(node.path("source").asText()).isEqualTo("eu-central");
                  assertThat(node.path("role").asText()).isEqualTo("worker");
                  assertThat(node.path("count").asInt()).isEqualTo(3);
                  assertThat(node.size()).isEqualTo(3);
              })
              .onFailure(cause -> fail(cause.message()));
    }

    /// A quote in a source name must stay inside its string: the body is data, never a way to add fields.
    @Test
    void buildReserveJson_escapesTheNames() {
        MAPPER.readTree(ClusterNodeIdsReserveCommand.buildReserveJson("a\",\"count\":99,\"x\":\"", "core", 1))
              .onSuccess(node -> {
                  assertThat(node.path("count").asInt()).isEqualTo(1);
                  assertThat(node.path("source").asText()).isEqualTo("a\",\"count\":99,\"x\":\"");
              })
              .onFailure(cause -> fail(cause.message()));
    }

    @Test
    void successMessage_listsTheReservedIds() {
        var json = "{\"source\":\"s\",\"role\":\"core\",\"ids\":[\"s-core-1\",\"s-core-2\"],\"highWater\":2}";

        assertThat(ClusterNodeIdsReserveCommand.successMessage(json)).isEqualTo("Reserved: s-core-1, s-core-2");
    }

    @Test
    void successMessage_fallsBack_whenTheAnswerIsNotJson() {
        assertThat(ClusterNodeIdsReserveCommand.successMessage("not json")).isEqualTo("Node ids reserved.");
    }

    @Test
    void reserve_isRegisteredUnderClusterNodeIds() {
        var cluster = new CommandLine(new AetherCli()).getSubcommands().get("cluster");

        assertThat(cluster.getSubcommands()).containsKey("node-ids");
        assertThat(cluster.getSubcommands().get("node-ids").getSubcommands()).containsKey("reserve");
    }

    @Test
    void reserve_refusesAMissingSource_withTheUsageExitCode() {
        var code = run("reserve", "--count", "2");

        assertThat(code).isEqualTo(CommandLine.ExitCode.USAGE);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("--source");
        assertThat(requestLine.get()).isNull();
    }
}
