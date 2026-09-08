// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.clidocs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// Diffs this repository's documented CLI invocations against the command surface the shipped CLIs
/// actually define, in both directions.
///
/// Documentation drifts silently because nothing fails when it does. This makes it fail.
///
/// THE COUNTS ARE PART OF THE RESULT. A gate that passes having examined nothing is worse than no
/// gate, because it also supplies a reason never to look again — this repository has been burned by
/// exactly that shape (a Maven goal exiting green on an empty file set). Every run therefore prints
/// how many documents, how many invocations and how many commands it compared, and
/// [#gate_examinedSetIsNonEmpty] refuses to pass on an implausibly small corpus rather than only on
/// an empty one. A glob that stopped matching after a directory rename would otherwise turn this gate
/// green forever.
class CliDocsDriftTest {
    /// Floors, not exact counts. They exist to catch a corpus that quietly collapsed — a renamed docs
    /// directory, a fence-parser regression, a CLI whose subcommand list failed to register. They are
    /// set far below the values observed at the commit that introduced this gate (see the printed
    /// summary), so ordinary editing never trips them and a collapse always does.
    private static final int MINIMUM_DOCUMENTS = 100;
    private static final int MINIMUM_INVOCATIONS = 300;
    private static final int MINIMUM_COMMANDS = 100;

    /// Anchored on the repository root, not on the working directory. Surefire happens to run with
    /// `${basedir}` as its working directory, but a gate whose corpus and whose waiver are found only
    /// when the caller stands in the right place reports different results to CI, to a full-reactor
    /// build and to an IDE. [DocCorpus#repositoryRoot] is derived from this class's own code-source
    /// location, so all three agree.
    private static final Path MODULE = DocCorpus.repositoryRoot().resolve("cli-docs-gate");
    private static final Path BASELINE = MODULE.resolve("src/test/resources/undocumented-commands.txt");
    private static final Path WAIVER = MODULE.resolve("src/test/resources/known-doc-drift.txt");

    private static List<Path> documents;
    private static DocScanner.ScanResult scan;
    private static Map<String, CliSurface.Node> aether;
    private static Map<String, CliSurface.Node> jbct;

    @BeforeAll
    static void buildSurfaceAndCorpus() {
        documents = DocCorpus.markdownFiles();
        scan = DocScanner.scan(documents);
        aether = CliSurface.flatten(CliSurface.aetherCli());
        jbct = CliSurface.flatten(CliSurface.jbctCli());

        System.out.println("""
                           cli-docs-gate corpus
                             repository root ......... %s
                             documents scanned ....... %d Markdown file(s)
                             fenced shell blocks ..... %d read, %d skipped by language
                             inline code spans ....... %d
                             invocations extracted ... %d
                             commands enumerated ..... %d (aether %d, jbct %d)
                             excluded path fragments . %s
                             shell fence languages ... %s
                           """.formatted(DocCorpus.repositoryRoot(),
                                         scan.filesScanned(),
                                         scan.fencedBlocksRead(),
                                         scan.fencedBlocksSkippedByLanguage(),
                                         scan.inlineSpansRead(),
                                         scan.invocations().size(),
                                         aether.size() + jbct.size(),
                                         aether.size(),
                                         jbct.size(),
                                         DocCorpus.describeExclusions(),
                                         new TreeSet<>(DocScanner.shellLanguages())));

        writeInventory();
    }

    /// The instrument check, run before any result from the instrument is believed.
    @Test
    void gate_examinedSetIsNonEmpty() {
        assertTrue(scan.filesScanned() >= MINIMUM_DOCUMENTS,
                   "cli-docs-gate examined " + scan.filesScanned() + " Markdown file(s), below the floor of "
                   + MINIMUM_DOCUMENTS + ". The corpus has collapsed — a renamed documentation directory, or an "
                   + "exclusion in DocCorpus that now matches far more than intended. A green result from this "
                   + "gate would be meaningless until it is fixed.");

        assertTrue(scan.invocations().size() >= MINIMUM_INVOCATIONS,
                   "cli-docs-gate extracted " + scan.invocations().size() + " CLI invocation(s) from "
                   + scan.filesScanned() + " file(s), below the floor of " + MINIMUM_INVOCATIONS
                   + ". The extractor has stopped matching — most likely the fence parser or the binary-name "
                   + "recognition in DocScanner. The gate cannot report drift it never read.");

        assertTrue(aether.size() + jbct.size() >= MINIMUM_COMMANDS,
                   "cli-docs-gate enumerated " + (aether.size() + jbct.size()) + " command(s), below the floor of "
                   + MINIMUM_COMMANDS + ". A CommandSpec tree failed to build — check that AetherCli and "
                   + "JbctCommand still register their subcommands.");

        assertTrue(scan.fencedBlocksRead() > 0, "No fenced shell blocks were read at all.");
    }

