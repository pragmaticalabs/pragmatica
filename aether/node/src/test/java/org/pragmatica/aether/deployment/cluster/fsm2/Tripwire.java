// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/// A source scan for the architecture tripwires (NT-116): which main sources construct or mention a name.
final class Tripwire {
    private Tripwire() {}

    static Path repositoryRoot() {
        var dir = Path.of("").toAbsolutePath();

        while (dir != null && !Files.isDirectory(dir.resolve("aether/aether-deployment/src/main"))) {
            dir = dir.getParent();
        }
        return dir;
    }

    /// Lines of `.java` files under `root` (main sources only, definitions excluded) that construct `type`.
    static List<String> constructions(Path root, String type) throws IOException {
        var construct = Pattern.compile("new\\s+(?:[\\w.]+\\.)?" + type + "\\s*\\(|\\b" + Character.toLowerCase(type.charAt(0)) + type.substring(1) + "\\s*\\(");

        return scan(root, type, line -> construct.matcher(line).find());
    }

    static List<String> mentions(Path root, String type) throws IOException {
        return scan(root, type, line -> line.contains(type));
    }

    private static List<String> scan(Path root, String type, java.util.function.Predicate<String> match) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            var sources = files.filter(path -> path.toString().endsWith(".java"))
                               .filter(path -> !path.toString().contains("/src/test/"))
                               .filter(path -> !path.toString().contains("/target/"))
                               .filter(path -> !path.toString().contains(".m2-local"))
                               .filter(path -> !path.getFileName().toString().equals("MembershipDecision.java"))
                               .toList();
            var hits = new java.util.ArrayList<String>();

            for (var source : sources) {
                for (var line : Files.readAllLines(source)) {
                    if (match.test(line)) {
                        hits.add(source + ": " + line.trim());
                    }
                }
            }
            return hits;
        }
    }
}
