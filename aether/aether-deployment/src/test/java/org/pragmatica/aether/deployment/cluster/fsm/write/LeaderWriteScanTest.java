// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm.write;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/// #2584 design §3.1 S1-S5: a source scan of `cluster/fsm/**` (comments and string literals stripped first) for the ways
/// around [TermWriter] that reflection cannot see: a call to the deleted `cluster()` accessor, a hand-built
/// `HierarchyStateWriter`, a direct `KVStore.process`, and in `SchemaOrchestratorService` (until P4) any new apply.
class LeaderWriteScanTest {
    private static final Path MAIN = Path.of("src/main/java/org/pragmatica/aether/deployment");
    private static final Path FSM = MAIN.resolve("cluster/fsm");
    private static final Path WRITE = FSM.resolve("write");
    private static final Path CONTEXT = FSM.resolve("ClusterDeploymentContext.java");
    private static final Path SCHEMA_ORCHESTRATOR = MAIN.resolve("schema/SchemaOrchestratorService.java");

    private static final Pattern CLUSTER_ACCESSOR = Pattern.compile("\\.\\s*cluster\\s*\\(\\s*\\)|::\\s*cluster\\b");
    private static final Pattern HIERARCHY_WRITER = Pattern.compile("\\bHierarchyStateWriter\\b");
    private static final Pattern STORE_PROCESS = Pattern.compile("(?i:kvStore|store)\\s*(?:\\(\\s*\\))?\\s*\\.\\s*process\\s*\\(");
    private static final Pattern CLUSTER_APPLY = Pattern.compile("cluster\\s*\\.\\s*apply\\s*\\(");
    /// A member declaration on one line: modifiers, a type and a name followed by its parameter list and body.
    private static final Pattern METHOD_DECLARATION = Pattern.compile("(?m)^[ \\t]*(?:(?:public|private|protected|static|final|default|synchronized)[ \\t]+)*"
                                                                      + "(?:<[^>\\n]+>[ \\t]+)?(?!(?:return|new|else|throw)\\b)[\\w.<>\\[\\],?]+(?:[ \\t]+[\\w.<>\\[\\],?]+)*[ \\t]+"
                                                                      + "(?!(?:if|for|while|switch|catch|synchronized)\\b)(\\w+)[ \\t]*\\([^;{]*\\)[ \\t]*(?:throws[^{;]*)?\\{");
    private static final Pattern CLUSTER_PARAMETER = Pattern.compile("ClusterNode<KVCommand<AetherKey>>\\s+cluster\\b");
    private static final Pattern CLUSTER_TOKEN = Pattern.compile("(?<![.\\w])cluster\\b");
    /// S3: every `cluster.apply` in SchemaOrchestratorService, by method, until P4 routes them through a writer.
    private static final Map<String, Integer> SCHEMA_APPLIES = Map.of("recordOutcome", 1,
                                                                      "acquireLock", 1,
                                                                      "renewalPut", 1,
                                                                      "putLock", 1,
                                                                      "updateStatus", 1,
                                                                      "updateStatusWithAttempt", 1);

    @Test
    void scope_isClusterFsmOutsideTheWriters() {
        var files = scannedFiles();

        assertThat(files).as("positive controls").contains(FSM.resolve("ClusterDeploymentState.java"), FSM.resolve("StaleEntryCleaner.java"), CONTEXT);
        assertThat(files).as("only cluster/fsm, never fsm/write, fsm2 or a sibling such as ClusterDeploymentManager")
                         .allMatch(file -> file.startsWith(FSM) && !file.startsWith(WRITE));
    }

    @Test
    void S1_theContextPassesItsClusterNodeOnlyToTheWriter() {
        var source = stripped(CONTEXT);
        var body = source.substring(source.indexOf("class ClusterDeploymentContext"));
        var parameters = CLUSTER_PARAMETER.matcher(body).results().count();
        var misuses = new ArrayList<String>();
        var tokens = CLUSTER_TOKEN.matcher(body);

        while (tokens.find()) {
            if (!isParameterDeclaration(body, tokens.start()) && !isWriterOrDelegationArgument(body, tokens.start())) {
                misuses.add(lineAround(body, tokens.start()));
            }
        }
        assertThat(parameters).as("positive control: the constructors still take the ClusterNode").isEqualTo(4);
        assertThat(misuses).isEmpty();
    }

    @Test
    void S2_noClusterAccessorCallOutsideTheWriters() {
        assertThat(matchesIn(CLUSTER_ACCESSOR)).isEmpty();
    }

    @Test
    void S3_schemaOrchestratorApplies_areExactlyTheAllowlist() {
        assertThat(appliesByMethod(stripped(SCHEMA_ORCHESTRATOR))).isEqualTo(SCHEMA_APPLIES);
    }

    @Test
    void S4_noHierarchyStateWriterOutsideTheWriters() {
        assertThat(matchesIn(HIERARCHY_WRITER)).isEmpty();
    }

    @Test
    void S5_noDirectStoreProcess() {
        assertThat(matchesIn(STORE_PROCESS)).isEmpty();
    }

    @Test
    void patterns_matchEveryShapeOfTheBypass() {
        assertThat(CLUSTER_ACCESSOR.matcher(strip("ctx.cluster()\n   .apply(commands);")).find()).as("multi-line").isTrue();
        assertThat(CLUSTER_ACCESSOR.matcher(strip("ctx.cluster().<Object> apply(commands);")).find()).as("typed apply").isTrue();
        assertThat(CLUSTER_ACCESSOR.matcher(strip("active.ctx()\n .cluster()\n .apply(commands);")).find()).as("via active").isTrue();
        assertThat(CLUSTER_ACCESSOR.matcher(strip("Supplier<?> s = ctx::cluster;")).find()).as("method reference").isTrue();
        assertThat(HIERARCHY_WRITER.matcher(strip("var w = new HierarchyStateWriter(a, b, c);")).find()).as("S4 constructor").isTrue();
        assertThat(STORE_PROCESS.matcher(strip("ctx.kvStore().process(batch);")).find()).as("S5").isTrue();
        assertThat(appliesByMethod(strip("    private Promise<Unit> sneak(String name) {\n        return cluster.apply(List.of());\n    }\n")))
                .as("S3 attributes an apply to its method").isEqualTo(Map.of("sneak", 1));
    }

