// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/// #1543 F2: a request to upgrade to the version the cluster config already stores is "already at version" only when no run towards it is owed.
/// A live (RUNNING or PAUSED) run, or one the server still owes, makes the request go to the server, which attaches to it. Without the guard a
/// re-issued upgrade during the run would answer "already at version" and `--wait` would never attach.
@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ClusterUpgradeRunOwedTest {
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    private HttpServer server;
    private volatile String statusBody = "{\"present\":false}";
    private final AtomicInteger posts = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/upgrade/status", exchange -> reply(exchange, statusBody));
        server.createContext("/api/v1/cluster/upgrade", exchange -> {
            posts.incrementAndGet();
            reply(exchange, "{\"started\":true}");
        });
        server.start();
        ClusterHttpClient.setEndpointOverride("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
        ClusterHttpClient.setEndpointOverride("");
    }

    private static void reply(HttpExchange exchange, String body) throws java.io.IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private ClusterUpgradeCommand command() {
        var command = new ClusterUpgradeCommand();

        new picocli.CommandLine(command).parseArgs("--version", "2.0.0");

        return command;
    }

    private static com.fasterxml.jackson.databind.JsonNode config() {
        return MAPPER.readTree("{\"version\":\"2.0.0\",\"configVersion\":7}").unwrap();
    }

    @Test
    void sameVersion_withALiveRun_goesToTheServer_insteadOfAlreadyAtVersion() {
        for (var state : java.util.List.of("RUNNING", "PAUSED")) {
            statusBody = "{\"present\":true,\"state\":\"" + state + "\"}";
            var before = posts.get();
            var result = command().initiateUpgrade(config());

            assertThat(result.isSuccess()).as(state + ": " + result).isTrue();
            assertThat(posts.get()).as(state).isEqualTo(before + 1);
        }
    }

    /// Control: with no run owed the same request is refused as already at the version and nothing is posted.
    @Test
    void sameVersion_withNoRunOwed_isAlreadyAtVersion_andPostsNothing() {
        for (var body : java.util.List.of("{\"present\":false}", "{\"present\":true,\"state\":\"COMPLETED\"}")) {
            statusBody = body;
            var result = command().initiateUpgrade(config());

            assertThat(result.isFailure()).as(body).isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("2.0.0"));
        }
        assertThat(posts.get()).isZero();
    }
}
