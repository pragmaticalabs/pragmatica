// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.parser;

import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/// The repo-wide SQL corpus walk shared by every corpus-level sensor in this module. ONE
/// implementation on purpose: the isRegularFile filter below was first added to a single caller
/// (CorpusParseTest, the #598 CI follow-up) and its absent sibling copy broke the next full build —
/// a shared mechanism must be fixed at the mechanism, not at a caller.
///
/// The walk PRUNES build and VCS output AT THE WALKER rather than filtering it out of the results
/// (#980). Filtering afterwards cannot help, and the distinction is the whole defect: the race is
/// between enumeration and open, and a downstream `Stream.filter` runs only once `Files.walk` has
/// already opened the directory and listed it. `.mvn/maven.config` carries `-T 1C`, so CI runs a
/// module-parallel reactor in which sibling modules create and delete files under
/// `*/target/surefire-reports/` while this walk is running — a file present at enumeration and gone
/// at open failed the build INSIDE this module, naming a module the author had never touched.
///
/// What the pruning guarantees, precisely: a pruned subtree is never opened, because
/// [SqlCollector#preVisitDirectory] returns [FileVisitResult#SKIP_SUBTREE]. It does NOT avoid one
/// `opendir` on the pruned directory itself — `walkFileTree` opens a directory's stream BEFORE
/// consulting `preVisitDirectory` (verified empirically, not read off the javadoc: a trap placed AT
/// `target` still throws, a trap placed INSIDE it no longer does). That residue is harmless because
/// `*/target` is created once per module and not removed mid-reactor, whereas its
/// `surefire-reports/` contents churn continuously.
///
/// Measured on this tree before the change: `Files.walk` enumerated 36,893 entries, of which 29,519
/// (80.0%) sat under `target/` or `.m2-local/` only to be discarded by the filters — 5,194 directory
/// opens the corpus never needed. The local figure UNDERSTATES CI, where `.git` is a real directory
/// rather than this worktree's pointer file.
final class SqlCorpus {
    /// Directory names whose subtrees cannot hold corpus SQL and ARE written concurrently by the
    /// reactor: Maven output, git's object store, and the tree-local Maven repository that this
    /// workspace's `.mvn/maven.config` places inside the repo root. Pruning `.m2-local` is not
    /// hygiene either — it contributed 1,932 of those directory opens, and nothing excluded it
    /// before.
    ///
    /// Controlled before pruning: of the repository's tracked paths, zero contain a `target/` or
    /// `.m2-local/` component (the same grep returned 3,862 for `/src/`), so no tracked `.sql` file
    /// can be lost to this set.
    private static final Set<String> PRUNED = Set.of("target", ".git", ".m2-local");

    private SqlCorpus() {}

    static List<Path> sqlFiles(Path root) throws Exception {
        var collector = new SqlCollector(root);

        Files.walkFileTree(root, collector);

        return collector.files()
                        .stream()
                        .sorted(Comparator.comparing(p -> root.relativize(p).toString()))
                        .toList();
    }

    /// `visitFileFailed` is deliberately NOT overridden. [SimpleFileVisitor] rethrows, so an
    /// unreadable file in the SOURCE space still fails the build loudly: #980 is a fix for walking
    /// where it had no business walking, NOT a licence to go blind about the corpus it does own.
    private static final class SqlCollector extends SimpleFileVisitor<Path> {
        private final Path root;
        private final List<Path> files = new ArrayList<>();

        private SqlCollector(Path root) {
            this.root = root;
        }

        private List<Path> files() {
            return files;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            return isPruned(dir)
                   ? FileVisitResult.SKIP_SUBTREE
                   : FileVisitResult.CONTINUE;
        }

        /// The root is never pruned even when its own name matches: walking what the caller asked
        /// for and finding nothing is honest, and it is also what keeps `getFileName()` — null only
        /// for a filesystem root — off this path.
        private boolean isPruned(Path dir) {
            return !dir.equals(root) && PRUNED.contains(dir.getFileName().toString());
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (isSqlFile(file)) {
                files.add(file);
            }

            return FileVisitResult.CONTINUE;
        }

        /// `Files.isRegularFile` rather than `attrs.isRegularFile()`, preserving the previous
        /// link-following semantics exactly. It stays load-bearing, not hygiene: JRE dist output
        /// contains DIRECTORIES named `java.sql` (package-shaped legal/ dirs), and a name-suffix
        /// match alone turns them into an IOException mid-corpus on any machine with a prior dist
        /// build.
        private static boolean isSqlFile(Path file) {
            return Files.isRegularFile(file) && file.getFileName().toString().endsWith(".sql");
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
