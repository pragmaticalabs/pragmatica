// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpServer;
import org.pragmatica.http.JdkHttpOperations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;


/// #1631: `aether events --code <code> --severity <level>` reaches `GET /api/v1/events` as the `code` and `severity`
/// query parameters. The first group pins the query string the command builds; the second runs the real command
/// against a listener and asserts what that listener RECEIVED, so a flag that parsed but never made it onto the wire
/// goes red.
class EventsFilterCommandTest {
    private HttpServer node;
    private final List<String> rawQueries = new CopyOnWriteArrayList<>();

    @BeforeEach
    void listener() throws IOException {
        node = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        node.createContext("/",
                           exchange -> {
                               rawQueries.add(String.valueOf(exchange.getRequestURI().getRawQuery()));
                               var body = "[]".getBytes(StandardCharsets.UTF_8);

                               exchange.sendResponseHeaders(200, body.length);
                               exchange.getResponseBody().write(body);
                               exchange.close();
                           });
        node.start();
    }

    @AfterEach
    void stopListener() {
        node.stop(0);
    }

    @Test
    void buildEventsQuery_withNoOptions_isEmpty() throws Exception {
        assertThat(queryFor()).isEmpty();
    }

    @Test
    void buildEventsQuery_withCodeAndSeverity_carriesBoth() throws Exception {
        assertThat(queryFor("--code", "swim-kill-gate-held", "--severity", "WARNING")).isEqualTo("code=swim-kill-gate-held&severity=WARNING");
    }

    @Test
    void buildEventsQuery_withSince_keepsItAheadOfTheFilters() throws Exception {
        assertThat(queryFor("--since", "2024-01-15T10:30:00Z", "--severity", "info")).isEqualTo("since=2024-01-15T10:30:00Z&severity=info");
    }

    @Test
    void buildEventsQuery_encodesAFilterValueThatCouldSplitTheQuery() throws Exception {
        assertThat(queryFor("--code", "a&severity=INFO b")).isEqualTo("code=a%26severity%3DINFO+b");
    }

    @Test
    void events_withCodeAndSeverity_sendsBothQueryParametersToTheNode() throws Exception {
        var exit = executeAgainstNode("events", "--code", "core-absence-fence", "--severity", "critical");

        assertThat(exit).isZero();
        assertThat(rawQueries).containsExactly("code=core-absence-fence&severity=critical");
    }

    @Test
    void events_withNoFilter_sendsNoQueryString() throws Exception {
        var exit = executeAgainstNode("events");

        assertThat(exit).isZero();
        assertThat(rawQueries).containsExactly("null");
    }

    /// `main` builds the HTTP client before it executes; a test that drives `execute` directly must do the same.
    private int executeAgainstNode(String... args) throws Exception {
        var cli = new AetherCli();
        var httpOps = AetherCli.class.getDeclaredField("httpOps");

        httpOps.setAccessible(true);
        httpOps.set(cli, JdkHttpOperations.jdkHttpOperations());

        var line = Stream.concat(Stream.of("--connect", endpoint()), Stream.of(args)).toArray(String[]::new);

        return new CommandLine(cli).execute(line);
    }

    private String endpoint() {
        return "127.0.0.1:" + node.getAddress().getPort();
    }

    private static String queryFor(String... args) throws Exception {
        var command = new AetherCli.EventsCommand();

        new CommandLine(command).parseArgs(args);
        var method = AetherCli.EventsCommand.class.getDeclaredMethod("buildEventsQuery");

        method.setAccessible(true);

        return (String) method.invoke(command);
    }
}