    /// DIRECTION A — the docs lie to the user. Highest severity: a reader copies the line and it fails.
    ///
    /// Gated against a checked-in WAIVER rather than at zero, for the same reason as direction B: the
    /// drift predates the gate, and folding a hundred documentation edits into the commit that
    /// introduces the check would produce a diff nobody can review. The waiver is not a mute button —
    /// it is the drift report, kept in the repository where it can be worked off:
    ///
    ///   - a finding NOT in the waiver fails the build, so drift cannot grow;
    ///   - a waiver entry that matches NOTHING fails the build, so a fixed document forces its entry
    ///     to be deleted and the list can only shrink.
    ///
    /// Entries are keyed by repository-relative path plus the drift itself, never by line number —
    /// line numbers here are tree-scoped and shift under any edit above them.
    @Test
    void documentedInvocations_existInTheRealCommandTree() {
        var findings = new java.util.LinkedHashMap<String, String>();

        for (var invocation : scan.invocations()) {
            if (DocScanner.wrongBinaries().contains(invocation.binary())) {
                continue;
            }

            var resolution = CliSurface.resolve(rootFor(invocation), invocation.tokens());

            if (resolution.clean()) {
                continue;
            }

            if (resolution.unknownSubcommand() != null) {
                var key = DocCorpus.relative(invocation.file()) + "\tcommand: " + resolution.resolved().path()
                          + " " + resolution.unknownSubcommand();

                findings.putIfAbsent(key, invocation.location() + "  no such command: '" + resolution.resolved().path()
                                          + " " + resolution.unknownSubcommand() + "'\n      " + invocation.raw());
            }

            for (var option : resolution.unknownOptions()) {
                var key = DocCorpus.relative(invocation.file()) + "\toption: " + option + " on "
                          + resolution.resolved().path();

                findings.putIfAbsent(key, invocation.location() + "  no such option: '" + option + "' on '"
                                          + resolution.resolved().path() + "'\n      " + invocation.raw());
            }
        }

        writeLines("drift-keys.txt", new TreeSet<>(findings.keySet()));

        var waiver = readKeyList(WAIVER);
        var unwaived = new java.util.LinkedHashMap<>(findings);

        waiver.forEach(unwaived::remove);

        var stale = new TreeSet<>(waiver);

        stale.removeAll(findings.keySet());

        var problems = new ArrayList<String>();

        if (!unwaived.isEmpty()) {
            problems.add("Documentation cites " + unwaived.size() + " CLI invocation element(s) the shipped CLI "
                         + "does not define, and that are not waived. Each is a line a reader can copy and watch "
                         + "fail:\n\n" + String.join("\n", unwaived.values())
                         + "\n\nFix the document, or — if it is deliberate — add the matching line from "
                         + "cli-docs-gate/target/cli-docs-gate/drift-keys.txt to " + WAIVER + " with a reason.");
        }

        if (!stale.isEmpty()) {
            problems.add("Waiver entries in " + DocCorpus.relative(WAIVER) + " that no longer match any drift (" + stale.size()
                         + ") — the documentation was fixed, or the file was renamed. Delete them; this list is "
                         + "only allowed to shrink:\n  " + String.join("\n  ", stale));
        }

        if (!problems.isEmpty()) {
            fail(String.join("\n\n", problems) + "\n\nFull inventory: cli-docs-gate/target/cli-docs-gate/");
        }
    }

    /// The shipped binaries are `aether` and `jbct`. A fenced line starting with `aether-cli` or
    /// `jbct-cli` names a binary that does not exist.
    @Test
    void documentedInvocations_useShippedBinaryNames() {
        var findings = scan.invocations()
                           .stream()
                           .filter(invocation -> DocScanner.wrongBinaries().contains(invocation.binary()))
                           .map(invocation -> invocation.location() + "  '" + invocation.binary()
                                              + "' is not a shipped binary\n      " + invocation.raw())
                           .toList();

        if (!findings.isEmpty()) {
            fail("Documentation invokes a binary name that is not shipped (the executables are `aether` and "
                 + "`jbct`):\n\n" + String.join("\n", findings));
        }
    }

