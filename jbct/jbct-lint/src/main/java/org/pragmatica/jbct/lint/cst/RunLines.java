package org.pragmatica.jbct.lint.cst;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.cst.RunOutcome.Command;
import org.pragmatica.jbct.lint.cst.RunOutcome.FileResult;
import org.pragmatica.jbct.lint.cst.RunOutcome.FormatAction;
import org.pragmatica.jbct.lint.cst.RunOutcome.Invocation;
import org.pragmatica.jbct.lint.cst.RunOutcome.Kind;
import org.pragmatica.jbct.lint.cst.RunOutcome.Channel;
import org.pragmatica.jbct.lint.cst.RunOutcome.Level;
import org.pragmatica.jbct.lint.cst.RunOutcome.Line;
import org.pragmatica.jbct.lint.cst.RunOutcome.Linted;
import org.pragmatica.jbct.lint.cst.RunOutcome.ParseFailed;
import org.pragmatica.jbct.lint.cst.RunOutcome.ReadFailed;
import org.pragmatica.jbct.lint.cst.RunOutcome.Rewritten;
import org.pragmatica.jbct.lint.cst.RunOutcome.RootResult;
import org.pragmatica.jbct.lint.cst.RunOutcome.SkippedByMode;
import org.pragmatica.jbct.lint.cst.RunOutcome.Surface;
import org.pragmatica.jbct.lint.cst.RunOutcome.Tally;
import org.pragmatica.jbct.lint.cst.RunOutcome.Unchanged;
import org.pragmatica.jbct.lint.cst.RunOutcome.Verdict;
import org.pragmatica.jbct.lint.cst.RunOutcome.Withheld;
import org.pragmatica.jbct.lint.cst.RunOutcome.WouldChange;
import org.pragmatica.jbct.lint.cst.RunOutcome.WriteFailed;
import org.pragmatica.jbct.score.ScoreReport;
import org.pragmatica.jbct.score.ScoreResult;
import org.pragmatica.jbct.shared.AnalysisCoverage;
import org.pragmatica.lang.Option;

import static org.pragmatica.jbct.lint.cst.RunOutcome.Line.line;


