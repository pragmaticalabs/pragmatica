// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.docs;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.http.HttpMethod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/// Every `curl` an operator is told to run against the management API names a route (#2672: a runbook gave
/// `POST /admin/drain` and `POST /admin/logging`, neither of which exists, so an operator following it got a 404 during
/// an incident).
///
/// Instrument: each `curl` command in `aether/docs/**/*.md` (backslash continuations joined) whose URL targets the
/// management port (`:8080`, `:5150`) or carries the `/api/v1/` prefix is matched, by method and path, against
/// [ManagementRoute#match], the same matcher the server routes with. The method is the `-X` value, else POST when
/// the command sends a body (`-d`, `--data*`), else GET. Placeholders (`{id}`, `<node-id>`, `$VAR`, `${VAR}`) become
/// a literal segment; the query string is dropped.
///
/// Out of scope, by construction: `archive/` (historical), `specs/` (test and design specs that describe planned or
/// superseded surfaces) and `.internal/` (working notes, not operator documentation), and URLs on the application port (`:8070`) or the Forge port (`:8888`), which
/// are not management routes. A fenced block is exempt only when the line directly above its opening fence is
/// [#SKIP_MARKER], which is how a documented-but-not-yet-built route says so out loud.
///
/// The count of checked commands is asserted at a floor, so an extractor that stopped matching cannot turn this
/// green over nothing.
class ManagementCurlPathsDocsTest {
    private static final String DOCS = "aether/docs";
    static final String SKIP_MARKER = "<!-- route-check: skip (planned route) -->";
    private static final int MIN_CHECKED = 80;

    private static final Pattern URL = Pattern.compile("(https?://[^/\\s\"'`]+|\\$\\{?[A-Za-z_]+\\}?)(/[^\\s\"'`)\\\\|?]*)");

