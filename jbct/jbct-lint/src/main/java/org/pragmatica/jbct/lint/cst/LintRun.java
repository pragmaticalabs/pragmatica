package org.pragmatica.jbct.lint.cst;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.jbct.config.ConfigLoader;
import org.pragmatica.jbct.config.JbctMode;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.DiagnosticSeverity;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.layer.LayerCoverage;
import org.pragmatica.jbct.lint.cst.RootResolver.ModeSource;
import org.pragmatica.jbct.lint.cst.RootResolver.ResolvedRoot;
import org.pragmatica.jbct.lint.cst.RootResolver.RootPlan;
import org.pragmatica.jbct.lint.cst.RunOutcome.Command;
import org.pragmatica.jbct.lint.cst.RunOutcome.FileResult;
import org.pragmatica.jbct.lint.cst.RunOutcome.FormatAction;
import org.pragmatica.jbct.lint.cst.RunOutcome.FormatOutcome;
import org.pragmatica.jbct.lint.cst.RunOutcome.LintOutcome;
import org.pragmatica.jbct.lint.cst.RunOutcome.Invocation;
import org.pragmatica.jbct.lint.cst.RunOutcome.Linted;
import org.pragmatica.jbct.lint.cst.RunOutcome.ParseFailed;
import org.pragmatica.jbct.lint.cst.RunOutcome.ReadFailed;
import org.pragmatica.jbct.lint.cst.RunOutcome.Rewritten;
import org.pragmatica.jbct.lint.cst.RunOutcome.RootResult;
import org.pragmatica.jbct.lint.cst.RunOutcome.RunResult;
import org.pragmatica.jbct.lint.cst.RunOutcome.SkippedByMode;
import org.pragmatica.jbct.lint.cst.RunOutcome.Tally;
import org.pragmatica.jbct.lint.cst.RunOutcome.Totals;
import org.pragmatica.jbct.lint.cst.RunOutcome.Unchanged;
import org.pragmatica.jbct.lint.cst.RunOutcome.Level;
import org.pragmatica.jbct.lint.cst.RunOutcome.Line;
import org.pragmatica.jbct.lint.cst.RunOutcome.Verdict;
import org.pragmatica.jbct.lint.cst.RunOutcome.Withheld;
import org.pragmatica.jbct.lint.cst.RunOutcome.WouldChange;
import org.pragmatica.jbct.lint.cst.RunOutcome.WriteFailed;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.Java25Parser;
import org.pragmatica.jbct.score.ScoreResult;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Cause;
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
    /// A source root and the lint context resolved for it. The path is taken as given: paths are normalised in one
    /// place, the [RootResolver] request, and a run is only ever handed paths that came from it.
    public record SourceRoot(Path path, LintContext context) {
        public static SourceRoot sourceRoot(Path path, LintContext context) {
            return new SourceRoot(path, context);
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
                             int notParsed,
                             int skipped,
                             int errors,
                             int warnings,
                             boolean fails,
                             List<String> notParsedFiles) {
        public RootCounts {
            notParsedFiles = List.copyOf(notParsedFiles);
        }

        public static RootCounts rootCounts(Path root,
                                            JbctMode mode,
                                            ModeSource modeSource,
                                            Option<String> basePackage,
                                            boolean testRoot,
                                            int files,
                                            int examined,
                                            int formatted,
                                            int notParsed,
                                            int skipped,
                                            int errors,
                                            int warnings,
                                            boolean fails,
                                            List<String> notParsedFiles) {
            return new RootCounts(root,
                                  mode,
                                  modeSource,
                                  basePackage,
                                  testRoot,
                                  files,
                                  examined,
                                  formatted,
                                  notParsed,
                                  skipped,
                                  errors,
                                  warnings,
                                  fails,
                                  notParsedFiles);
        }

        /// Why this root linted nothing, in words; empty when it linted a file.
        Option<String> whyNotLinted() {
            return examined > 0
                   ? Option.none()
                   : Option.some(root + " (" + reasonFor(!CstLinter.rulesFor(mode).isEmpty()) + ")");
        }

        /// Why this root formatted nothing, in words; empty when it formatted a file.
        Option<String> whyNotFormatted() {
            return formatted > 0
                   ? Option.none()
                   : Option.some(root + " (" + reasonFor(mode != JbctMode.DISABLED) + ")");
        }

        private String reasonFor(boolean modeAllows) {
            return notParsed > 0
                   ? notParsed + " file(s) not parsed"
                   : modeAllows
                     ? files == 0
                       ? "no Java files"
                       : "no file was asked for"
                     : "mode " + mode.token() + " skips it";
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

        /// The outcome lines of a lint activity: how many files were linted and how many did not parse, or why nothing
        /// was linted, per root. A front end prints these; it never reads a mode to word them.
        public List<String> lintLines() {
            return activityLines("linted",
                                 roots.stream().mapToInt(RootCounts::examined).sum(),
                                 roots.stream().map(RootCounts::whyNotLinted));
        }

        /// The outcome lines of a format activity (`format`, `process`).
        public List<String> formatLines() {
            return activityLines("formatted",
                                 roots.stream().mapToInt(RootCounts::formatted).sum(),
                                 roots.stream().map(RootCounts::whyNotFormatted));
        }

        /// The outcome lines of a format-check activity (`format --check`, `check`'s format half).
        public List<String> formatCheckLines() {
            return activityLines("format-checked",
                                 roots.stream().mapToInt(RootCounts::formatted).sum(),
                                 roots.stream().map(RootCounts::whyNotFormatted));
        }

        /// The outcome lines of a score activity: a root that runs no rule is named as not scored, and nothing scored
        /// at all says why per root.
        public List<String> scoreLines() {
            var unscored = roots.stream()
                                .filter(root -> root.examined() == 0)
                                .map(root -> "Not scored: " + root.whyNotLinted()
                                                                  .or(root.root().toString()))
                                .toList();

            return Stream.concat(unscored.stream(),
                                 activityLines("scored",
                                               roots.stream().mapToInt(RootCounts::examined).sum(),
                                               roots.stream().map(RootCounts::whyNotLinted)).stream())
                         .toList();
        }

        /// The files that failed to parse, as `path: failure`, in root order.
        public List<String> notParsedFiles() {
            return roots.stream()
                        .flatMap(root -> root.notParsedFiles()
                                             .stream())
                        .toList();
        }

        private List<String> activityLines(String verb, int done, Stream<Option<String>> reasons) {
            return done > 0
                   ? List.of(done + " file(s) " + verb + notParsedPart())
                   : List.of("Nothing was " + verb + ": " + reasonList(reasons));
        }

        private String notParsedPart() {
            var notParsed = roots.stream().mapToInt(RootCounts::notParsed).sum();

            return notParsed > 0
                   ? ", " + notParsed + " file(s) not parsed"
                   : "";
        }

        private static String reasonList(Stream<Option<String>> reasons) {
            var joined = reasons.flatMap(reason -> reason.stream()).collect(Collectors.joining("; "));

            return joined.isEmpty()
                   ? "no source root"
                   : joined;
        }

        private static Option<String> failureOf(List<RootCounts> roots) {
            var failing = roots.stream().filter(RootCounts::fails).map(RootCounts::describe).toList();

            return failing.isEmpty()
                   ? Option.none()
                   : Option.some("JBCT failed in " + failing.size() + " source root(s): " + String.join("; ", failing));
        }
    }

    static final String FORMAT_ONLY_NOTE = "format only, no rule run";
    static final String DISABLED_NOTE = "skipped, no format, no rule run";
    static final String INFRASTRUCTURE_NOTE = "structure checks off";

    private final List<Group> groups;
    private final Function<String, Result<Cursor>> parser;
    private final AtomicBoolean finished = new AtomicBoolean();
    private final int uncollectedTestFiles;

    private LintRun(List<Group> groups, Function<String, Result<Cursor>> parser, int uncollectedTestFiles) {
        this.groups = groups;
        this.parser = parser;
        this.uncollectedTestFiles = uncollectedTestFiles;
    }

    /// A run over the given roots; test roots are recognised by the `src/test` predicate alone.
    public static LintRun lintRun(List<SourceRoot> roots) {
        return lintRun(roots, Set.of());
    }

    /// A run over the given roots, with directories the caller declares as test roots: a root that is one of them or
    /// lies under one is a test root, besides any the `src/test` predicate finds.
    public static LintRun lintRun(List<SourceRoot> roots, Set<Path> declaredTestRoots) {
        return new LintRun(ordered(roots.stream()
                                        .map(root -> Group.group(root,
                                                                 isTestRoot(root.path(),
                                                                            declaredTestRoots),
                                                                 Option.none()))),
                           new Java25Parser()::parse,
                           0);
    }

    private static List<Group> ordered(Stream<Group> groups) {
        return groups.sorted(Comparator.comparing(group -> group.path()
                                                                .toString()))
                     .toList();
    }

    /// A run over the roots a [RootResolver] decided: each root keeps the config, mode and provenance the resolver
    /// gave it, and [#finish] reports per-root counts and the verdict.
    public static LintRun lintRun(RootPlan plan) {
        return lintRun(plan, new Java25Parser()::parse);
    }

    /// A run over a plan with the given parser: the seam through which a test counts the parses of [#perform].
    static LintRun lintRun(RootPlan plan, Function<String, Result<Cursor>> parser) {
        return new LintRun(ordered(plan.roots()
                                       .stream()
                                       .map(root -> Group.group(SourceRoot.sourceRoot(root.path(),
                                                                                      root.context()),
                                                                root.testRoot(),
                                                                Option.some(Detail.detail(root, plan))))),
                           parser,
                           plan.uncollectedTestFiles());
    }

    /// Perform every per-file action the invocation asks for, once per distinct file of the plan, each in its root's
    /// mode ([FileAction]), and report the recorded outcomes, one tally per root and asked activity, the findings and
    /// the verdict. Single-shot like [#finish]: a second call, or one after [#finish], fails with
    /// [LintRunError.General#RUN_FINISHED]. A run built from a list of roots holds no plan and performs on no file.
    public Result<RunResult> perform(Invocation invocation) {
        return finished.compareAndSet(false, true)
               ? success(performed(invocation))
               : LintRunError.General.RUN_FINISHED.result();
    }

    private RunResult performed(Invocation invocation) {
        var activities = invocation.activities();
        var files = fileResults(activities);
        var roots = groups.stream().flatMap(group -> group.rootResult(activities, files)
                                                          .stream()).toList();
        var score = RunScore.scoreOf(invocation, files);
        var verdict = verdict(invocation, activities, files, roots, score);
        var lines = RunLines.runLines(invocation, files, roots, verdict, layerLines(files), uncollectedTestFiles, score).lines();

        return RunResult.runResult(files,
                                   roots,
                                   findings(activities, files),
                                   verdict,
                                   lines.before(),
                                   lines.after(),
                                   totals(activities, files),
                                   score);
    }

    /// The run totals: one tally per asked activity over every file of the run, and the files handed over.
    private static Totals totals(Activities activities, List<FileResult> files) {
        return Totals.totals(files.size(),
                             activities.lint()
                             ? Option.some(tallyOf(files.stream().map(FileResult::lint).toList(),
                                                   Linted.class))
                             : Option.none(),
                             activities.format() == FormatAction.NONE
                             ? Option.none()
                             : Option.some(tallyOf(files.stream().map(FileResult::format).toList(),
                                                   Rewritten.class)));
    }

    /// The layer-coverage line of each root that linted a file, from the packages `perform` read.
    private List<Line> layerLines(List<FileResult> files) {
        return groups.stream()
                     .flatMap(group -> group.layerLine(files)
                                            .stream())
                     .toList();
    }

    /// One [FileResult] per distinct path of the plan: a path two roots both hold is acted on once, in the first root
    /// (in path order) that holds it.
    private List<FileResult> fileResults(Activities activities) {
        var actions = groups.stream()
                            .flatMap(group -> group.detail()
                                                   .stream())
                            .map(known -> FileAction.fileAction(known.root(),
                                                                activities,
                                                                parser))
                            .toList();
        var owners = actions.stream()
                            .flatMap(action -> action.root()
                                                     .files()
                                                     .stream()
                                                     .map(file -> Map.entry(file, action)))
                            .collect(Collectors.toMap(Map.Entry::getKey,
                                                      Map.Entry::getValue,
                                                      (first, _) -> first,
                                                      LinkedHashMap::new));

        return owners.entrySet()
                     .stream()
                     .map(owner -> owner.getValue()
                                        .perform(owner.getKey()))
                     .toList();
    }

    private List<Diagnostic> findings(Activities activities, List<FileResult> files) {
        return Stream.concat(files.stream().flatMap(LintRun::diagnosticsOf),
                             activities.lint()
                             ? groups.stream()
                                     .flatMap(group -> group.runFindings()
                                                            .stream())
                             : Stream.empty())
                     .toList();
    }

    private static Stream<Diagnostic> diagnosticsOf(FileResult file) {
        return file.lint() instanceof Linted(var diagnostics)
               ? diagnostics.stream()
               : Stream.empty();
    }

    /// NOTHING_COLLECTED when the plan holds no file; INCOMPLETE when a file was not read, not parsed or not written;
    /// FINDINGS when a root fails (a score is failed by its density gate alone) or, under a format check (not a dry
    /// run), a file would change; CLEAN otherwise.
    private static Verdict verdict(Invocation invocation,
                                   Activities activities,
                                   List<FileResult> files,
                                   List<RootResult> roots,
                                   Option<ScoreResult> score) {
        var incomplete = files.stream().filter(LintRun::unfinished).count();
        var failing = invocation.command() == Command.SCORE
                      ? RunScore.breachOf(score, invocation.maxDensity()).isPresent()
                      : roots.stream().anyMatch(RootResult::fails);
        var wouldChange = activities.format() == FormatAction.CHECK
                          ? files.stream().filter(file -> file.format() instanceof WouldChange).count()
                          : 0;

        return files.isEmpty()
               ? Verdict.nothingCollected()
               : incomplete > 0
                 ? Verdict.incomplete(RunLines.failureOf(invocation, files, roots, score))
                 : !failing && wouldChange == 0
                   ? Verdict.clean()
                   : Verdict.findings(RunLines.failureOf(invocation, files, roots, score));
    }

    private static boolean unfinished(FileResult file) {
        return failed(file.lint()) || failed(file.format()) || file.format() instanceof WriteFailed;
    }

    private static boolean failed(LintOutcome outcome) {
        return outcome instanceof ReadFailed || outcome instanceof ParseFailed;
    }

    private static boolean failed(FormatOutcome outcome) {
        return outcome instanceof ReadFailed || outcome instanceof ParseFailed;
    }

    /// The count of recorded outcomes of one activity, slot by slot; `done` counts the activity's own success type.
    private static Tally tallyOf(List<?> outcomes, Class<?> done) {
        return Tally.tally(count(outcomes, done),
                           count(outcomes, Unchanged.class),
                           count(outcomes, WouldChange.class),
                           count(outcomes, Withheld.class),
                           count(outcomes, SkippedByMode.class),
                           count(outcomes, ReadFailed.class),
                           count(outcomes, ParseFailed.class),
                           count(outcomes, WriteFailed.class));
    }

    private static int count(List<?> outcomes, Class<?> kind) {
        return (int) outcomes.stream()
                             .filter(kind::isInstance)
                             .count();
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

    /// The files the run may score: those of the roots whose mode runs rules, in root order. A front end scores these
    /// and nothing else; it reads no mode.
    public List<Path> scorable() {
        return groups.stream()
                     .filter(Group::mayLint)
                     .flatMap(group -> group.filesOf()
                                            .stream())
                     .toList();
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
        return Option.from(groups.stream()
                                 .filter(group -> group.holds(file))
                                 .max(Comparator.comparingInt(group -> group.path()
                                                                            .getNameCount())))
                     .toResult(LintRunError.OutsideSourceRoots.outsideSourceRoots(file))
                     .map(group -> group.counted(file));
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

    private static boolean isTestRoot(Path root, Set<Path> declared) {
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

    /// What was done to one file, recorded when it is done. A file is linted, formatted, both, or skipped by mode; a
    /// file whose lint failed to parse is `NOT_PARSED` and nothing else. Combining two outcomes keeps what was done:
    /// the activities of one file add up, a skip never hides work, and a parse failure is never taken back.
    private enum Outcome {
        SKIPPED_BY_MODE(false, false),
        LINTED(true, false),
        FORMATTED(false, true),
        LINTED_AND_FORMATTED(true, true),
        NOT_PARSED(false, false);
        private final boolean linted;
        private final boolean formatted;
        Outcome(boolean linted, boolean formatted) {
            this.linted = linted;
            this.formatted = formatted;
        }
        Outcome and(Outcome other) {
            return this == NOT_PARSED || other == NOT_PARSED
                   ? NOT_PARSED
                   : of(linted || other.linted, formatted || other.formatted);
        }
        private static Outcome of(boolean linted, boolean formatted) {
            return linted
                   ? formatted
                     ? LINTED_AND_FORMATTED
                     : LINTED
                   : formatted
                     ? FORMATTED
                     : SKIPPED_BY_MODE;
        }
    }

    private record Group(Path path,
                         LintContext context,
                         boolean testRoot,
                         CstLinter linter,
                         boolean lintsFiles,
                         List<RunRule> runRules,
                         AtomicInteger files,
                         Map<Path, Outcome> outcomes,
                         Map<Path, String> notParsed,
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
                             new ConcurrentHashMap<>(),
                             new ConcurrentHashMap<>(),
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

            return this;
        }

        /// Whether this group's rules may run on its files: the answer to "which files may be scored".
        boolean mayLint() {
            return lintsFiles;
        }

        List<Path> filesOf() {
            return detail.map(known -> known.root()
                                            .files())
                         .or(List.of());
        }

        Result<List<Diagnostic>> lint(SourceFile file) {
            return lintsFiles
                   ? linter.lint(file)
                           .onFailure(cause -> failedToParse(file.path(),
                                                             cause))
                           .map(found -> lintedWith(file.path(),
                                                    found))
                   : success(skippedBy(file.path()));
        }

        Result<List<Diagnostic>> lintFile(Path file) {
            return lintsFiles
                   ? SourceFile.sourceFile(file)
                               .onFailure(cause -> failedToParse(file, cause))
                               .flatMap(source -> lint(source))
                   : success(skippedBy(file));
        }

        List<Diagnostic> lintParsed(Cursor tree, SourceFile file) {
            return lintsFiles
                   ? lintedWith(file.path(), linter.lintParsed(tree, file))
                   : skippedBy(file.path());
        }

        boolean claimFormat(Path file) {
            var formats = context.mode() != JbctMode.DISABLED;

            record(file,
                   formats
                   ? Outcome.FORMATTED
                   : Outcome.SKIPPED_BY_MODE);

            return formats;
        }

        private Outcome record(Path file, Outcome outcome) {
            return outcomes.merge(file, outcome, Outcome::and);
        }

        /// A lint that could not read or parse the file is recorded with its failure, and the failure still travels to
        /// the caller: this records it, it does not absorb it.
        private Outcome failedToParse(Path file, Cause cause) {
            notParsed.put(file, cause.message());

            return record(file, Outcome.NOT_PARSED);
        }

        private List<Diagnostic> skippedBy(Path file) {
            record(file, Outcome.SKIPPED_BY_MODE);

            return List.of();
        }

        /// Record that a file was linted and count its findings against this root.
        private List<Diagnostic> lintedWith(Path file, List<Diagnostic> found) {
            record(file, Outcome.LINTED);

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
            return tallied(runFindings());
        }

        /// The run rules' findings, without counting them against the legacy counters.
        List<Diagnostic> runFindings() {
            return runRules.stream()
                           .flatMap(Group::evaluate)
                           .toList();
        }

        /// The layer-coverage line of this root, from the packages its linted files declare; none when the root linted
        /// no file or its layers are not explicitly configured.
        Option<Line> layerLine(List<FileResult> all) {
            return Option.some(lintedPackages(all))
                         .filter(packages -> !packages.isEmpty())
                         .flatMap(packages -> LayerCoverage.coverageOfPackages(packages, context))
                         .map(coverage -> Line.line(Level.INFO,
                                                    coverage.render()));
        }

        private List<String> lintedPackages(List<FileResult> all) {
            return mine(all).stream()
                       .filter(file -> file.lint() instanceof Linted)
                       .map(FileResult::packageName)
                       .toList();
        }

        /// What this root of a performed run came to, from the recorded outcomes of its files.
        Option<RootResult> rootResult(Activities activities, List<FileResult> all) {
            return detail.map(known -> rootResultOf(known, activities, mine(all)));
        }

        private List<FileResult> mine(List<FileResult> all) {
            return all.stream()
                      .filter(file -> file.root()
                                          .equals(path))
                      .toList();
        }

        private RootResult rootResultOf(Detail known, Activities activities, List<FileResult> files) {
            var found = Stream.concat(files.stream().flatMap(LintRun::diagnosticsOf),
                                      activities.lint()
                                      ? runFindings().stream()
                                      : Stream.<Diagnostic> empty())
                              .toList();
            var errorCount = severityCount(found, DiagnosticSeverity.ERROR);
            var warningCount = severityCount(found, DiagnosticSeverity.WARNING);

            return RootResult.rootResult(path,
                                         context.mode(),
                                         known.root().modeSource(),
                                         context.basePackage(),
                                         testRoot,
                                         known.testRootRule(),
                                         files.size(),
                                         activities.lint()
                                         ? Option.some(tallyOf(files.stream().map(FileResult::lint).toList(),
                                                               Linted.class))
                                         : Option.none(),
                                         activities.format() == FormatAction.NONE
                                         ? Option.none()
                                         : Option.some(tallyOf(files.stream().map(FileResult::format).toList(),
                                                               Rewritten.class)),
                                         errorCount,
                                         warningCount,
                                         severityCount(found, DiagnosticSeverity.INFO),
                                         errorCount > 0 || warningCount > 0 && known.failsOnWarning());
        }

        private static Stream<Diagnostic> evaluate(RunRule rule) {
            return Stream.empty();
        }

        /// The tally of one recorded outcome kind.
        private int tally(Predicate<Outcome> kind) {
            return (int) outcomes.values()
                                 .stream()
                                 .filter(kind)
                                 .count();
        }

        int linted() {
            return tally(outcome -> outcome.linted);
        }

        int formatted() {
            return tally(outcome -> outcome.formatted);
        }

        int notParsedCount() {
            return tally(outcome -> outcome == Outcome.NOT_PARSED);
        }

        int skipped() {
            return tally(outcome -> outcome == Outcome.SKIPPED_BY_MODE);
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
                                         outcomes.size(),
                                         linted(),
                                         formatted(),
                                         notParsedCount(),
                                         skipped(),
                                         errors.get(),
                                         warnings.get(),
                                         errors.get() > 0 || warnings.get() > 0 && known.failsOnWarning(),
                                         notParsedFiles());
        }

        private List<String> notParsedFiles() {
            return notParsed.entrySet()
                            .stream()
                            .sorted(Map.Entry.comparingByKey())
                            .map(entry -> entry.getKey() + ": " + entry.getValue())
                            .toList();
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
                 + ": " + linted()
                 + " examined by rules, " + formatted()
                 + " formatted or format-checked, " + notParsedPart() + skipped()
                 + " skipped by mode" + (testRoot
                                         ? ", " + outcomes.size() + " test-root files"
                                         : "")
                 + "; test-root rule: " + known.testRootRule() + noteText().map(text -> "; " + text)
                                                                         .or("");
        }

        private String notParsedPart() {
            var count = notParsedCount();

            return count > 0
                   ? count + " not parsed, "
                   : "";
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
