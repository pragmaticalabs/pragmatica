// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1543 F2: the REAL `--wait` wiring (endpoint in force -> status and topology fetches -> failover) against a management server whose
/// two names stand for two nodes: `127.0.0.1` is the node the CLI was pointed at and which is replaced after the first answer,
/// `localhost` is a live member the topology lists. The same port on both is what a cluster's uniform management port looks like.
class ClusterUpgradeWaitFailoverTest {
    private static final String RUNNING = "{\"present\":true,\"state\":\"RUNNING\",\"targetVersion\":\"2\"}";
    private static final String COMPLETED = "{\"present\":true,\"state\":\"COMPLETED\",\"targetVersion\":\"2\"}";

    private HttpServer server;
    private final AtomicInteger firstNodeAnswers = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/upgrade/status", this::status);
        server.createContext("/api/v1/cluster/topology", this::topology);
        server.start();
        ClusterHttpClient.setEndpointOverride("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
        ClusterHttpClient.setEndpointOverride("");
    }

    private void status(HttpExchange exchange) throws java.io.IOException {
        var host = exchange.getRequestHeaders().getFirst("Host");

        if (host.startsWith("localhost")) {
            reply(exchange, COMPLETED);
        } else if (firstNodeAnswers.getAndIncrement() == 0) {
            reply(exchange, RUNNING);
        } else {
            exchange.close();
        }
    }

    private void topology(HttpExchange exchange) throws java.io.IOException {
        reply(exchange, "{\"nodeDetails\":[{\"nodeId\":\"a\",\"health\":\"CONNECTED\",\"address\":\"127.0.0.1:6000\"},"
                        + "{\"nodeId\":\"b\",\"health\":\"CONNECTED\",\"address\":\"localhost:6000\"}]}");
    }

    private static void reply(HttpExchange exchange, String body) throws java.io.IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void wait_whenTheEndpointNodeIsReplacedMidRun_exitsZeroOnceTheRunCompletes() {
        var exit = new ClusterUpgradeCommand().awaitRun(60_000L);

        assertThat(firstNodeAnswers.get()).as("CONTROL: the endpoint node answered once and was then gone").isGreaterThanOrEqualTo(2);
        assertThat(exit).isZero();
    }
}
