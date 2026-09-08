// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.clidocs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/// Which Markdown files the drift gate reads, and — just as load-bearing — which it does not.
///
/// The corpus is defined as SWEEP EVERYTHING, MINUS AN EXPLICIT EXCLUSION LIST. That direction is
/// deliberate. A gate whose corpus is an include-list of globs (`docs/**`, `aether/docs/**`) goes
/// quietly green the day someone renames a directory: the glob stops matching, the sweep finds
/// nothing, and nothing fails. Defined as "every `*.md` under the repository root except these",
/// a rename cannot shrink the corpus — a new documentation directory is swept the moment it exists,
/// and only an entry added to [#EXCLUDED_SEGMENTS] can ever remove anything.
///
/// [CliDocsDriftTest] prints the resulting file count and refuses to pass on an implausibly small
/// one, so the corpus is reported rather than assumed.
final class DocCorpus {
    private DocCorpus() {}

    /// Path fragments that disqualify a Markdown file. Every entry is a decision with a reason, not a
    /// convenience:
    ///
    ///   - `target`, `.git`, `.m2-local`, `node_modules` — build output and VCS internals, not docs.
    ///   - `.internal` — the working notes under `aether/docs/.internal` (progress logs, audits,
    ///     website-correction drafts). They record what was true on the day they were written; they
    ///     are not instructions to a user, and holding them to today's command surface would make the
    ///     gate a history-rewriting tool.
    ///   - `archive` — same argument, stated by the directory name itself.
    ///   - `changelog.d` and `CHANGELOG.md` — a changelog's job is to describe a change, so it
    ///     legitimately names commands as they were. "Renamed `aether blueprint` to `aether
    ///     blueprints`" MUST keep saying `aether blueprint`. Holding a changelog to today's surface
    ///     would demand that it lie about the past.
    ///   - `specs/future` — proposals explicitly marked as not shipped. Their whole purpose is to name
    ///     a surface that does not exist yet.
    ///   - `src/test/resources` — fixtures. Some are deliberately malformed input for other tests.
    ///
    /// The single principle behind all of them: this gate reads documents whose job is to tell a
    /// reader what to type TODAY. It does not read records of what changed, what was proposed, or what
    /// used to be true.
    ///
    /// DELIBERATELY STILL IN: `aether/docs/specs/**` (minus `future/`) and `docs/rfc/**`. A design
    /// record for a feature that shipped is making a claim about the surface that shipped, and a
    /// command name in it that never existed is drift worth surfacing. Where such drift exists today
    /// it is enumerated in `known-doc-drift.txt`, not hidden by narrowing this corpus.
    ///
    /// EXCLUDED FROM THE EXCLUSIONS, deliberately: `aether/tests/integration/suites/**` (the suite
    /// READMEs are executable-by-hand operator instructions and drift there is real), the
    /// scaffolding template shipped inside `jbct-cli/src/main/resources/templates` (it lands in a
    /// user's project verbatim), and the repository's own `CLAUDE.md`/`CONTRIBUTING.md`.
    private static final List<String> EXCLUDED_SEGMENTS =
        List.of("/target/", "/.git/", "/.m2-local/", "/node_modules/", "/.internal/", "/archive/",
                "/changelog.d/", "/src/test/resources/", "/specs/future/");

    /// Excluded by FILE NAME rather than by directory, on the same principle as `changelog.d/`.
    private static final List<String> EXCLUDED_FILENAMES = List.of("CHANGELOG.md");

    /// Walked up from this class's own `target/test-classes`, which sits exactly two directories below
    /// the repository root (`<root>/cli-docs-gate/target/test-classes`). Deliberately NOT `user.dir`:
    /// that varies with the directory `mvn` was invoked from, and a gate whose corpus depends on the
    /// caller's shell is a gate that reports different numbers to CI and to a developer.
    static Path repositoryRoot() {
        return ROOT;
    }

    private static final Path ROOT = computeRepositoryRoot();

    private static Path computeRepositoryRoot() {
        var testClasses = codeSourceLocation();
        var root = testClasses.getParent().getParent().getParent();

        if (root == null || !Files.isRegularFile(root.resolve("pom.xml"))
            || !Files.isDirectory(root.resolve("aether"))
            || !Files.isDirectory(root.resolve("jbct"))) {
            throw new IllegalStateException(
                "DocCorpus.repositoryRoot() computed " + root + " from " + testClasses
                + ", which does not look like the pragmatica root (expected pom.xml plus aether/ and "
                + "jbct/ directories) — the three-levels-up assumption (target/test-classes -> "
                + "cli-docs-gate -> <root>) no longer holds; fix the walk-up count.");
        }

        return root;
    }

    private static Path codeSourceLocation() {
        try {
            return Path.of(DocCorpus.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /// Every Markdown file in the corpus, sorted so failure output is stable between runs.
    static List<Path> markdownFiles() {
        var root = repositoryRoot();

        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                       .filter(path -> path.getFileName().toString().endsWith(".md"))
                       .filter(DocCorpus::included)
                       .sorted()
                       .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean included(Path path) {
        var normalized = "/" + ROOT.relativize(path).toString().replace('\\', '/');

        return EXCLUDED_SEGMENTS.stream().noneMatch(normalized::contains)
               && !EXCLUDED_FILENAMES.contains(path.getFileName().toString());
    }

    static String describeExclusions() {
        return String.join(", ", EXCLUDED_SEGMENTS) + " and file(s) named " + String.join(", ", EXCLUDED_FILENAMES);
    }

    /// Repository-relative, forward-slashed — the form used as a waiver key, so a waiver written on one
    /// machine matches on another.
    static String relative(Path path) {
        return ROOT.relativize(path).toString().replace('\\', '/');
    }
}
