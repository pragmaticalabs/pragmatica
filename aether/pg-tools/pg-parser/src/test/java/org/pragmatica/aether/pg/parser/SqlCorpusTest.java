// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.parser;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Pins the #980 property: [SqlCorpus] must not walk INTO build or VCS output, because under CI's
/// module-parallel reactor those directories are being written while the walk runs.
///
/// The instrument is an UNREADABLE directory rather than a timing window, which is what makes the
/// pin deterministic. A race cannot be reproduced on demand; "the walk opened a directory it should
/// never have opened" can be, and it is the same mechanism — the old code failed while opening
/// something beneath `target/`, and an unreadable directory fails at exactly that point every time.
///
/// [#sqlFiles_propagatesFailure_whenTheSourceTreeCannotBeRead] is the anti-blindness control, and it
/// is the reason this class is three tests rather than two: a "fix" that swallowed walk failures
/// would pass the first two and is precisely the wrong cure.
class SqlCorpusTest {
    private static final String SQL = "SELECT 1;\n";
    private final List<Path> trapped = new ArrayList<>();

    /// Restores every trap so the @TempDir cleanup cannot fail on a directory it may not read.
    @AfterEach
    void disarmTraps() {
        trapped.forEach(dir -> dir.toFile().setReadable(true, true));
        trapped.clear();
    }

    @Test
    void sqlFiles_doesNotEnterBuildOutput_whenItsContentsCannotBeRead(@TempDir Path root) throws Exception {
        var corpusFile = write(root.resolve("module/src/main/resources/schema/live.sql"));
        var reports = Files.createDirectories(root.resolve("module/target/surefire-reports"));

        write(reports.resolve("stale.sql"));
        arm(reports);

        assumeTrue(isUnreadable(reports),
                   "trap not armed (running as root?): an unreadable directory is this test's instrument");

        assertThat(SqlCorpus.sqlFiles(root))
                .as("the walk must never descend beneath target/, so an unreadable "
                    + "target/surefire-reports cannot fail it")
                .containsExactly(corpusFile);
    }

    /// Permission-independent, so it pins the corpus contract on every machine including one running
    /// as root. It reddens the pre-#980 code too, for a second and independent reason: `.m2-local`
    /// was excluded by NOTHING there, and the tree-local Maven repository lives inside the repo root.
    @Test
    void sqlFiles_excludesBuildOutputAndLocalRepository(@TempDir Path root) throws Exception {
        var corpusFile = write(root.resolve("module/src/test/resources/corpus/live.sql"));

        write(root.resolve("module/target/classes/schema/copied.sql"));
        write(root.resolve(".m2-local/org/example/artifact/1.0.0/installed.sql"));
        write(root.resolve(".git/objects/looks-like.sql"));

        assertThat(SqlCorpus.sqlFiles(root))
                .as("only the source-tree file belongs to the corpus")
                .containsExactly(corpusFile);
    }

    /// The walk owns the source space and must stay loud about it. Pruning build output is not a
    /// licence to swallow an unreadable corpus directory: that would shrink the corpus silently,
    /// which is the failure [CorpusParseTest] exists to catch. The mutation this pins is a
    /// `visitFileFailed` override returning CONTINUE, which reddens this test and leaves the other
    /// two green.
    ///
    /// It ALSO pins the exception type, and that is worth stating because it is a deliberate change
    /// rather than a preserved property: the old `Files.walk` surfaced a walk failure as
    /// [java.io.UncheckedIOException], which is a `RuntimeException` and therefore NOT an
    /// [IOException]. `walkFileTree` throws the [IOException] itself. Both fail the build, so this
    /// test's red against the pre-#980 code is attributable to the type and not to blindness - hence
    /// the separate mutation named above, which is what actually validates it as an anti-blindness
    /// pin. `sqlFiles` already declared `throws Exception`, so no caller changes.
    @Test
    void sqlFiles_propagatesFailure_whenTheSourceTreeCannotBeRead(@TempDir Path root) throws Exception {
        var schema = Files.createDirectories(root.resolve("module/src/main/resources/schema"));

        write(schema.resolve("live.sql"));
        arm(schema);

        assumeTrue(isUnreadable(schema),
                   "trap not armed (running as root?): an unreadable directory is this test's instrument");

        assertThatThrownBy(() -> SqlCorpus.sqlFiles(root))
                .as("an unreadable directory in the SOURCE space must still fail the build")
                .isInstanceOf(IOException.class)
                // Named, not merely thrown: "it failed" and "it failed for this reason" are
                // different observations, and only the second one is evidence.
                .hasMessageContaining("schema");
    }

    private static Path write(Path file) throws Exception {
        Files.createDirectories(file.getParent());

        return Files.writeString(file, SQL);
    }

    private void arm(Path dir) {
        dir.toFile().setReadable(false, false);
        trapped.add(dir);
    }

    /// Measures the property the trap depends on rather than asserting it: `canRead` reports true for
    /// root, and a trap that is not armed would make the test pass VACUOUSLY, which is worse than no
    /// test at all.
    private static boolean isUnreadable(Path dir) {
        try (DirectoryStream<Path> ignored = Files.newDirectoryStream(dir)) {
            return false;
        } catch (IOException expected) {
            return true;
        }
    }
}
