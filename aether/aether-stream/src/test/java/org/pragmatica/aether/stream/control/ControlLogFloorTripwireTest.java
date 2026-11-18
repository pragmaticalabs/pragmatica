// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.control;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// T-01 (stream storage design §9.1, S0): `ControlLog` gets a production caller only together with its floor in the
/// retention `min` (§4.1 control row). At the read SHA no production source constructs one: only the factories
/// themselves exist (`ControlLog.controlLog`, two overloads) and `StreamAssembly` calls the 7-argument
/// `StreamForwardHandler.streamForwardHandler`, which passes `Option.none()`. This test is green at that state by
/// design and turns red the moment a `src/main` construction appears.
///
/// Scanned: every `*.java` whose path contains `/src/main/` under the repository root (`target/` and `src/test/` excluded, so the control fixtures under test resources never count), comments
/// stripped first so a mention in a comment is not a construction. Two call shapes are hits: the `ControlLog.controlLog(`
/// factory (qualified, method reference, or bare under a static import), and the `ControlLog` overload of
/// `streamForwardHandler`, found by arity: it is the only 4-argument overload (the others take 3, 5, 6 or 7).
class ControlLogFloorTripwireTest {
    private static final Pattern QUALIFIED_FACTORY = Pattern.compile("\\bControlLog\\s*(?:\\.|::)\\s*controlLog\\b");
    private static final Pattern STATIC_IMPORT = Pattern.compile("import\\s+static\\s+[\\w.]*ControlLog\\s*\\.\\s*(?:controlLog|\\*)\\s*;");
    private static final Pattern BARE_FACTORY = Pattern.compile("(?<![\\w.])(?<!ControlLog )controlLog\\s*\\(");
    private static final Pattern HANDLER_CALL = Pattern.compile("(?<![\\w])(?<!StreamForwardHandler )streamForwardHandler\\s*\\(");
    private static final Pattern GENERIC_ARGUMENTS = Pattern.compile("^<[\\w\\s,.?<>\\[\\]]*>");
    private static final int CONTROL_LOG_OVERLOAD_ARITY = 4;

    @Test
    void controlLogHasNoProductionConstructionWithoutFloor() {
        var hits = productionHits(repositoryRoot());

        assertThat(hits).as("T-01: a src/main construction of ControlLog (ControlLog.controlLog or the ControlLog overload of "
                            + "StreamForwardHandler.streamForwardHandler) appeared. A production ControlLog is allowed only together with "
                            + "its floor: compose ControlRetentionFloor in the node's retention floor (design §4.1 control row) in the "
                            + "same PR, then replace this tripwire with the test that pins that composition.")
                        .isEmpty();
    }

