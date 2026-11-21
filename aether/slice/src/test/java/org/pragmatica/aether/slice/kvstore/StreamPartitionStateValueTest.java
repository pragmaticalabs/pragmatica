// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionStateValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionStateValue.Election;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionStateValue.PartitionState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionStateValue.streamPartitionStateValue;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// #3303: the partition-state row's dormant last component `activationGeneration` is carried by every copy method (a write
/// derived from the committed row cannot drop it), no factory or constructor overload defaults it, and no main source writes a
/// non-zero value yet.
///
/// The carry arms run on a row whose component is 7, built here: a carry test on 0 cannot tell a carry from a default.
class StreamPartitionStateValueTest {
    private static final long CARRIED = 7L;
    private static final NodeId NODE_A = NodeId.nodeId("node-a").unwrap();
    private static final NodeId NODE_B = NodeId.nodeId("node-b").unwrap();
    private static final Election ELECTION = Election.election(Epoch.epoch(2L, 6L, 1L), NODE_B, false, List.of(NODE_A, NODE_B));
    private static final String TRIPWIRE = "SP3b-1 writes this in S4: delete this half";
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(".git", ".bb", ".m2-local", "target", "node_modules");
    private static final Pattern WRITE_CALL = Pattern.compile("\\bstreamPartitionStateValue\\(|\\bnew\\s+StreamPartitionStateValue\\(");
    private static final Pattern CARRY_READ = Pattern.compile("(?:[A-Za-z_][A-Za-z0-9_]*\\s*\\.\\s*)?activationGeneration\\(\\s*\\)");

    // ---- (2) carry, one arm per copy method ----

    @Test
    void withElection_set_carriesActivationGeneration() {
        var row = openRow(none());

        var result = row.withElection(some(ELECTION));

        assertThat(result.activationGeneration()).as("activationGeneration after withElection(some)").isEqualTo(CARRIED);
        assertThat(result).isEqualTo(streamPartitionStateValue(3L,
                                                               2L,
                                                               100L,
                                                               PartitionState.OPEN,
                                                               10L,
                                                               none(),
                                                               some(ELECTION),
                                                               List.of(1),
                                                               List.of(2, 3),
                                                               CARRIED));
    }

    @Test
    void withElection_cleared_carriesActivationGeneration() {
        var row = openRow(some(ELECTION));

        var result = row.withElection(none());

        assertThat(result.activationGeneration()).as("activationGeneration after withElection(none)").isEqualTo(CARRIED);
        assertThat(result).isEqualTo(openRow(none()));
    }

    @Test
    void closed_carriesActivationGenerationAndElection() {
        var row = openRow(some(ELECTION));

        var result = row.closed(900L);

        assertThat(result.activationGeneration()).as("activationGeneration after closed").isEqualTo(CARRIED);
        assertThat(result).isEqualTo(streamPartitionStateValue(3L,
                                                               2L,
                                                               100L,
                                                               PartitionState.CLOSED,
                                                               10L,
                                                               some(900L),
                                                               some(ELECTION),
                                                               List.of(1),
                                                               List.of(2, 3),
                                                               CARRIED));
    }

    @Test
    void released_carriesActivationGenerationAndEveryOtherComponent() {
        var row = openRow(some(ELECTION)).closed(900L);

        var result = row.released();

        assertThat(result.activationGeneration()).as("activationGeneration after released").isEqualTo(CARRIED);
        assertThat(result).isEqualTo(streamPartitionStateValue(3L,
                                                               2L,
                                                               100L,
                                                               PartitionState.RELEASED,
                                                               10L,
                                                               some(900L),
                                                               some(ELECTION),
                                                               List.of(1),
                                                               List.of(2, 3),
                                                               CARRIED));
    }

    @Test
    void newLife_onALiveElection_bumpsGenerationClearsElectionAndCarriesActivationGeneration() {
        var row = openRow(some(ELECTION));

        var result = row.newLife(500L);

        assertThat(result.activationGeneration()).as("activationGeneration after newLife").isEqualTo(CARRIED);
        assertThat(result.generation()).as("generation + 1").isEqualTo(3L);
        assertThat(result.lifeStart()).as("the new lifeStart").isEqualTo(500L);
        assertThat(result.election()).as("election cleared").isEqualTo(none());
        assertThat(result).isEqualTo(streamPartitionStateValue(3L,
                                                               3L,
                                                               500L,
                                                               PartitionState.OPEN,
                                                               10L,
                                                               none(),
                                                               none(),
                                                               List.of(1),
                                                               List.of(2, 3),
                                                               CARRIED));
    }

    // ---- no defaulting overload ----