    /// DIRECTION B — a shipped surface nobody can find.
    ///
    /// Gated against a checked-in baseline rather than at zero, because the existing gap is large and
    /// absorbing it into this change would produce an unreviewable diff. The baseline is SELF-CLEANING:
    /// the test fails if an entry has since been documented, and fails if an entry is no longer a real
    /// command. It can therefore only shrink, and it cannot rot into a list of names that mean nothing.
    @Test
    void everyCommand_isDocumentedOrExplicitlyBaselined() {
        var documented = documentedCommandPaths();
        var real = new TreeSet<String>();

        aether.forEach((path, node) -> {
            if (!node.hidden()) {
                real.add(path);
            }
        });
        jbct.forEach((path, node) -> {
            if (!node.hidden()) {
                real.add(path);
            }
        });

        var baseline = readBaseline();
        var undocumented = new TreeSet<>(real);

        undocumented.removeAll(documented);

        writeLines("undocumented-commands-candidates.txt", undocumented);

        var newlyUndocumented = new TreeSet<>(undocumented);

        newlyUndocumented.removeAll(baseline);

        var nowDocumented = new TreeSet<>(baseline);

        nowDocumented.retainAll(documented);

        var noLongerReal = new TreeSet<>(baseline);

        noLongerReal.removeAll(real);

        var problems = new ArrayList<String>();

        if (!newlyUndocumented.isEmpty()) {
            problems.add("Shipped commands that no documentation mentions, and that are not baselined ("
                         + newlyUndocumented.size() + "):\n  " + String.join("\n  ", newlyUndocumented)
                         + "\n  -> document them, or add them to " + DocCorpus.relative(BASELINE) + " with a reason.");
        }

        if (!nowDocumented.isEmpty()) {
            problems.add("Baseline entries that ARE now documented (" + nowDocumented.size()
                         + ") — delete them from " + DocCorpus.relative(BASELINE) + " so the baseline keeps shrinking:\n  "
                         + String.join("\n  ", nowDocumented));
        }

        if (!noLongerReal.isEmpty()) {
            problems.add("Baseline entries that are no longer commands (" + noLongerReal.size()
                         + ") — the command was renamed or removed; delete them from " + DocCorpus.relative(BASELINE) + ":\n  "
                         + String.join("\n  ", noLongerReal));
        }

        if (!problems.isEmpty()) {
            fail(String.join("\n\n", problems));
        }
    }

    /// The gate's own blind spots, printed on every run so their size is a known number.
    ///
    /// A command declaring BOTH subcommands and positional parameters cannot distinguish an invented
    /// subcommand from a positional argument, so drift under it is not reported. Naming them here is
    /// the difference between a stated limit and an unknown one.
    @Test
    void gate_blindSpots_areEnumerated() {
        var ambiguous = new TreeSet<String>();

        aether.forEach((path, node) -> addIfAmbiguous(ambiguous, path, node));
        jbct.forEach((path, node) -> addIfAmbiguous(ambiguous, path, node));

        System.out.println("cli-docs-gate blind spots — commands with BOTH subcommands and positionals, where an "
                           + "invented subcommand reads as an argument and is NOT reported (" + ambiguous.size()
                           + "):\n  " + String.join("\n  ", ambiguous));
        System.out.println("cli-docs-gate also does not validate: multi-character single-dash tokens (ambiguous "
                           + "between clustered short options and a long option), option VALUES, positional "
                           + "argument shapes, or fenced blocks whose language is outside "
                           + new TreeSet<>(DocScanner.shellLanguages()) + ".");
    }

    private static void addIfAmbiguous(Set<String> out, String path, CliSurface.Node node) {
        if (!node.spec().subcommands().isEmpty() && !node.spec().positionalParameters().isEmpty()) {
            out.add(path);
        }
    }

    /// Every command path a document actually reaches, plus all of its prefixes: documenting
    /// `aether cluster bootstrap` documents the existence of `aether cluster` too.
    private static Set<String> documentedCommandPaths() {
        var paths = new LinkedHashSet<String>();

        for (var invocation : scan.invocations()) {
            if (DocScanner.wrongBinaries().contains(invocation.binary())) {
                continue;
            }

            var resolution = CliSurface.resolve(rootFor(invocation), invocation.tokens());

            for (var node = resolution.resolved(); node != null; node = node.parent()) {
                paths.add(node.path());
            }
        }

        return paths;
    }

    private static CliSurface.Node rootFor(DocScanner.Invocation invocation) {
        return "aether".equals(invocation.binary())
               ? aether.get("aether")
               : jbct.get("jbct");
    }

    private static Set<String> readBaseline() {
        return readKeyList(BASELINE);
    }

    private static Set<String> readKeyList(Path file) {
        if (!Files.isRegularFile(file)) {
            return Set.of();
        }

        try {
            return Files.readAllLines(file)
                        .stream()
                        .map(String::strip)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeLines(String name, Iterable<String> lines) {
        var out = MODULE.resolve("target/cli-docs-gate");

        try {
            Files.createDirectories(out);
            Files.write(out.resolve(name), lines);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// Written on every run so a failure can be investigated by reading the artifact rather than by
    /// re-running the search.
    private static void writeInventory() {
        var out = MODULE.resolve("target/cli-docs-gate");

        try {
            Files.createDirectories(out);
            Files.write(out.resolve("commands.txt"),
                        java.util.stream.Stream.concat(aether.keySet().stream(), jbct.keySet().stream()).sorted().toList());
            Files.write(out.resolve("invocations.txt"),
                        scan.invocations()
                            .stream()
                            .map(invocation -> invocation.location() + "\t" + invocation.origin() + "\t"
                                               + invocation.binary() + " " + String.join(" ", invocation.tokens()))
                            .toList());
            Files.write(out.resolve("documents.txt"), documents.stream().map(Path::toString).toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