    /// Positive control: the same scanner over a fixture tree holding exactly one factory construction (plus a comment
    /// mention and a declaration, which must not count) finds exactly one hit. A broken glob or a broken pattern
    /// finds zero and turns this red, so the empty result above is never the result of a scan that reached nothing.
    @Test
    void scanner_findsExactlyOneFactoryConstructionInTheFixture() {
        var hits = productionHits(fixtureRoot("factory"));

        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst()).contains("FactoryFixture.java");
    }

    /// Positive control for the handler arm: one 4-argument call whose last argument carries a generic comma is found; the
    /// 7-argument call, the declaration and the comment are not.
    @Test
    void scanner_findsExactlyOneControlLogHandlerCallInTheFixture() {
        var hits = productionHits(fixtureRoot("handler"));

        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst()).contains("HandlerFixture.java");
    }

    @Test
    void scanner_readsTheProductionCorpus() {
        var root = repositoryRoot();

        assertThat(productionSources(root).size()).as("src/main Java files scanned under " + root)
                                                  .isGreaterThan(1000);
        assertThat(productionSources(root)).as("the factory's own file is inside the scan (so only its declarations are skipped)")
                                           .anyMatch(path -> path.endsWith("aether/stream/control/ControlLog.java"));
    }

    @Test
    void withoutComments_dropsCommentsAndKeepsLiterals() {
        assertThat(withoutComments("a(); // ControlLog.controlLog(x)\nb();")).doesNotContain("controlLog")
                                                                              .contains("b();");
        assertThat(withoutComments("/* ControlLog.controlLog( */ c();")).doesNotContain("controlLog");
        assertThat(withoutComments("var u = \"http://x\"; ControlLog.controlLog(a);")).contains("ControlLog.controlLog(a);");
    }

    private static List<String> productionHits(Path root) {
        var hits = new ArrayList<String>();

        for (var path : productionSources(root)) {
            var source = withoutComments(read(path));
            collect(hits, root, path, QUALIFIED_FACTORY.matcher(source), source, false);
            collectBareFactory(hits, root, path, source);
            collect(hits, root, path, HANDLER_CALL.matcher(source), source, true);
        }
        return hits;
    }

    private static void collectBareFactory(List<String> hits, Path root, Path path, String source) {
        if (STATIC_IMPORT.matcher(source).find()) {
            collect(hits, root, path, BARE_FACTORY.matcher(source), source, false);
        }
    }

    private static void collect(List<String> hits, Path root, Path path, Matcher matcher, String source, boolean byArity) {
        while (matcher.find()) {
            if (!byArity || argumentCount(source, matcher.end()) == CONTROL_LOG_OVERLOAD_ARITY) {
                hits.add(root.relativize(path) + ": " + source.substring(matcher.start(), matcher.end()));
            }
        }
    }

    /// Number of top-level arguments of the call whose opening parenthesis ends just before `from`. Nested parentheses,
    /// brackets, braces, string literals and generic argument lists (`Holder.<A, B>get()`) do not split arguments.
    static int argumentCount(String source, int from) {
        var depth = 1;
        var count = 1;
        var empty = true;
        var i = from;

        while (i < source.length() && depth > 0) {
            var c = source.charAt(i);

            if (c == '"' || c == '\'') {
                i = endOfLiteral(source, i, c);
                empty = false;
                continue;
            }
            if (c == '<' && depth == 1 && i > 0 && (Character.isJavaIdentifierPart(source.charAt(i - 1)) || source.charAt(i - 1) == '.')) {
                var generic = GENERIC_ARGUMENTS.matcher(source.substring(i));
                if (generic.find()) {
                    i += generic.end();
                    empty = false;
                    continue;
                }
            }
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 1) {
                count++;
            }
            if (!Character.isWhitespace(c) && depth > 0) {
                empty = false;
            }
            i++;
        }
        return empty ? 0 : count;
    }

    /// Filters on the path relative to `root`, so a fixture root that itself lives under `src/test/` is scanned like a repository.
    private static List<Path> productionSources(Path root) {
        try (var paths = Files.walk(root)) {
            return paths.filter(path -> path.toString()
                                            .endsWith(".java"))
                        .filter(path -> isProduction("/" + root.relativize(path)))
                        .toList();
        } catch (IOException e) {
            throw new AssertionError("Cannot walk sources under " + root, e);
        }
    }

    private static boolean isProduction(String relativePath) {
        return relativePath.contains("/src/main/") && !relativePath.contains("/target/") && !relativePath.contains("/src/test/");
    }

    private static Path repositoryRoot() {
        var dir = Path.of("")
                      .toAbsolutePath();

        while (dir != null && !(Files.isDirectory(dir.resolve("aether")) && Files.isDirectory(dir.resolve("core")))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("repository root (the directory holding aether/ and core/) above the test's working directory")
                       .isNotNull();
        return dir;
    }

    private static Path fixtureRoot(String name) {
        var root = Path.of("src/test/resources/control-log-floor-fixture", name)
                       .toAbsolutePath();

        assertThat(root).as("fixture root")
                        .isDirectory();
        return root;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new AssertionError("Cannot read " + path, e);
        }
    }

    /// Removes `//` and `/* */` comments, leaving string, char and text-block literals intact.
    static String withoutComments(String source) {
        var out = new StringBuilder(source.length());
        var i = 0;

        while (i < source.length()) {
            var c = source.charAt(i);

            if (source.startsWith("\"\"\"", i)) {
                var end = source.indexOf("\"\"\"", i + 3);
                var stop = end < 0 ? source.length() : end + 3;
                out.append(source, i, stop);
                i = stop;
            } else if (c == '"' || c == '\'') {
                var stop = endOfLiteral(source, i, c);
                out.append(source, i, stop);
                i = stop;
            } else if (source.startsWith("//", i)) {
                var end = source.indexOf('\n', i);
                i = end < 0 ? source.length() : end;
            } else if (source.startsWith("/*", i)) {
                var end = source.indexOf("*/", i + 2);
                i = end < 0 ? source.length() : end + 2;
                out.append(' ');
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int endOfLiteral(String source, int start, char quote) {
        var i = start + 1;

        while (i < source.length() && source.charAt(i) != quote && source.charAt(i) != '\n') {
            i += source.charAt(i) == '\\' ? 2 : 1;
        }
        return Math.min(i + 1, source.length());
    }
}
