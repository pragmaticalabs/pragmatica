package org.pragmatica.jbct.lint.cst;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.pragmatica.jbct.config.ConfigLoader;
import org.pragmatica.jbct.config.JbctMode;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.DiagnosticSeverity;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.RootResolver.ModeSource;
import org.pragmatica.jbct.lint.cst.RootResolver.ResolvedRoot;
import org.pragmatica.jbct.lint.cst.RootResolver.RootPlan;
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

    /// What one root's run came to: the mode and where it came from, the file counts (examined by rules, formatted or
    /// format-checked, skipped by mode), the diagnostics by severity, and whether the root fails the run.
    public record RootCounts(Path root,
                             JbctMode mode,
                             ModeSource modeSource,
                             Option<String> basePackage,
                             boolean testRoot,
                             int files,
                             int examined,
                             int formatted,
                             int skipped,
                             int errors,
                             int warnings,
                             boolean fails) {
        public static RootCounts rootCounts(Path root,
                                            JbctMode mode,
                                            ModeSource modeSource,
                                            Option<String> basePackage,
                                            boolean testRoot,
                                            int files,
                                            int examined,
                                            int formatted,
                                            int skipped,
                                            int errors,
                                            int warnings,
                                            boolean fails) {
            return new RootCounts(root,
                                  mode,
                                  modeSource,
                                  basePackage,
                                  testRoot,
                                  files,
                                  examined,
                                  formatted,
                                  skipped,
                                  errors,
                                  warnings,
                                  fails);
        }

        /// How many of this root's findings count against it, in the failure message's words.
        String describe() {
            return root + ": " + errors + " error(s), " + warnings + " warning(s)";
        }
    }

    /// What a finished run reports: the run diagnostics, the summary (one line per source root), the per-root counts
    /// and, when any root fails, the failure message naming those roots with their counts. A run built from the
    /// legacy root list has no per-root counts and never fails here: its front ends judge diagnostics themselves.
    public record RunReport(List<Diagnostic> diagnostics,
                            List<String> summary,
                            List<RootCounts> roots,
                            Option<String> failure) {
        public RunReport {
            diagnostics = List.copyOf(diagnostics);
            summary = List.copyOf(summary);
            roots = List.copyOf(roots);
        }

        public static RunReport runReport(List<Diagnostic> diagnostics, List<String> summary) {
            return new RunReport(diagnostics, summary, List.of(), Option.none());
        }

        public static RunReport runReport(List<Diagnostic> diagnostics, List<String> summary, List<RootCounts> roots) {
            return new RunReport(diagnostics, summary, roots, failureOf(roots));
        }

        /// Whether the run fails: some root has errors, or warnings under its own `failOnWarning` (or the flag).
        public boolean fails() {
            return failure.isPresent();
        }

        /// Whether no root ran a rule: a run in which this holds must not print a compliance pass.
        public boolean ranNoRule() {
            return roots.stream()
                        .allMatch(root -> root.examined() == 0);
        }

        private static Option<String> failureOf(List<RootCounts> roots) {
            var failing = roots.stream().filter(RootCounts::fails).map(RootCounts::describe).toList();

            return failing.isEmpty()
                   ? Option.none()
                   : Option.some("JBCT failed in " + failing.size() + " source root(s): " + String.join("; ", failing));
        }
    }

    private static final String FORMAT_ONLY_NOTE = "format only, no rule run";
    private static final String DISABLED_NOTE = "skipped, no format, no rule run";
    private static final String INFRASTRUCTURE_NOTE = "structure checks off";

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

        return new LintRun(ordered(roots.stream()
                                        .map(root -> Group.group(root,
                                                                 isTestRoot(root.path(),
                                                                            declared),
                                                                 Option.none()))));
    }

    private static List<Group> ordered(Stream<Group> groups) {
        return groups.sorted(Comparator.comparing(group -> group.path()
                                                                .toString()))
                     .toList();
    }

    /// A run over the roots a [RootResolver] decided: each root keeps the config, mode and provenance the resolver
    /// gave it, and [#finish] reports per-root counts and the verdict.
    public static LintRun lintRun(RootPlan plan) {
        return new LintRun(ordered(plan.roots()
                                       .stream()
                                       .map(root -> Group.group(SourceRoot.sourceRoot(root.path(),
                                                                                      root.context()),
                                                                root.testRoot(),
                                                                Option.some(Detail.detail(root, plan))))));
    }

    /// Lint one file with the rules of its source root; a root whose mode runs no rule does not parse it.
    public Result<List<Diagnostic>> lint(SourceFile file) {
        return finished.get()
               ? LintRunError.General.RUN_FINISHED.result()
               : route(file.path()).flatMap(group -> group.lint(file));
    }

    /// Lint a file by path: it is opened only when its root's mode runs rules, so a `format-only` or `disabled` root's
    /// files are never read for linting.
    public Result<List<Diagnostic>> lintFile(Path file) {
        return finished.get()
               ? LintRunError.General.RUN_FINISHED.result()
               : route(file).flatMap(group -> group.lintFile(file));
    }

    /// Claim the formatting of a file: true when its root's mode formats (every mode but `disabled`), and the file is
    /// then counted as formatted or format-checked; false when the root skips it, and the file is counted as skipped.
    /// Ask before opening the file.
    public Result<Boolean> claimFormat(Path file) {
        return finished.get()
               ? LintRunError.General.RUN_FINISHED.result()
               : route(file).map(group -> group.claimFormat(file));
    }

    /// Lint one file the caller has already parsed (`process` formats and lints on one CST), with the rules of its
    /// source root.
    public Result<List<Diagnostic>> lintParsed(Cursor tree, SourceFile file) {
        return finished.get()
               ? LintRunError.General.RUN_FINISHED.result()
               : route(file.path()).map(group -> group.lintParsed(tree, file));
    }

    /// Close the run: the run diagnostics and the per-root summary. Refused the second time.
    public Result<RunReport> finish() {
        return finished.compareAndSet(false, true)
               ? success(report())
               : LintRunError.General.RUN_FINISHED.result();
    }

    private RunReport report() {
        var diagnostics = runDiagnostics();

        return groups.stream()
                     .anyMatch(Group::detailed)
               ? RunReport.runReport(diagnostics, summary(), counts())
               : RunReport.runReport(diagnostics, summary());
    }

    private List<RootCounts> counts() {
        return groups.stream()
                     .flatMap(group -> group.counts()
                                            .stream())
                     .toList();
    }

    private Result<Group> route(Path file) {
        var path = file.toAbsolutePath().normalize();

        return Option.from(groups.stream()
                                 .filter(group -> group.holds(path))
                                 .max(Comparator.comparingInt(group -> group.path()
                                                                            .getNameCount())))
                     .toResult(LintRunError.OutsideSourceRoots.outsideSourceRoots(file))
                     .map(group -> group.counted(path));
    }

    /// The run rules each root's mode leaves on, evaluated once every file has been seen.
    private List<Diagnostic> runDiagnostics() {
        return groups.stream()
                     .flatMap(group -> group.runDiagnostics()
                                            .stream())
                     .toList();
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

    /// What a plan-built group knows beyond the legacy root: the resolved root and the invocation facts.
    private record Detail(ResolvedRoot root, boolean failOnWarningFlag, String testRootRule) {
        static Detail detail(ResolvedRoot root, RootPlan plan) {
            return new Detail(root, plan.failOnWarning(), plan.testRootRule());
        }

        boolean failsOnWarning() {
            return failOnWarningFlag || root.config()
                                            .lint()
                                            .failOnWarning();
        }
    }

    private record Group(Path path,
                         LintContext context,
                         boolean testRoot,
                         CstLinter linter,
                         boolean lintsFiles,
                         List<RunRule> runRules,
                         AtomicInteger files,
                         Set<Path> seen,
                         Set<Path> examined,
                         Set<Path> formatted,
                         AtomicInteger errors,
                         AtomicInteger warnings,
                         Option<Detail> detail) {
        static Group group(SourceRoot root, boolean testRoot, Option<Detail> detail) {
            var rules = CstLinter.rulesFor(root.context().mode());

            return new Group(root.path(),
                             root.context(),
                             testRoot,
                             CstLinter.cstLinter(root.context()),
                             !rules.isEmpty(),
                             runRulesIn(rules),
                             new AtomicInteger(),
                             ConcurrentHashMap.newKeySet(),
                             ConcurrentHashMap.newKeySet(),
                             ConcurrentHashMap.newKeySet(),
                             new AtomicInteger(),
                             new AtomicInteger(),
                             detail);
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

        boolean detailed() {
            return detail.isPresent();
        }

        Group counted(Path file) {
            files.incrementAndGet();
            seen.add(file);

            return this;
        }

        Result<List<Diagnostic>> lint(SourceFile file) {
            return lintsFiles
                   ? linter.lint(file)
                           .map(found -> examinedWith(file.path(),
                                                      found))
                   : success(List.of());
        }

        Result<List<Diagnostic>> lintFile(Path file) {
            return lintsFiles
                   ? SourceFile.sourceFile(file).flatMap(source -> lint(source))
                   : success(List.of());
        }

        List<Diagnostic> lintParsed(Cursor tree, SourceFile file) {
            return lintsFiles
                   ? examinedWith(file.path(), linter.lintParsed(tree, file))
                   : List.of();
        }

        boolean claimFormat(Path file) {
            return context.mode() != JbctMode.DISABLED && formattedFile(file);
        }

        private boolean formattedFile(Path file) {
            formatted.add(file.toAbsolutePath().normalize());

            return true;
        }

        /// Record that a file was examined by rules and count its findings against this root.
        private List<Diagnostic> examinedWith(Path file, List<Diagnostic> found) {
            examined.add(file.toAbsolutePath().normalize());

            return tallied(found);
        }

        private List<Diagnostic> tallied(List<Diagnostic> found) {
            errors.addAndGet(severityCount(found, DiagnosticSeverity.ERROR));
            warnings.addAndGet(severityCount(found, DiagnosticSeverity.WARNING));

            return found;
        }

        private static int severityCount(List<Diagnostic> found, DiagnosticSeverity severity) {
            return (int) found.stream()
                              .filter(diagnostic -> diagnostic.severity() == severity)
                              .count();
        }

        /// `RunRule` declares no analysis entry point until the first run rule exists (P7), which adds it here.
        List<Diagnostic> runDiagnostics() {
            return tallied(runRules.stream().flatMap(Group::evaluate).toList());
        }

        private static Stream<Diagnostic> evaluate(RunRule rule) {
            return Stream.empty();
        }

        int skipped() {
            return (int) seen.stream()
                             .filter(file -> !examined.contains(file) && !formatted.contains(file))
                             .count();
        }

        Option<RootCounts> counts() {
            return detail.map(this::countsOf);
        }

        private RootCounts countsOf(Detail known) {
            return RootCounts.rootCounts(path,
                                         context.mode(),
                                         known.root().modeSource(),
                                         context.basePackage(),
                                         testRoot,
                                         seen.size(),
                                         examined.size(),
                                         formatted.size(),
                                         skipped(),
                                         errors.get(),
                                         warnings.get(),
                                         errors.get() > 0 || warnings.get() > 0 && known.failsOnWarning());
        }

        String summaryLine() {
            return detail.map(this::detailedLine)
                         .or(this::legacyLine);
        }

        private String legacyLine() {
            return "mode=" + context.mode()
                                    .token()
                 + " root=" + path
                 + " " + files.get()
                 + " files" + testPart() + legacyNote();
        }

        private String detailedLine(Detail known) {
            return "mode=" + context.mode()
                                    .token()
                 + " (" + known.root()
                               .modeSource()
                               .describe()
                 + ")"
                 + " root=" + path + context.basePackage()
                                            .map(base -> " basePackage=" + base)
                                            .or("")
                 + ": " + examined.size()
                 + " examined by rules, " + formatted.size()
                 + " formatted or format-checked, " + skipped()
                 + " skipped by mode" + (testRoot
                                         ? ", " + seen.size() + " test-root files"
                                         : "")
                 + "; test-root rule: " + known.testRootRule() + noteText().map(text -> "; " + text)
                                                                         .or("");
        }

        private String testPart() {
            return testRoot
                   ? ", " + files.get() + " test-root files"
                   : "";
        }

        private String legacyNote() {
            return noteText().map(text -> ": " + text)
                           .or("");
        }

        private Option<String> noteText() {
            return switch (context.mode()) {
                case BUSINESS -> Option.none();
                case INFRASTRUCTURE -> Option.some(INFRASTRUCTURE_NOTE);
                case FORMAT_ONLY -> Option.some(FORMAT_ONLY_NOTE);
                case DISABLED -> Option.some(DISABLED_NOTE);
            };
        }
    }
}