    @Test
    void patterns_ignoreCommentsAndStrings() {
        var comment = "        /// round 2 item g) — bundled into the SAME `ctx.cluster().apply` batch as the previous\n";

        assertThat(CLUSTER_ACCESSOR.matcher(strip(comment)).find()).as("the CDS doc comment").isFalse();
        assertThat(CLUSTER_ACCESSOR.matcher(strip("log.info(\"ctx.cluster() is gone\"); /* ctx.cluster() */ // ctx.cluster()")).find())
                .as("string, block and line comments").isFalse();
        assertThat(CLUSTER_ACCESSOR.matcher(strip("String s = \"\"\"\n    ctx.cluster()\n    \"\"\";")).find()).as("text block").isFalse();
    }

    private static List<String> matchesIn(Pattern pattern) {
        var found = new ArrayList<String>();

        for (var file : scannedFiles()) {
            var source = stripped(file);
            var matcher = pattern.matcher(source);

            while (matcher.find()) {
                found.add(file.getFileName() + ": " + lineAround(source, matcher.start()));
            }
        }
        return found;
    }

    private static Map<String, Integer> appliesByMethod(String source) {
        var declarations = new ArrayList<Integer>();
        var names = new ArrayList<String>();
        var declaration = METHOD_DECLARATION.matcher(source);

        while (declaration.find()) {
            declarations.add(declaration.start());
            names.add(declaration.group(1));
        }
        var counts = new LinkedHashMap<String, Integer>();
        var apply = CLUSTER_APPLY.matcher(source);

        while (apply.find()) {
            counts.merge(enclosingMethod(declarations, names, apply.start()), 1, Integer::sum);
        }
        return counts;
    }

    private static String enclosingMethod(List<Integer> declarations, List<String> names, int position) {
        var name = "<outside any method>";

        for (var i = 0; i < declarations.size() && declarations.get(i) < position; i++) {
            name = names.get(i);
        }
        return name;
    }

    private static boolean isParameterDeclaration(String source, int position) {
        var start = Math.max(0, position - "ClusterNode<KVCommand<AetherKey>> ".length() - 4);

        return CLUSTER_PARAMETER.matcher(source.substring(start, Math.min(source.length(), position + "cluster".length())))
                                .find();
    }

    /// True when the token is an argument of `FsmWriter.fsmWriter(` or of a `this(` delegation.
    private static boolean isWriterOrDelegationArgument(String source, int position) {
        var depth = 0;

        for (var i = position - 1; i >= 0; i--) {
            var c = source.charAt(i);

            if (c == ')') {
                depth++;
            } else if (c == '(' && depth > 0) {
                depth--;
            } else if (c == '(') {
                var callee = source.substring(0, i).stripTrailing();

                return callee.endsWith("FsmWriter.fsmWriter") || callee.endsWith("this");
            } else if (c == ';' || c == '{' || c == '}') {
                return false;
            }
        }
        return false;
    }

    private static String lineAround(String source, int position) {
        var start = source.lastIndexOf('\n', position) + 1;
        var end = source.indexOf('\n', position);

        return source.substring(start, end < 0 ? source.length() : end).strip();
    }

    private static List<Path> scannedFiles() {
        try (var files = Files.walk(FSM)) {
            return files.filter(file -> file.toString().endsWith(".java"))
                        .filter(file -> !file.startsWith(WRITE))
                        .sorted()
                        .toList();
        } catch (IOException e) {
            throw new AssertionError("cannot list " + FSM.toAbsolutePath(), e);
        }
    }

    private static String stripped(Path file) {
        try {
            return strip(Files.readString(file));
        } catch (IOException e) {
            throw new AssertionError("cannot read " + file.toAbsolutePath(), e);
        }
    }

    /// Blanks comments, string literals, text blocks and char literals, keeping newlines so positions keep their lines.
    static String strip(String source) {
        var out = new StringBuilder(source.length());
        var i = 0;

        while (i < source.length()) {
            if (source.startsWith("//", i)) {
                i = blankUntil(source, out, i, source.indexOf('\n', i), 0);
            } else if (source.startsWith("/*", i)) {
                i = blankUntil(source, out, i, source.indexOf("*/", i + 2), 2);
            } else if (source.startsWith("\"\"\"", i)) {
                i = blankUntil(source, out, i, source.indexOf("\"\"\"", i + 3), 3);
            } else if (source.charAt(i) == '"' || source.charAt(i) == '\'') {
                i = blankUntil(source, out, i, literalEnd(source, i), 1);
            } else {
                out.append(source.charAt(i++));
            }
        }
        return out.toString();
    }

    private static int literalEnd(String source, int open) {
        var quote = source.charAt(open);

        for (var i = open + 1; i < source.length(); i++) {
            if (source.charAt(i) == '\\') {
                i++;
            } else if (source.charAt(i) == quote) {
                return i;
            }
        }
        return source.length();
    }

    private static int blankUntil(String source, StringBuilder out, int from, int found, int closerLength) {
        var end = found < 0 ? source.length() : Math.min(source.length(), found + closerLength);

        for (var i = from; i < end; i++) {
            out.append(source.charAt(i) == '\n' ? '\n' : ' ');
        }
        return end;
    }
}