/// Every sentence a performed run prints (#2525), built from what `perform` recorded: the per-file outcomes, the
/// per-root tallies, the verdict and the invocation. A front end prints [Lines#before] at their levels, then the
/// findings, then [Lines#after], and holds no sentence of its own. The sentence is selected by (command, surface,
/// sentence) alone; no count is derived here, every number is a tally read or a count of recorded outcomes.
///
/// A pass sentence is printed only when the verdict is CLEAN, a file was handed over and every asked activity of every
/// file is done: no skip, no failure. An activity attempted on no file says `Nothing was <verb>: <reasons>` instead.
///
/// Score is the lint outcome plus the file's line count, so a score invocation reads the lint slot: `Measuring N`
/// (Maven), the root line with a `scored` clause, `Not scored: <root> (<reason>)` for a root that scored nothing while
/// another did, `Nothing was scored: ...`, and `<flag> X was not evaluated: nothing was scored` when a density limit was
/// given and nothing was scored. The score itself is [RunResult#score]; a front end prints it from there.
record RunLines(Invocation invocation,
                List<FileResult> files,
                List<RootResult> roots,
                Verdict verdict,
                List<Line> layers,
                int uncollectedTests,
                Option<ScoreResult> score) {
    /// The two ordered lists: the lines before the findings and the lines after them.
    record Lines(List<Line> before, List<Line> after) {
        static Lines lines(List<Line> before, List<Line> after) {
            return new Lines(List.copyOf(before), List.copyOf(after));
        }
    }

    private static final String FINDINGS_HEAD = "Files not properly formatted:";

    static RunLines runLines(Invocation invocation,
                             List<FileResult> files,
                             List<RootResult> roots,
                             Verdict verdict,
                             List<Line> layers,
                             int uncollectedTests) {
        return runLines(invocation, files, roots, verdict, layers, uncollectedTests, Option.none());
    }

    static RunLines runLines(Invocation invocation,
                             List<FileResult> files,
                             List<RootResult> roots,
                             Verdict verdict,
                             List<Line> layers,
                             int uncollectedTests,
                             Option<ScoreResult> score) {
        return new RunLines(invocation, files, roots, verdict, layers, uncollectedTests, score);
    }

    /// The text a build failure carries for this run: the tip's text for the command, built from the outcomes.
    static String failureOf(Invocation invocation, List<FileResult> files, List<RootResult> roots) {
        return failureOf(invocation, files, roots, Option.none());
    }

    static String failureOf(Invocation invocation,
                            List<FileResult> files,
                            List<RootResult> roots,
                            Option<ScoreResult> score) {
        return runLines(invocation, files, roots, Verdict.clean(), List.of(), 0, score).failure();
    }

    Lines lines() {
        return files.isEmpty()
               ? Lines.lines(nothingCollected(), List.of())
               : Lines.lines(before(), after());
    }

    // ---------------------------------------------------------------- nothing collected (T2, T3, T4)
    private List<Line> nothingCollected() {
        return List.of(carried(nothingLevel(), nothingText(), scoreOrOut()));
    }

    /// A line whose CLI stream is `cliChannel`; on the Maven surface (which logs by level) the level's own channel.
    private Line carried(Level level, String text, Channel cliChannel) {
        return invocation.surface() == Surface.CLI
               ? line(level, text, cliChannel)
               : line(level, text);
    }

    /// The release's score command wrote every sentence of its own to stderr (its stdout is the report); the other
    /// commands wrote theirs to stdout.
    private Channel scoreOrOut() {
        return invocation.command() == Command.SCORE
               ? Channel.STDERR
               : Channel.STDOUT;
    }

    private Level nothingLevel() {
        return invocation.surface() == Surface.MAVEN && uncollectedTests > 0
               ? Level.WARN
               : Level.INFO;
    }

    private String nothingText() {
        return invocation.surface() == Surface.MAVEN
               ? mavenNothing()
               : invocation.command() == Command.LINT
                 ? "No Java files found: nothing was examined. This is a COVERAGE GAP, not a pass."
                 : "No Java files found.";
    }

    private String mavenNothing() {
        return uncollectedTests > 0
               ? invocation.module()
                           .map(this::examinedNothing)
                           .or("No Java files found.")
               : "No Java files found.";
    }

    private String examinedNothing(RunOutcome.MavenModule module) {
        return "JBCT " + goal()
             + " examined NOTHING in " + module.artifactId()
             + ": no Java files under " + module.sourceDirectory()
             + ", and " + uncollectedTests
             + " test file(s) under " + module.testSourceDirectory()
             + " were excluded because " + module.testsOption()
             + "=false."
             + " This module is NOT covered by this goal — a green result here is not evidence about those files."
             + " Pass -D" + module.testsOption()
             + "=true to examine them.";
    }

    // ---------------------------------------------------------------- before the findings
    private List<Line> before() {
        return Stream.of(header().stream(),
                         roots.stream().map(this::rootLine),
                         layers.stream().map(this::layerLine),
                         perFile().stream(),
                         unformatted().stream(),
                         findingsFrame().stream())
                     .flatMap(stream -> stream)
                     .toList();
    }

    /// The release wrote the layer-coverage line to stderr, in every CLI command.
    private Line layerLine(Line layer) {
        return carried(layer.level(), layer.text(), Channel.STDERR);
    }

    /// T5, T6 (Maven) and T21 (CLI, DETAIL).
    private Option<Line> headerLine() {
        return switch (invocation.surface()) {
            case MAVEN -> mavenHeader();
            case CLI -> cliHeader();
        };
    }

    private List<Line> header() {
        return headerLine().stream()
                         .toList();
    }

    private Option<Line> mavenHeader() {
        return switch (invocation.command()) {
            case LINT -> counted("Linting ", count(this::lintAttempted));
            case CHECK -> counted("Running JBCT check on ", count(this::anyAttempted));
            case FORMAT -> counted("Formatting ", count(this::formatAttempted));
            case FORMAT_CHECK -> counted("Checking format of ", count(this::formatAttempted));
            case PROCESS -> counted("Processing ", count(this::anyAttempted));
            case SCORE -> counted("Measuring ", count(this::lintAttempted));
        };
    }

    private Option<Line> counted(String prefix, int number) {
        return number > 0
               ? Option.some(line(Level.INFO, prefix + number + " Java file(s)"))
               : Option.none();
    }

    private Option<Line> cliHeader() {
        return switch (invocation.command()) {
            case LINT -> Option.some(line(Level.DETAIL, "Found " + files.size() + " Java file(s) to lint."));
            case CHECK -> Option.some(line(Level.DETAIL, "Checking " + files.size() + " Java file(s)..."));
            case FORMAT -> Option.some(line(Level.DETAIL, "Found " + files.size() + " Java file(s) to process."));
            case FORMAT_CHECK, PROCESS, SCORE -> Option.none();
        };
    }

    /// T1: one line per root; its clauses are the root's tallies.
    private Line rootLine(RootResult root) {
        return line(Level.INFO,
                    "mode=" + root.mode()
                                  .token()
                   + " (" + root.modeSource()
                                .describe()
                   + ")"
                   + " root=" + root.root() + root.basePackage()
                                                  .map(base -> " basePackage=" + base)
                                                  .or("")
                   + ": " + root.files()
                   + " files" + root.lint()
                                    .map(this::lintClause)
                                    .or("") + root.format()
                                                  .map(this::formatClause)
                                                  .or("")
                   + "; test-root rule: " + root.testRootRule() + noteOf(root));
    }

    private String lintClause(Tally tally) {
        return "; lint: " + tally.done() + (invocation.command() == Command.SCORE
                                            ? " scored"
                                            : " linted") + rest(tally);
    }

    private String formatClause(Tally tally) {
        return "; format: " + (invocation.activities()
                                         .format() == FormatAction.WRITE
                               ? tally.done()
                                + " rewritten, " + tally.unchanged()
                                + " unchanged" + part(tally.withheld(), "withheld") + part(tally.writeFailed(),
                                                                                           "not written")
                               : tally.unchanged() + " unchanged, " + tally.wouldChange() + " would change") + rest(tally);
    }

    private static String rest(Tally tally) {
        return part(tally.skippedByMode(), "skipped by mode") + part(tally.notParsed(), "not parsed") + part(tally.notRead(),
                                                                                                             "not read");
    }

    private static String part(int number, String words) {
        return number > 0
               ? ", " + number + " " + words
               : "";
    }

    private static String noteOf(RootResult root) {
        return switch (root.mode()) {
            case BUSINESS -> "";
            case INFRASTRUCTURE -> "; " + LintRun.INFRASTRUCTURE_NOTE;
            case FORMAT_ONLY -> "; " + LintRun.FORMAT_ONLY_NOTE;
            case DISABLED -> "; " + LintRun.DISABLED_NOTE;
        };
    }

    /// T13: the files a check found not formatted, before the findings; the release's format-check logged them once, one
    /// error line per file, after its loop (the failure text repeats them).
    private List<Line> unformatted() {
        return switch (invocation.command()) {
            case CHECK -> count(this::wouldChange) > 0
                          ? Stream.of(blank(),
                                      Stream.of(carried(Level.ERROR, FINDINGS_HEAD, Channel.STDOUT)),
                                      wouldChangeFiles().map(file -> carried(Level.ERROR, "  " + file, Channel.STDOUT))).flatMap(stream -> stream).toList()
                          : List.of();
            case FORMAT_CHECK -> invocation.surface() == Surface.MAVEN
                                 ? wouldChangeFiles().map(file -> line(Level.ERROR, "  " + file)).toList()
                                 : List.of();
            default -> List.of();
        };
    }

    /// The release's check and lint printed a blank line in front of their findings, when there were findings.
    private List<Line> findingsFrame() {
        return invocation.surface() == Surface.CLI && invocation.command() != Command.FORMAT && invocation.command() != Command.SCORE && hasFindings()
               ? List.of(line(Level.INFO, "", Channel.WITH_FINDINGS))
               : List.of();
    }

    private boolean hasFindings() {
        return files.stream()
                    .anyMatch(file -> file.lint() instanceof Linted linted && !linted.diagnostics()
                                                                                     .isEmpty());
    }

    /// A blank line on stdout, as the release's CLI printed between its blocks; the Maven surface has none.
    private Stream<Line> blank() {
        return invocation.surface() == Surface.CLI
               ? Stream.of(line(Level.INFO, "", Channel.STDOUT))
               : Stream.empty();
    }

    private Stream<String> wouldChangeFiles() {
        return files.stream()
                    .filter(this::wouldChange)
                    .map(file -> file.file()
                                     .toString());
    }

    // ---------------------------------------------------------------- per-file lines (T20)
    private List<Line> perFile() {
        return files.stream()
                    .flatMap(file -> Stream.concat(fileLine(file).stream(),
                                                   checkDetail(file)))
                    .toList();
    }

    /// `check -v` names every file the release's check found clean, per activity: format first, then lint.
    private Stream<Line> checkDetail(FileResult file) {
        return invocation.surface() == Surface.CLI && invocation.command() == Command.CHECK
               ? Stream.of(detail(file.format() instanceof Unchanged,
                                  "  ✓ format: " + file.file().getFileName()),
                           detail(lintClean(file),
                                  "  ✓ lint: " + file.file().getFileName()))
                       .flatMap(Option::stream)
               : Stream.empty();
    }

    private static boolean lintClean(FileResult file) {
        return file.lint() instanceof Linted linted && linted.diagnostics()
                                                             .isEmpty();
    }

    private static Option<Line> detail(boolean when, String text) {
        return when
               ? Option.some(line(Level.DETAIL, text))
               : Option.none();
    }

    private Option<Line> fileLine(FileResult file) {
        return failureLine(file).orElse(() -> successLine(file));
    }

    /// One error line per unreadable or unparseable file; where lint is asked its outcome speaks for the file.
    private Option<Line> failureLine(FileResult file) {
        return invocation.activities()
                         .lint()
               ? lintFailureLine(file)
               : formatFailureLine(file);
    }

    private Option<Line> lintFailureLine(FileResult file) {
        return switch (file.lint()) {
            case ReadFailed(var message) -> Option.some(error(readText(file, message)));
            case ParseFailed(var message) -> Option.some(error(parseText(file, message)));
            default -> formatFailureLine(file);
        };
    }

    private Option<Line> formatFailureLine(FileResult file) {
        return switch (file.format()) {
            case ReadFailed(var message) -> Option.some(error(readText(file, message)));
            case ParseFailed(var message) -> Option.some(error(formatParseText(file, message)));
            case WriteFailed(var message) -> Option.some(error(writeText(file, message)));
            default -> Option.none();
        };
    }

    private String readText(FileResult file, String message) {
        return switch (invocation.surface()) {
            case MAVEN -> "Error reading " + file.file() + ": " + message;
            case CLI -> cliFailure(file, message);
        };
    }

    private String parseText(FileResult file, String message) {
        return switch (invocation.surface()) {
            case MAVEN -> "Parse error in " + file.file() + ": " + message;
            case CLI -> cliFailure(file, message);
        };
    }

    private String cliFailure(FileResult file, String message) {
        return switch (invocation.command()) {
            case LINT, SCORE -> "  ✗ " + file.file() + ": " + message;
            case CHECK -> "  ✗ parse error: " + file.file() + ": " + message;
            default -> "  error: " + file.file() + " - " + message;
        };
    }

    private String formatParseText(FileResult file, String message) {
        return invocation.surface() == Surface.MAVEN
               ? switch (invocation.command()) {
            case FORMAT_CHECK -> "Error checking " + file.file() + ": " + positioned(file, message);
            case PROCESS -> "Parse error in " + file.file() + ": " + message;
            default -> "Error formatting " + file.file() + ": " + positioned(file, message);
        }
               : invocation.command() == Command.FORMAT
                 ? "  error: " + file.file() + " - " + positioned(file, message)
                 : cliFailure(file, message);
    }

    /// The release's formatter reported an unparseable file as `Parse error at <name>:<line>:<column> - <details>`; the
    /// formatter never read a position out of the parse failure, so the release's text always said 1:1.
    private static String positioned(FileResult file, String message) {
        return "Parse error at " + file.file()
                                       .getFileName() + ":1:1 - " + message;
    }

    private String writeText(FileResult file, String message) {
        return invocation.surface() == Surface.MAVEN
               ? invocation.command() == Command.PROCESS
                 ? "Error writing " + file.file() + ": " + message
                 : "Error formatting " + file.file() + ": " + message
               : "  error: " + file.file() + " - " + message;
    }

    private static Line error(String text) {
        return line(Level.ERROR, text);
    }

    private Option<Line> successLine(FileResult file) {
        return switch (file.format()) {
            case Rewritten _ -> rewrittenLine(file);
            case Unchanged _ -> cliOnly(Level.DETAIL, "  unchanged: " + file.file());
            case WouldChange _ -> wouldChangeLine(file);
            default -> Option.none();
        };
    }

    private Option<Line> rewrittenLine(FileResult file) {
        return invocation.surface() == Surface.MAVEN
               ? Option.some(line(Level.WARN, "Formatted: " + file.file()))
               : Option.some(line(Level.DETAIL, "  formatted: " + file.file()));
    }

    private Option<Line> wouldChangeLine(FileResult file) {
        return invocation.command() == Command.FORMAT
               ? Option.some(line(Level.INFO,
                                  invocation.format() == FormatAction.CHECK
                                  ? "  needs formatting: " + file.file()
                                  : "  would format: " + file.file()))
               : Option.none();
    }

    private Option<Line> cliOnly(Level level, String text) {
        return invocation.surface() == Surface.CLI && invocation.command() == Command.FORMAT
               ? Option.some(line(level, text))
               : Option.none();
    }

    // ---------------------------------------------------------------- after the findings
    private List<Line> after() {
        return Stream.of(scoreTable(),
                         afterFrame(),
                         withheldLine().stream(),
                         gap().stream(),
                         scoreBreach().stream(),
                         skipLines().stream(),
                         summary().stream())
                     .flatMap(stream -> stream)
                     .toList();
    }

    /// The release's Maven score logged its table before the coverage-gap line, which was the goal's last error line, so the
    /// table leads the Maven score's after-group. The CLI prints the table from [RunResult#score] to its own stream.
    private Stream<Line> scoreTable() {
        return invocation.surface() == Surface.MAVEN && invocation.command() == Command.SCORE
               ? score.stream()
                      .flatMap(result -> ScoreReport.terminalLines(result).stream())
                      .map(text -> line(Level.INFO, text))
               : Stream.empty();
    }

    /// The release's score printed the density gate's breach to stderr, after a blank line, when nothing was left unexamined;
    /// the Maven surface fails the build with the verdict's message instead.
    private Option<Line> scoreBreach() {
        return invocation.surface() == Surface.CLI && invocation.command() == Command.SCORE && !overall().isPartial()
               ? RunScore.breachOf(score,
                                   invocation.maxDensity())
                         .map(message -> line(Level.ERROR, "\n" + message, Channel.STDERR))
               : Option.none();
    }

    /// The release's lint, check and format printed one blank line before their gap report and summary.
    private Stream<Line> afterFrame() {
        return invocation.command() == Command.SCORE
               ? Stream.empty()
               : blank();
    }

    /// T26.
    private Option<Line> withheldLine() {
        return invocation.command() == Command.PROCESS && invocation.surface() == Surface.MAVEN
               ? withheldCount()
               : Option.none();
    }

    private Option<Line> withheldCount() {
        var withheld = count(file -> file.format() instanceof Withheld);

        return withheld > 0
               ? Option.some(line(Level.WARN, withheld + " file(s) not formatted: lint errors (failBeforeFormat)"))
               : Option.none();
    }

    /// T18.
    private Option<Line> gap() {
        return overall().gapReport(goal())
                      .map(text -> carried(Level.ERROR,
                                           text,
                                           scoreOrOut()));
    }

    /// T14 and T15.
    private List<Line> skipLines() {
        return Stream.of(skippedLine().stream(),
                         notScored(),
                         nothingDone(),
                         densityNotEvaluated().stream())
                     .flatMap(stream -> stream)
                     .toList();
    }

    private Option<Line> skippedLine() {
        var skipped = count(this::skippedForAny);

        return invocation.command() != Command.SCORE && skipped > 0 && count(this::anyAttempted) > 0
               ? Option.some(line(Level.WARN,
                                  skipped
                                 + " file(s) skipped by mode: " + roots.stream()
                                                                       .filter(this::hasSkipped)
                                                                       .map(root -> root.root()
                                                                                   + " (mode " + root.mode()
                                                                                                     .token()
                                                                                   + ")")
                                                                       .collect(Collectors.joining("; "))))
               : Option.none();
    }

    private boolean hasSkipped(RootResult root) {
        return files.stream()
                    .anyMatch(file -> file.root()
                                          .equals(root.root()) && skippedForAny(file));
    }

    private Stream<Line> nothingDone() {
        return Stream.of(nothingLine(invocation.activities().lint() && count(this::lintAttempted) == 0,
                                     invocation.command() == Command.SCORE
                                     ? "scored"
                                     : "linted",
                                     RootResult::lint),
                         nothingLine(invocation.activities()
                                               .format() != FormatAction.NONE && count(this::formatAttempted) == 0,
                                     formatVerb(),
                                     RootResult::format))
                     .flatMap(Option::stream);
    }

    /// T16: a root that scored nothing while another root scored, with its reasons.
    private Stream<Line> notScored() {
        return invocation.command() == Command.SCORE && count(this::lintAttempted) > 0
               ? roots.stream()
                      .filter(root -> root.lint()
                                          .map(tally -> tally.done() == 0)
                                          .or(true))
                      .map(root -> line(Level.WARN,
                                        "Not scored: " + root.root() + " (" + reasons(root,
                                                                                      root.lint()) + ")"))
               : Stream.empty();
    }

    /// T17: a density limit was given and nothing was scored; the limit is reported as not evaluated, nothing more.
    private Option<Line> densityNotEvaluated() {
        return score.isEmpty()
               ? invocation.maxDensity()
                           .map(limit -> line(Level.INFO,
                                              limit.flag()
                                             + " " + limit.perKloc()
                                             + " was not evaluated: nothing was scored"))
               : Option.none();
    }

    private String formatVerb() {
        return invocation.activities()
                         .format() == FormatAction.CHECK
               ? "format-checked"
               : "formatted";
    }

    private Option<Line> nothingLine(boolean nothing, String verb, Function<RootResult, Option<Tally>> activity) {
        return nothing
               ? Option.some(line(Level.WARN,
                                  "Nothing was " + verb
                                 + ": " + roots.stream()
                                               .map(root -> root.root()
                                                           + " (" + reasons(root,
                                                                            activity.apply(root))
                                                           + ")")
                                               .collect(Collectors.joining("; "))))
               : Option.none();
    }

    private static String reasons(RootResult root, Option<Tally> tally) {
        return tally.map(known -> reasonList(root, known))
                    .or("no Java files");
    }

    private static String reasonList(RootResult root, Tally tally) {
        var reasons = Stream.of(tally.skippedByMode() > 0
                                ? Option.some("mode " + root.mode().token() + " skips it")
                                : Option.<String> none(),
                                tally.notParsed() > 0
                                ? Option.some(tally.notParsed() + " file(s) not parsed")
                                : Option.<String> none(),
                                tally.notRead() > 0
                                ? Option.some(tally.notRead() + " file(s) not read")
                                : Option.<String> none(),
                                root.files() == 0
                                ? Option.some("no Java files")
                                : Option.<String> none())
                            .flatMap(Option::stream)
                            .collect(Collectors.joining(", "));

        return reasons.isEmpty()
               ? "no Java files"
               : reasons;
    }

    // ---------------------------------------------------------------- summary lines (T7 .. T12)
    private List<Line> summary() {
        return switch (invocation.command()) {
            case LINT -> lintSummary();
            case CHECK -> checkSummary();
            case FORMAT -> formatSummary();
            case FORMAT_CHECK -> formatCheckSummary();
            case PROCESS -> processSummary();
            case SCORE -> List.of();
        };
    }

    private List<Line> lintSummary() {
        return count(this::lintAttempted) == 0
               ? List.of()
               : List.of(invocation.surface() == Surface.CLI
                         ? cliLintSummary()
                         : line(Level.INFO, "Lint results (checked " + overall().render() + "): " + severities()));
    }

    private Line cliLintSummary() {
        return overall().isPartial()
               ? line(Level.INFO, "✗ Checked " + overall().render() + ": " + severities())
               : passed() && noFindings()
                 ? line(Level.INFO, "✓ All " + overall().collected() + " file(s) passed JBCT compliance check.")
                 : line(Level.INFO, "Checked " + overall().render() + ": " + severities());
    }

    private String severities() {
        return roots.stream()
                    .mapToInt(RootResult::errors)
                    .sum()
             + " error(s), " + roots.stream()
                                    .mapToInt(RootResult::warnings)
                                    .sum()
             + " warning(s), " + roots.stream()
                                      .mapToInt(RootResult::infos)
                                      .sum()
             + " info(s)";
    }

    private boolean noFindings() {
        return roots.stream()
                    .allMatch(root -> root.errors() == 0
                                      && root.warnings() == 0
                                      && root.infos() == 0);
    }

    private List<Line> checkSummary() {
        return count(this::anyAttempted) == 0
               ? List.of()
               : Stream.concat(Stream.of(line(Level.INFO,
                                              "Check results (checked " + overall().render()
                                             + "): " + count(this::wouldChange)
                                             + " format issue(s), " + roots.stream()
                                                                           .mapToInt(RootResult::errors)
                                                                           .sum()
                                             + " lint error(s), " + roots.stream()
                                                                         .mapToInt(RootResult::warnings)
                                                                         .sum()
                                             + " warning(s)")),
                               passLine(invocation.surface() == Surface.CLI
                                        ? "✓ All checks passed."
                                        : "JBCT check passed.").stream())
                       .toList();
    }

    private List<Line> formatSummary() {
        return count(this::formatAttempted) == 0
               ? List.of()
               : List.of(switch (invocation.format()) {
            case WRITE -> line(Level.INFO,
                               "Formatted: " + count(file -> file.format() instanceof Rewritten) + ", Unchanged: " + count(file -> file.format() instanceof Unchanged) + ", Errors: " + count(this::formatErrored));
            case DRY_RUN -> line(Level.INFO, "Dry run: " + count(this::wouldChange) + " file(s) would be formatted.");
            default -> checkLine();
        });
    }

    private Line checkLine() {
        return overall().isPartial()
               ? line(Level.INFO,
                      "✗ Checked " + overall().render() + ": " + count(this::wouldChange) + " file(s) need formatting")
               : passed()
                 ? line(Level.INFO, "All files are properly formatted.")
                 : line(Level.INFO, count(this::wouldChange) + " file(s) need formatting.");
    }

    private List<Line> formatCheckSummary() {
        return passLine("All files are properly formatted.").stream()
                       .toList();
    }

    private List<Line> processSummary() {
        return Stream.of(count(this::formatAttempted) > 0
                         ? Option.some(line(Level.INFO,
                                            "Format (checked " + activity(this::formatAttempted, this::formatUnreadable)
                                           + "): " + count(file -> file.format() instanceof Rewritten)
                                           + " formatted, " + count(file -> file.format() instanceof Unchanged)
                                           + " unchanged, " + count(file -> file.format() instanceof WriteFailed)
                                           + " errors"))
                         : Option.<Line> none(),
                         count(this::lintAttempted) > 0
                         ? Option.some(line(Level.INFO,
                                            "Lint (checked " + activity(this::lintAttempted, this::lintUnreadable)
                                           + "): " + severities()))
                         : Option.<Line> none())
                     .flatMap(Option::stream)
                     .toList();
    }

    private Option<Line> passLine(String text) {
        return passed()
               ? Option.some(line(Level.INFO, text))
               : Option.none();
    }

    /// The pass rule: CLEAN, a file handed over, and every asked activity of every file done.
    private boolean passed() {
        return verdict.kind() == Kind.CLEAN
               && !files.isEmpty()
               && files.stream()
                       .allMatch(this::allDone);
    }

    private boolean allDone(FileResult file) {
        return (! invocation.activities()
                            .lint() || file.lint() instanceof Linted) && (invocation.activities()
                                                                                    .format() == FormatAction.NONE || formatDone(file));
    }

    private static boolean formatDone(FileResult file) {
        return file.format() instanceof Rewritten || file.format() instanceof Unchanged || file.format() instanceof WouldChange;
    }

    // ---------------------------------------------------------------- failure text (T22, T23)
    String failure() {
        return switch (invocation.command()) {
            case LINT -> reasoned("lint", gapReason(), failingRoots());
            case CHECK -> reasoned("check", notFormattedReason(), gapReason(), failingRoots());
            case PROCESS -> count(file -> file.format() instanceof WriteFailed) > 0
                            ? "Formatting failed for " + count(file -> file.format() instanceof WriteFailed) + " file(s)"
                            : reasoned("process", gapReason(), failingRoots());
            case FORMAT -> formatFailure();
            case FORMAT_CHECK -> formatCheckFailure();
            case SCORE -> scoreFailure();
        };
    }

    /// The coverage gap outranks the density check; a breach is the gate's own message; roots do not fail a score.
    private String scoreFailure() {
        return overall().isPartial()
               ? reasoned("score", gapReason().map(reason -> reason + ", so the reported density does not cover them"))
               : RunScore.breachOf(score,
                                   invocation.maxDensity())
                         .or("JBCT score failed");
    }

    private String formatFailure() {
        return count(this::formatErrored) > 0
               ? "Formatting failed for " + count(this::formatErrored) + " file(s)"
               : count(this::wouldChange) > 0
                 ? count(this::wouldChange) + " file(s) need formatting"
                 : "JBCT format failed";
    }

    private String formatCheckFailure() {
        return count(this::wouldChange) > 0
               ? count(this::wouldChange)
                + " file(s) are not properly formatted:" + wouldChangeList() + (invocation.surface() == Surface.MAVEN
                                                                                ? "\nRun 'mvn jbct:format' to fix."
                                                                                : "")
               : count(this::formatUnreadable) > 0
                 ? "Format check failed for " + count(this::formatUnreadable) + " file(s)"
                 : "JBCT format-check failed";
    }

    private String wouldChangeList() {
        return wouldChangeFiles().map(file -> "\n  " + file)
                               .collect(Collectors.joining());
    }

    private Option<String> notFormattedReason() {
        return count(this::wouldChange) > 0
               ? Option.some(count(this::wouldChange) + " file(s) need formatting:" + wouldChangeList())
               : Option.none();
    }

    private Option<String> gapReason() {
        return overall().isPartial()
               ? Option.some(overall().unparseable() + " file(s) could not be read or parsed and were NOT analysed")
               : Option.none();
    }

    private Option<String> failingRoots() {
        var failing = roots.stream()
                           .filter(RootResult::fails)
                           .map(root -> root.root()
                                       + ": " + root.errors()
                                       + " error(s), " + root.warnings()
                                       + " warning(s)")
                           .toList();

        return failing.isEmpty()
               ? Option.none()
               : Option.some("JBCT failed in " + failing.size() + " source root(s): " + String.join("; ", failing));
    }

    @SafeVarargs
    private String reasoned(String goalName, Option<String>... reasons) {
        var joined = Stream.of(reasons).flatMap(Option::stream).collect(Collectors.joining(", "));

        return joined.isEmpty()
               ? "JBCT " + goalName + " failed"
               : "JBCT " + goalName + " failed: " + joined;
    }

    // ---------------------------------------------------------------- recorded-outcome predicates
    private String goal() {
        return invocation.command()
                         .name()
                         .toLowerCase()
                         .replace('_', '-');
    }

    private int count(Predicate<FileResult> kind) {
        return (int) files.stream()
                          .filter(kind)
                          .count();
    }

    private boolean lintAttempted(FileResult file) {
        return invocation.activities()
                         .lint() && !(file.lint() instanceof SkippedByMode);
    }

    private boolean formatAttempted(FileResult file) {
        return invocation.activities()
                         .format() != FormatAction.NONE && !(file.format() instanceof SkippedByMode);
    }

    private boolean anyAttempted(FileResult file) {
        return lintAttempted(file) || formatAttempted(file);
    }

    private boolean skippedForAny(FileResult file) {
        return invocation.activities()
                         .lint() && file.lint() instanceof SkippedByMode || invocation.activities()
                                                                                      .format() != FormatAction.NONE && file.format() instanceof SkippedByMode;
    }

    private boolean wouldChange(FileResult file) {
        return file.format() instanceof WouldChange;
    }

    private boolean lintUnreadable(FileResult file) {
        return file.lint() instanceof ReadFailed || file.lint() instanceof ParseFailed;
    }

    private boolean formatUnreadable(FileResult file) {
        return file.format() instanceof ReadFailed || file.format() instanceof ParseFailed;
    }

    private boolean formatErrored(FileResult file) {
        return formatUnreadable(file) || file.format() instanceof WriteFailed;
    }

    /// What the run was able to analyse: files on which some asked activity was attempted, and those among them that
    /// could not be read or parsed.
    private AnalysisCoverage overall() {
        return AnalysisCoverage.analysisCoverage(count(this::anyAttempted),
                                                 count(file -> anyAttempted(file) && unreadable(file)));
    }

    private boolean unreadable(FileResult file) {
        return invocation.activities()
                         .lint() && lintUnreadable(file) || invocation.activities()
                                                                      .format() != FormatAction.NONE && formatUnreadable(file);
    }

    private String activity(Predicate<FileResult> attempted, Predicate<FileResult> unreadable) {
        return AnalysisCoverage.analysisCoverage(count(attempted),
                                                 count(file -> attempted.test(file) && unreadable.test(file)))
                               .render();
    }
}
