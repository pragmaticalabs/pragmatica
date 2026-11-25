// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;
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
    void events_withCodeAndSeverity_sendsBothQueryParametersToTheNode() {
        var exit = new CommandLine(new AetherCli()).execute("--connect", endpoint(), "events", "--code", "core-absence-fence", "--severity", "critical");

        assertThat(exit).isZero();
        assertThat(rawQueries).containsExactly("code=core-absence-fence&severity=critical");
    }

    @Test
    void events_withNoFilter_sendsNoQueryString() {
        var exit = new CommandLine(new AetherCli()).execute("--connect", endpoint(), "events");

        assertThat(exit).isZero();
        assertThat(rawQueries).containsExactly("null");
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
