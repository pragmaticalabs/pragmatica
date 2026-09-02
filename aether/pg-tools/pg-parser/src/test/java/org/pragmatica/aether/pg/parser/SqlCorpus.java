// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.parser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/// The repo-wide SQL corpus walk shared by every corpus-level sensor in this module. ONE
/// implementation on purpose: the isRegularFile filter below was first added to a single caller
/// (CorpusParseTest, the #598 CI follow-up) and its absent sibling copy broke the next full build —
/// a shared mechanism must be fixed at the mechanism, not at a caller.
final class SqlCorpus {
    private SqlCorpus() {}

    static List<Path> sqlFiles(Path root) throws Exception {
        try (var walk = Files.walk(root)) {
            // isRegularFile is load-bearing, not hygiene: JRE dist output contains DIRECTORIES
            // named `java.sql` (package-shaped legal/ dirs), and a name-suffix match alone turns
            // them into an IOException mid-corpus on any machine with a prior dist build.
            return walk.filter(Files::isRegularFile)
                       .filter(p -> p.toString().endsWith(".sql"))
                       .filter(p -> !p.toString().contains("/target/"))
                       .filter(p -> !p.toString().contains("/.git/"))
                       .sorted(Comparator.comparing(p -> root.relativize(p).toString()))
                       .toList();
        }
    }

    static Path repoRoot() {
        var dir = Path.of("").toAbsolutePath();

        while (dir != null && !Files.exists(dir.resolve(".git"))) {
            dir = dir.getParent();
        }

        return dir == null
               ? Path.of("").toAbsolutePath()
               : dir;
    }
}
