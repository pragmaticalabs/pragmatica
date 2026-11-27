// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #3828 lane 1, the enforcer for "inert": nothing writes the directive receipt yet. A source scan of every production `src/main/java`
/// under `aether/` (comments stripped first) for a way to MAKE the key: its constructor, a method reference to it, or its factory. Only
/// `AetherKey` itself (the declaration) may. The compiler-required `switch` arms and `ReplicaFilter` name the key as a type pattern, which
/// this scan does not match. Lane 3 of the redesign, the writer, deletes this test and replaces it with the writer's own pin.
class DirectiveReceiptKeyInertTest {
    private static final Path AETHER = Path.of("..");
    private static final Path DECLARATION = Path.of("slice/src/main/java/org/pragmatica/aether/slice/kvstore/AetherKey.java");
    private static final Pattern MAKES_THE_KEY = Pattern.compile("\\bnew\\s+(?:AetherKey\\s*\\.\\s*)?DirectiveReceiptKey\\s*\\(" + "|\\bDirectiveReceiptKey\\s*::\\s*new\\b"
                                                                 + "|\\bdirectiveReceiptKey\\s*\\(");
    private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);

    @Test
    void scope_coversTheDeclarationAndTheDeploymentModule() {
        var files = productionSources();

        assertThat(files).as("positive controls: the scan reaches the declaring file and the cluster FSM's Directory")
                         .contains(AETHER.resolve(DECLARATION), AETHER.resolve("aether-deployment/src/main/java/org/pragmatica/aether/deployment/cluster/fsm2/Directory.java"));
        assertThat(files).as("only production sources").allMatch(file -> file.toString().contains("/src/main/java/"));
    }

    @Test
    void noProductionCode_makesTheReceiptKey_outsideItsDeclaration() {
        assertThat(makers()).isEmpty();
    }

    @Test
    void pattern_matchesEveryWayToMakeTheKey_andIgnoresCommentsAndTypePatterns() {
        assertThat(makesTheKey("var k = new DirectiveReceiptKey(node);")).as("constructor").isTrue();
        assertThat(makesTheKey("var k = new AetherKey.DirectiveReceiptKey (node);")).as("qualified constructor").isTrue();
        assertThat(makesTheKey("nodes.map(DirectiveReceiptKey::new)")).as("constructor reference").isTrue();
        assertThat(makesTheKey("var k = DirectiveReceiptKey.directiveReceiptKey(node);")).as("factory").isTrue();
        assertThat(makesTheKey("case AetherKey.DirectiveReceiptKey _ -> true;")).as("type pattern in a switch").isFalse();
        assertThat(makesTheKey("import org.pragmatica.aether.slice.kvstore.AetherKey.DirectiveReceiptKey;")).as("import").isFalse();
        assertThat(makesTheKey("/// never `new DirectiveReceiptKey(node)` here\n// new DirectiveReceiptKey(node)\n/* new DirectiveReceiptKey(node) */")).as("comments").isFalse();
    }

    private static boolean makesTheKey(String source) {
        return MAKES_THE_KEY.matcher(COMMENTS.matcher(source).replaceAll("")).find();
    }

    private static List<String> makers() {
        return productionSources().stream()
                                  .filter(file -> !file.equals(AETHER.resolve(DECLARATION)))
                                  .filter(file -> makesTheKey(read(file)))
                                  .map(Path::toString)
                                  .toList();
    }

    private static List<Path> productionSources() {
        try (var files = Files.walk(AETHER)) {
            return files.filter(file -> file.toString().endsWith(".java"))
                        .filter(file -> file.toString().contains("/src/main/java/"))
                        .filter(file -> !file.toString().contains("/target/"))
                        .sorted()
                        .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + AETHER.toAbsolutePath(), e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }
}
