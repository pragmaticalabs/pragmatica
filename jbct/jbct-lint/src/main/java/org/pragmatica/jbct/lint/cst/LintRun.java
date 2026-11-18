package org.pragmatica.jbct.lint.cst;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.DiagnosticSeverity;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.layer.LayerCoverage;
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
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


/// One lint run over the roots of a [RootPlan] (#2525).
///
/// A run holds one group per resolved root: the root, its mode and lint context, whether it is a test root, and the
/// run rules its mode leaves on. [#perform] acts once on every distinct file of the plan ([FileAction]) and reports
/// what was recorded: one tally per root and asked activity, the findings, the verdict and the lines to print.
///
/// **Finish discipline:** [#perform] is single-shot. A second call fails with [LintRunError.General#RUN_FINISHED]:
/// the result was already handed out.
///
/// **Thread Safety:** a run is performed once, from one thread; the linters it uses are thread-safe.
public final class LintRun {
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

    private static List<Group> ordered(Stream<Group> groups) {
        return groups.sorted(Comparator.comparing(group -> group.path()
                                                                .toString()))
                     .toList();
    }

    /// A run over the roots a [RootResolver] decided: each root keeps the config, mode and provenance the resolver
    /// gave it, and [#perform] reports per-root counts and the verdict.
    public static LintRun lintRun(RootPlan plan) {
        return lintRun(plan, new Java25Parser()::parse);
    }

    /// A run over a plan with the given parser: the seam through which a test counts the parses of [#perform].
    static LintRun lintRun(RootPlan plan, Function<String, Result<Cursor>> parser) {
        return new LintRun(ordered(plan.roots()
                                       .stream()
                                       .map(root -> Group.group(root, Detail.detail(root, plan)))),
                           parser,
                           plan.uncollectedTestFiles());
    }

    /// Perform every per-file action the invocation asks for, once per distinct file of the plan, each in its root's
    /// mode ([FileAction]), and report the recorded outcomes, one tally per root and asked activity, the findings and
    /// the verdict. Single-shot: a second call fails with
    /// [LintRunError.General#RUN_FINISHED].
    public Result<RunResult> perform(Invocation invocation) {
        return finished.compareAndSet(false, true)
               ? success(performed(invocation))
               : LintRunError.General.RUN_FINISHED.result();
    }

    private RunResult performed(Invocation invocation) {
        var activities = invocation.activities();
        var files = fileResults(activities);
        var roots = groups.stream().map(group -> group.rootResult(activities, files)).toList();
        var score = RunScore.scoreOf(invocation, files);
        var verdict = verdict(invocation, activities, files, roots, score);
        var lines = RunLines.runLines(invocation, files, roots, verdict, layerLines(files), uncollectedTestFiles, score).lines();

        return RunResult.runResult(files,
                                   roots,
                                   findings(invocation, activities, files),
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
                            .map(group -> FileAction.fileAction(group.detail().root(),
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

    /// The release's `score` printed no finding: the diagnostics feed the score, not the output. The run knows the command, so
    /// a score run returns none and no front end needs a per-command condition.
    private List<Diagnostic> findings(Invocation invocation, Activities activities, List<FileResult> files) {
        return invocation.command() == Command.SCORE
               ? List.of()
               : Stream.concat(files.stream().flatMap(LintRun::diagnosticsOf),
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

    private record Group(Path path, LintContext context, boolean testRoot, List<RunRule> runRules, Detail detail) {
        static Group group(ResolvedRoot root, Detail detail) {
            return new Group(root.path(),
                             root.context(),
                             root.testRoot(),
                             runRulesIn(CstLinter.rulesFor(root.context().mode())),
                             detail);
        }

        private static List<RunRule> runRulesIn(List<LintRule> rules) {
            return rules.stream()
                        .filter(RunRule.class::isInstance)
                        .map(RunRule.class::cast)
                        .toList();
        }

        /// The run rules' findings.
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
        RootResult rootResult(Activities activities, List<FileResult> all) {
            return rootResultOf(detail, activities, mine(all));
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

        private static int severityCount(List<Diagnostic> found, DiagnosticSeverity severity) {
            return (int) found.stream()
                              .filter(diagnostic -> diagnostic.severity() == severity)
                              .count();
        }

        private static Stream<Diagnostic> evaluate(RunRule rule) {
            return Stream.empty();
        }
    }
}
