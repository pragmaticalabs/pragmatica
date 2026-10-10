// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2101 guard: a client-body producer renders a cause through `HttpError.clientMessage(Cause)`, never through a
/// direct `cause.message()` -- `message()` of a chained cause walks the origin chain. The scan covers the statements
/// that write a client body (`sendProblem`, `writeProblem`, `plainErrorResponse`, `response.error`, a bare `error(`) in
/// the producer files, and carries its own positive control: a synthetic violation is flagged and a synthetic
/// compliant statement is not, and the real scan must have reached a minimum number of statements.
class ClientBodyProducersUseRendererTest {
    private static final List<String> PRODUCERS = List.of("src/main/java/org/pragmatica/aether/http/AppHttpServer.java",
                                                          "src/main/java/org/pragmatica/aether/api/ManagementServer.java",
                                                          "src/main/java/org/pragmatica/aether/api/routes/ProblemResponses.java",
                                                          "src/main/java/org/pragmatica/aether/api/routes/MavenProtocolRoutes.java",
                                                          "../http-routing-adapter/src/main/java/org/pragmatica/aether/http/adapter/SliceRouter.java",
                                                          "../../integrations/net/http-server/src/main/java/org/pragmatica/http/server/ResponseWriter.java",
                                                          "../../integrations/net/http-types/src/main/java/org/pragmatica/http/ProblemDetail.java");

    private static final Pattern STATEMENT_START = Pattern.compile("\\b(sendProblem|writeProblem|plainErrorResponse|problemResponse|response\\.error|fromCause)\\(|(?<![\\w.])error\\(");
    private static final Pattern DIRECT_RENDER = Pattern.compile("\\b(cause|transientCause|failure|httpError)\\.message\\(\\)");

    private record Statement(String text) {}

    static List<Statement> clientBodyStatements(String source) {
        var statements = new ArrayList<Statement>();
        var matcher = STATEMENT_START.matcher(source);

        while (matcher.find()) {
            var end = source.indexOf(';', matcher.start());

            statements.add(new Statement(source.substring(matcher.start(), end < 0 ? source.length() : end)));
        }

        return statements;
    }

    static List<String> violations(String source) {
        return clientBodyStatements(source).stream()
                                           .map(Statement::text)
                                           .filter(text -> DIRECT_RENDER.matcher(text).find())
                                           .toList();
    }

    @Test
    void scanner_flagsADirectRender_control() {
        assertThat(violations("sendProblem(response, status, cause.message(), path, id);")).hasSize(1);
        assertThat(violations("response.error(HttpStatus.GATEWAY_TIMEOUT, cause.message());")).hasSize(1);
        assertThat(violations("plainErrorResponse(httpError.status(), httpError.message())")).hasSize(1);
    }

    @Test
    void scanner_passesTheRenderer_control() {
        assertThat(violations("sendProblem(response, status, HttpError.clientMessage(cause), path, id);")).isEmpty();
        assertThat(violations("log.warn(\"failed {}\", cause.message());")).isEmpty();
    }

    @Test
    void producers_renderCausesOnlyThroughTheRenderer() throws IOException {
        var violating = new ArrayList<String>();
        var scanned = 0;

        for (var producer : PRODUCERS) {
            var source = Files.readString(Path.of(producer));

            scanned += clientBodyStatements(source).size();
            violations(source).forEach(text -> violating.add(producer + ": " + text.replaceAll("\\s+", " ")));
        }

        assertThat(scanned).as("the scan must reach client-body statements, or an empty result proves nothing").isGreaterThan(20);
        assertThat(violating).isEmpty();
    }
}
