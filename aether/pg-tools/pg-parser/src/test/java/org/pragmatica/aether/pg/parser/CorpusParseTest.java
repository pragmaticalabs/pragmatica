// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.parser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Corpus-level sensors over the facade, asserting the two properties that unit tests could not see
/// during the peglib 0.6.0 -> 0.7.2 migration.
///
/// Both correspond to a real defect that shipped past a fully green suite:
///   - [#everySqlFileInTheRepoParses] — `--` comments stopped being lexed as trivia and 18 of 34
///     real schema files became unparseable, while every unit test stayed green.
///   - [#everyHarvestedStatementIsCountedSeparately] — the synthetic `_ROOT` wrapper made the facade
///     report one statement per file; the previous parser had reported a constant two, so the count
///     was wrong in both directions and nothing noticed.
///
/// A byte-exact CST dump is deliberately NOT checked in. It was the instrument that found these, but
/// as a 2 MB golden file it regenerates wholesale on any CST change and would be re-baselined
/// mechanically the first time it went red. Run the differential by hand for deep changes — build
/// before, build after, diff:
///
/// ```
/// mvn -Pgenerate-parser -pl aether/pg-tools/pg-parser test -Dtest=ZCstDumpTest \
///     -Dsurefire.failIfNoSpecifiedTests=false -Dcstdump.out=/abs/path/dump.txt
/// ```
class CorpusParseTest {
    private static final PostgresParser PARSER = PostgresParser.create();

    @Test
    void everySqlFileInTheRepoParses() throws Exception {
        var root = repoRoot();
        var failures = new ArrayList<String>();
        var files = sqlFiles(root);

        assertThat(files).as("no .sql files found under %s", root).isNotEmpty();

        for (var file : files) {
            PARSER.parseCst(Files.readString(file))
                  .onFailure(cause -> failures.add(root.relativize(file) + ": " + cause.message()));
        }

        assertThat(failures).as("SQL files that no longer parse").isEmpty();
    }

    /// The harvested corpus holds exactly one statement per non-comment line, so the facade's
    /// statement count is checkable against the file itself rather than against a stored dump.
    @Test
    void everyHarvestedStatementIsCountedSeparately() throws Exception {
        var corpus = Path.of("src/test/resources/corpus");
        var files = Files.list(corpus)
                         .filter(p -> p.toString().endsWith(".sql"))
                         .sorted()
                         .toList();

        assertThat(files).as("harvested corpus is missing").isNotEmpty();

        for (var file : files) {
            var expected = Files.readAllLines(file)
                                .stream()
                                .filter(line -> !line.isBlank() && !line.startsWith("--"))
                                .count();

            PARSER.parseScript(Files.readString(file))
                  .onFailureRun(() -> assertThat(false).as("%s did not parse", file.getFileName()).isTrue())
                  .onSuccess(statements -> assertThat(statements.size())
                          .as("statement count for %s", file.getFileName())
                          .isEqualTo((int) expected));
        }
    }

    private static List<Path> sqlFiles(Path root) throws Exception {
        try (var walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".sql"))
                       .filter(p -> !p.toString().contains("/target/"))
                       .filter(p -> !p.toString().contains("/.git/"))
                       .sorted(Comparator.comparing(p -> root.relativize(p).toString()))
                       .toList();
        }
    }

    private static Path repoRoot() {
        var dir = Path.of("").toAbsolutePath();

        while (dir != null && !Files.exists(dir.resolve(".git"))) {
            dir = dir.getParent();
        }

        return dir == null
               ? Path.of("").toAbsolutePath()
               : dir;
    }
}