    private static final Pattern METHOD = Pattern.compile("(?:^|\\s)(?:-X|--request)\\s+([A-Za-z]+)");
    private static final Pattern BODY_FLAG = Pattern.compile("(?:^|\\s)(?:-d|--data(?:-[a-z]+)?)(?:\\s|=|$)");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^}/]*}|<[^>/]*>|\\$\\{[^}/]*}|\\$[A-Za-z_][A-Za-z0-9_]*");

    record Finding(String file, int line, String command, String method, String path) {
        String describe() {
            return file + ":" + line + "  " + method + " " + path;
        }
    }

    @Test
    void managementCurls_nameADeclaredRoute() throws IOException {
        var findings = managementCurls(docsRoot());
        var bad = findings.stream().filter(f -> !declared(f)).map(Finding::describe).toList();

        assertTrue(findings.size() >= MIN_CHECKED, "extractor found only " + findings.size() + " management curls");
        assertEquals(List.of(), bad, "management curls naming a route ManagementRoute does not declare");
    }

    @Test
    void extractor_readsMethodPathAndPlaceholders() {
        var found = extract("f.md",
                            7,
                            "curl -X POST http://node:8080/api/v1/nodes/drain/<node-id>?force=true");

        assertEquals(List.of("POST /api/v1/nodes/drain/x"),
                     found.stream().map(f -> f.method() + " " + f.path()).toList());
    }

    @Test
    void extractor_infersPostFromABodyAndGetOtherwise() {
        var withBody = extract("f.md", 1, "curl http://node:8080/api/v1/logging/levels -d '{}'");
        var plain = extract("f.md", 2, "curl http://node:8080/api/v1/logging/levels");

        assertEquals("POST",
                     withBody.getFirst().method());
        assertEquals("GET",
                     plain.getFirst().method());
    }

    @Test
    void extractor_ignoresApplicationAndForgePorts() {
        assertEquals(List.of(), extract("f.md", 1, "curl http://node:8070/api/hello"));
        assertEquals(List.of(), extract("f.md", 2, "curl http://localhost:8888/api/status"));
    }

    @Test
    void declared_rejectsTheRoutesThatRunbooksUsedToCall() {
        assertFalse(declared(new Finding("f.md", 1, "", "POST", "/admin/drain")));
        assertFalse(declared(new Finding("f.md", 1, "", "POST", "/admin/logging")));
        assertTrue(declared(new Finding("f.md", 1, "", "POST", "/api/v1/nodes/drain/x")));
        assertTrue(declared(new Finding("f.md", 1, "", "POST", "/api/v1/logging/levels")));
    }

    private static boolean declared(Finding finding) {
        return Stream.of(HttpMethod.values())
                     .filter(m -> m.name()
                                   .equals(finding.method()))
                     .anyMatch(m -> ManagementRoute.match(m,
                                                          finding.path())
                                                   .isSuccess());
    }

    static List<Finding> managementCurls(Path docs) throws IOException {
        var result = new ArrayList<Finding>();

        try (Stream<Path> files = Files.walk(docs)) {
            for (var file : files.filter(p -> p.toString()
                                               .endsWith(".md"))
                                 .filter(ManagementCurlPathsDocsTest::inScope)
                                 .sorted()
                                 .toList()) {
                result.addAll(extractFile(docs.getParent().getParent(),
                                          file));
            }
        }

        return result;
    }

    private static boolean inScope(Path file) {
        var path = file.toString().replace('\\', '/');

        return ! path.contains("/docs/archive/")
               && !path.contains("/docs/specs/")
               && !path.contains("/docs/.internal/");
    }

    private static List<Finding> extractFile(Path root, Path file) throws IOException {
        var lines = Files.readAllLines(file);
        var rel = root.relativize(file).toString();
        var out = new ArrayList<Finding>();
        var skipFence = false;
        var inFence = false;
        var i = 0;

        while (i < lines.size()) {
            var line = lines.get(i);
            var trimmed = line.stripLeading();

            if (trimmed.startsWith("```")) {
                skipFence = !inFence && i > 0 && lines.get(i - 1).strip().equals(SKIP_MARKER);
                inFence = !inFence;
                i++;
                continue;
            }

            if (!line.contains("curl")) {
                i++;
                continue;
            }

            var command = new StringBuilder(line);
            var start = i;

            while (lines.get(i).stripTrailing().endsWith("\\") && i + 1 < lines.size()) {
                i++;
                command.append(' ').append(lines.get(i).strip());
            }

            if (!skipFence) {
                out.addAll(extract(rel, start + 1, command.toString()));
            }

            i++;
        }

        return out;
    }

    static List<Finding> extract(String file, int line, String command) {
        var out = new ArrayList<Finding>();
        var methodMatch = METHOD.matcher(command);
        var method = methodMatch.find()
                     ? methodMatch.group(1).toUpperCase(Locale.ROOT)
                     : impliedMethod(command);
        var urls = URL.matcher(command);

        while (urls.find()) {
            var host = urls.group(1);
            var path = urls.group(2);

            if (isManagement(host, path)) {
                out.add(new Finding(file, line, command, method, normalise(path)));
            }
        }

        return out;
    }

    private static String impliedMethod(String command) {
        return BODY_FLAG.matcher(command).find()
               ? "POST"
               : "GET";
    }

    private static boolean isManagement(String host, String path) {
        return ! host.endsWith(":8070")
               && !host.endsWith(":8888")
               && (host.endsWith(":8080") || host.endsWith(":5150") || path.startsWith("/api/v1/"));
    }

    private static String normalise(String path) {
        var noTrailing = path.length() > 1 && path.endsWith("/")
                         ? path.substring(0, path.length() - 1)
                         : path;

        return PLACEHOLDER.matcher(noTrailing).replaceAll("x");
    }

    private static Path docsRoot() {
        try {
            var dir = Path.of(ManagementCurlPathsDocsTest.class.getProtectionDomain()
                                                               .getCodeSource()
                                                               .getLocation()
                                                               .toURI());

            while (dir != null && !Files.isDirectory(dir.resolve(DOCS))) {
                dir = dir.getParent();
            }

            if (dir == null) {
                throw new IllegalStateException(DOCS + " not found above the test classes");
            }

            return dir.resolve(DOCS);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
