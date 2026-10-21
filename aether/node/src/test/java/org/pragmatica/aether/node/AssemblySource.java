// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// The source text of a node's assembly, for the source-text pin tests: `AetherNode.java` plus every file under
/// `node/assembly/`, in that order. The assembly is being split out of `AetherNode` into per-driver units, and a pin
/// that read only `AetherNode.java` would either break on the first move or, worse, keep passing while scanning
/// nothing.
///
/// A pin asserts [org.assertj.core.api.AbstractCharSequenceAssert#containsOnlyOnce] on the joined text: exactly one
/// match across the whole set. Zero means the wiring is gone; two means a pin can no longer say WHICH wiring it
/// protects, and removing one of the two copies would leave it green. The files are joined with a newline, which the
/// two normalisations below never leave inside a file, so a pin cannot match across a file boundary.
///
/// An unreadable or empty `AetherNode.java` fails loudly, because a pin that scans nothing always passes.
public final class AssemblySource {
    private static final String NODE_DIR = "org/pragmatica/aether/node";
    private static final String NODE_FILE = "AetherNode.java";
    private static final String ASSEMBLY_DIR = "assembly";

    private AssemblySource() {}

    /// Every file of the assembly set: `AetherNode.java` first, then `node/assembly/*.java` by name.
    public static List<Path> files() {
        var nodeDir = sourceRoot().resolve(NODE_DIR);
        var files = new ArrayList<Path>();

        files.add(nodeDir.resolve(NODE_FILE));
        files.addAll(assemblyUnits(nodeDir.resolve(ASSEMBLY_DIR)));

        return files;
    }

    /// The set with line comments removed and all whitespace stripped, files joined by a newline.
    public static String stripped() {
        return joined(AssemblySource::strippedText);
    }

    /// The set with every whitespace run collapsed to one space, comments kept, files joined by a newline.
    public static String spaced() {
        return joined(AssemblySource::spacedText);
    }

    private static String joined(Function<Path, String> normalise) {
        var files = files();

        assertThat(files.getFirst()).as("the node source must exist, or every pin scans nothing").exists();
        assertThat(readFile(files.getFirst())).as("AetherNode.java must not be empty").isNotBlank();

        return files.stream()
                    .map(normalise)
                    .collect(Collectors.joining("\n"));
    }

    private static String strippedText(Path file) {
        return readFile(file).lines()
                             .map(line -> line.replaceFirst("//.*$", ""))
                             .collect(Collectors.joining())
                             .replaceAll("\\s+", "");
    }

    private static String spacedText(Path file) {
        return readFile(file).replaceAll("\\s+", " ");
    }

    private static List<Path> assemblyUnits(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }

        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(path -> path.getFileName().toString().endsWith(".java"))
                          .sorted()
                          .toList();
        } catch (IOException e) {
            throw new AssertionError("Cannot list " + dir, e);
        }
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new AssertionError("Cannot read " + path, e);
        }
    }

    private static Path sourceRoot() {
        try {
            var testClasses = Path.of(AssemblySource.class.getProtectionDomain()
                                                          .getCodeSource()
                                                          .getLocation()
                                                          .toURI());

            return testClasses.getParent()
                              .getParent()
                              .resolve("src/main/java");
        } catch (URISyntaxException e) {
            throw new AssertionError("Cannot locate module source root", e);
        }
    }
}
