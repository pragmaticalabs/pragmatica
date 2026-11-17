package org.pragmatica.jbct.lint.cst;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.pragmatica.jbct.config.ConfigLoader;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


/// One lint run over files that may belong to several source roots (#2525).
///
/// A source root carries its own configuration, so a run holds one group per root: the root, its mode and lint
/// context, and whether it is a test root. [#lint] routes a file to the group of the deepest root that contains it
/// and lints it with that group's rules; a file under no root is a typed failure, never a silent skip. [#finish]
/// closes the run: it evaluates the run rules the mode filter leaves on, and renders one summary line per root, in
/// path order, with the root's mode and file count. `format-only` and `disabled` roots lint nothing, and their summary
/// line says so.
///
/// A test root is one the caller declares (Maven's `testSourceDirectory`), or one whose path relative to its module
/// directory holds `src/test` ([ConfigLoader#isTestRoot]); its files are counted separately in the summary.
///
/// **Finish discipline:** [#finish] is single-shot. A second call, and any [#lint] after the first, fail with
/// [LintRunError.General#RUN_FINISHED]: the report was already handed out, so a late file would be counted nowhere.
///
/// **Thread Safety:** [#lint] may be called concurrently (the per-root counters are atomic and the linters are
/// thread-safe); [#finish] should follow the last [#lint].
public final class LintRun {
    /// A source root and the lint context resolved for it.
    public record SourceRoot(Path path, LintContext context) {
        public static SourceRoot sourceRoot(Path path, LintContext context) {
            return new SourceRoot(path.toAbsolutePath().normalize(),
                                  context);
        }
    }

    /// What a finished run reports: the run diagnostics and the summary, one line per source root.
    public record RunReport(List<Diagnostic> diagnostics, List<String> summary) {
        public RunReport {
            diagnostics = List.copyOf(diagnostics);
            summary = List.copyOf(summary);
        }

        public static RunReport runReport(List<Diagnostic> diagnostics, List<String> summary) {
            return new RunReport(diagnostics, summary);
        }
    }

    private static final String FORMAT_ONLY_NOTE = ": format only, no rule run";
    private static final String DISABLED_NOTE = ": skipped, no format, no rule run";
    private static final String INFRASTRUCTURE_NOTE = ": structure checks off";

    private final List<Group> groups;
    private final AtomicBoolean finished = new AtomicBoolean();

    private LintRun(List<Group> groups) {
        this.groups = groups;
    }

    /// A run over the given roots; test roots are recognised by the `src/test` predicate alone.
    public static LintRun lintRun(List<SourceRoot> roots) {
        return lintRun(roots, Set.of());
    }

    /// A run over the given roots, with directories the caller declares as test roots: a root that is one of them or
    /// lies under one is a test root, besides any the `src/test` predicate finds.
    public static LintRun lintRun(List<SourceRoot> roots, Set<Path> declaredTestRoots) {
        var declared = declaredTestRoots.stream().map(directory -> directory.toAbsolutePath()
                                                                            .normalize()).toList();

        return new LintRun(roots.stream()
                                .map(root -> Group.group(root,
                                                         isTestRoot(root.path(),
                                                                    declared)))
                                .sorted(Comparator.comparing(group -> group.path()
                                                                           .toString()))
                                .toList());
    }

    /// Lint one file with the rules of its source root.
    public Result<List<Diagnostic>> lint(SourceFile file) {
        return finished.get()
               ? LintRunError.General.RUN_FINISHED.result()
               : route(file).flatMap(group -> group.lint(file));
    }

    /// Lint one file the caller has already parsed (`process` formats and lints on one CST), with the rules of its
    /// source root.
    public Result<List<Diagnostic>> lintParsed(Cursor tree, SourceFile file) {
        return finished.get()
               ? LintRunError.General.RUN_FINISHED.result()
               : route(file).map(group -> group.lintParsed(tree, file));
    }

    /// Close the run: the run diagnostics and the per-root summary. Refused the second time.
    public Result<RunReport> finish() {
        return finished.compareAndSet(false, true)
               ? success(RunReport.runReport(runDiagnostics(), summary()))
               : LintRunError.General.RUN_FINISHED.result();
    }

    private Result<Group> route(SourceFile file) {
        var path = file.path().toAbsolutePath().normalize();

        return Option.from(groups.stream()
                                 .filter(group -> group.holds(path))
                                 .max(Comparator.comparingInt(group -> group.path()
                                                                            .getNameCount())))
                     .toResult(LintRunError.OutsideSourceRoots.outsideSourceRoots(file.path()))
                     .map(Group::counted);
    }

    /// The run rules each root's mode leaves on, evaluated once every file has been seen.
    private List<Diagnostic> runDiagnostics() {
        return groups.stream()
                     .flatMap(group -> group.runRules()
                                            .stream())
                     .flatMap(LintRun::evaluate)
                     .toList();
    }

    /// `RunRule` declares no analysis entry point until the first run rule exists (P7), which adds it here.
    private static Stream<Diagnostic> evaluate(RunRule rule) {
        return Stream.empty();
    }

    private List<String> summary() {
        return groups.stream()
                     .map(Group::summaryLine)
                     .toList();
    }

    private static boolean isTestRoot(Path root, List<Path> declared) {
        return declared.stream()
                       .anyMatch(root::startsWith) || ConfigLoader.isTestRoot(root);
    }

    private record Group(Path path,
                         LintContext context,
                         boolean testRoot,
                         CstLinter linter,
                         boolean lintsFiles,
                         List<RunRule> runRules,
                         AtomicInteger files) {
        static Group group(SourceRoot root, boolean testRoot) {
            var rules = CstLinter.rulesFor(root.context().mode());

            return new Group(root.path(),
                             root.context(),
                             testRoot,
                             CstLinter.cstLinter(root.context()),
                             !rules.isEmpty(),
                             runRulesIn(rules),
                             new AtomicInteger());
        }

        private static List<RunRule> runRulesIn(List<LintRule> rules) {
            return rules.stream()
                        .filter(RunRule.class::isInstance)
                        .map(RunRule.class::cast)
                        .toList();
        }

        boolean holds(Path file) {
            return file.startsWith(path);
        }

        Group counted() {
            files.incrementAndGet();

            return this;
        }

        Result<List<Diagnostic>> lint(SourceFile file) {
            return lintsFiles
                   ? linter.lint(file)
                   : success(List.of());
        }

        List<Diagnostic> lintParsed(Cursor tree, SourceFile file) {
            return lintsFiles
                   ? linter.lintParsed(tree, file)
                   : List.of();
        }

        String summaryLine() {
            return "mode=" + context.mode()
                                    .token()
                 + " root=" + path
                 + " " + files.get()
                 + " files" + testPart() + note();
        }

        private String testPart() {
            return testRoot
                   ? ", " + files.get() + " test-root files"
                   : "";
        }

        private String note() {
            return switch (context.mode()) {
                case BUSINESS -> "";
                case INFRASTRUCTURE -> INFRASTRUCTURE_NOTE;
                case FORMAT_ONLY -> FORMAT_ONLY_NOTE;
                case DISABLED -> DISABLED_NOTE;
            };
        }
    }
}