    @Test
    void value_hasExactlyOneFactoryAndOnePublicConstructor_eachWithTenParameters() {
        var factories = Stream.of(StreamPartitionStateValue.class.getDeclaredMethods())
                              .filter(method -> Modifier.isStatic(method.getModifiers()))
                              .filter(method -> method.getReturnType() == StreamPartitionStateValue.class)
                              .toList();
        var constructors = StreamPartitionStateValue.class.getConstructors();

        assertThat(factories).as("static factories of the value").hasSize(1);
        assertThat(factories.getFirst().getParameterCount()).as("factory parameters").isEqualTo(10);
        assertThat(constructors).as("public constructors of the value").hasSize(1);
        assertThat(constructors[0].getParameterCount()).as("constructor parameters").isEqualTo(10);
    }

    // ---- (3) tripwire: no main source writes a non-zero component ----

    @Test
    void mainSources_writeNoNonZeroActivationGeneration() {
        var root = repositoryRoot();
        var files = mainSources(root);
        var calls = files.stream()
                         .filter(file -> !file.endsWith(Path.of("kvstore", "AetherValue.java")))
                         .flatMap(file -> writeCalls(read(file)).stream().map(call -> file + ": " + call))
                         .toList();
        var offenders = calls.stream()
                             .filter(call -> !carriesOrPassesZero(call))
                             .toList();

        System.out.printf("activationGeneration tripwire: root %s, %d main source files, %d calls%n", root, files.size(), calls.size());

        assertThat(files).as("main source files scanned under %s", root).isNotEmpty();
        assertThat(offenders).as(TRIPWIRE).isEmpty();
        assertThat(calls).as("writes of the row outside AetherValue (none exist yet; the first writer updates this count). "
                             + TRIPWIRE)
                         .isEmpty();
    }

    @Test
    void tripwireScan_flagsANonZeroLastArgument_andPassesZeroOrACarry() {
        var fixture = String.join("\n",
                                  "var a = streamPartitionStateValue(1L, 1L, 0L, OPEN, 0L, none(), none(), List.of(1, 2), List.of(), 1);",
                                  "var b = new StreamPartitionStateValue(1L, 1L, 0L, OPEN, 0L, none(), none(), List.of(), List.of(), 0);",
                                  "var c = streamPartitionStateValue(1L, 1L, 0L, OPEN, 0L, none(), none(), List.of(), List.of(), row.activationGeneration());",
                                  "var d = streamPartitionStateValue(1L, 1L, 0L, OPEN, 0L, none(), none(), List.of(), List.of(), 0L);");

        var calls = writeCalls(fixture);

        assertThat(calls).as("calls found in the fixture").hasSize(4);
        assertThat(calls.stream().filter(call -> !carriesOrPassesZero(call)).toList())
            .as("only the call ending in 1 is flagged")
            .containsExactly(calls.getFirst());
    }

    private static StreamPartitionStateValue openRow(Option<Election> election) {
        return streamPartitionStateValue(3L,
                                         2L,
                                         100L,
                                         PartitionState.OPEN,
                                         10L,
                                         none(),
                                         election,
                                         List.of(1),
                                         List.of(2, 3),
                                         CARRIED);
    }

    private static boolean carriesOrPassesZero(String call) {
        var last = lastArgument(call);

        return last.equals("0") || last.equals("0L") || CARRY_READ.matcher(last).matches();
    }

    /// Every write call in `source`, as the text from the call's name through its closing parenthesis.
    private static List<String> writeCalls(String source) {
        var matcher = WRITE_CALL.matcher(source);
        var calls = new ArrayList<String>();

        while (matcher.find()) {
            calls.add(source.substring(matcher.start(), closingParenthesis(source, matcher.end() - 1) + 1));
        }

        return calls;
    }

    private static int closingParenthesis(String source, int open) {
        var depth = 0;

        for (var i = open; i < source.length(); i++) {
            var c = source.charAt(i);

            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }

        return source.length() - 1;
    }

    /// The last top-level argument of `call`, trimmed.
    private static String lastArgument(String call) {
        var open = call.indexOf('(');
        var depth = 0;
        var start = open + 1;

        for (var i = open + 1; i < call.length() - 1; i++) {
            var c = call.charAt(i);

            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                start = i + 1;
            }
        }

        return call.substring(start, call.length() - 1).trim();
    }

    private static Path repositoryRoot() {
        var dir = Path.of("").toAbsolutePath();

        while (dir != null && !(Files.isDirectory(dir.resolve("aether")) && Files.isDirectory(dir.resolve("core"))
                                && Files.isRegularFile(dir.resolve("pom.xml")))) {
            dir = dir.getParent();
        }

        assertThat(dir).as("repository root above %s", Path.of("").toAbsolutePath()).isNotNull();

        return dir;
    }

    private static List<Path> mainSources(Path root) {
        var files = new ArrayList<Path>();

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                    return SKIPPED_DIRECTORIES.contains(String.valueOf(dir.getFileName()))
                           ? FileVisitResult.SKIP_SUBTREE
                           : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (file.toString().endsWith(".java") && isMainSource(root.relativize(file))) {
                        files.add(file);
                    }

                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return files;
    }

    private static boolean isMainSource(Path relative) {
        var text = relative.toString().replace('\\', '/');

        return text.startsWith("src/main/java/") || text.contains("/src/main/java/");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
