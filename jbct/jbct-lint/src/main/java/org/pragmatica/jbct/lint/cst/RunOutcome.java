package org.pragmatica.jbct.lint.cst;

import java.nio.file.Path;
import java.util.List;

import org.pragmatica.jbct.config.JbctMode;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.cst.RootResolver.ModeSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


/// The vocabulary of one invocation of the lint run (#2525): what was asked ([Invocation]), what happened to each
/// file for each asked activity ([LintOutcome], [FormatOutcome], [FileResult]), the tallies of those outcomes
/// ([Tally]), the lines the run prints ([Line]) and the verdict ([Verdict]). Types only: the run that records them
/// arrives in the next step, so nothing here is read by existing code.
///
/// One sealed family per activity; the five records both families share (`NotAsked`, `SkippedByMode`, `ReadFailed`,
/// `ParseFailed`) implement both. Score is not a third family: its per-file outcome is the lint outcome plus the file's
/// line count in [FileResult].
public sealed interface RunOutcome permits RunOutcome.unused {
    record unused() implements RunOutcome {}

    /// What a front end asked the run to do.
    enum Command {
        LINT,
        CHECK,
        FORMAT,
        FORMAT_CHECK,
        PROCESS,
        SCORE
    }

    /// The front end that asked; read only to choose sentences.
    enum Surface {
        CLI,
        MAVEN
    }

    /// What the format activity does with a file; `NONE` means the format activity is not asked.
    enum FormatAction {
        NONE,
        CHECK,
        DRY_RUN,
        WRITE
    }

    /// The score gate: findings per thousand lines and the flag that carried it.
    record DensityLimit(double perKloc, String flag) {
        public static DensityLimit densityLimit(double perKloc, String flag) {
            return new DensityLimit(perKloc, flag);
        }
    }

    /// The Maven module facts the sentences need; `testsOption` is the `jbct.includeTests` name for the empty-plan sentence.
    record MavenModule(String artifactId, Path sourceDirectory, Path testSourceDirectory, String testsOption) {
        public static MavenModule mavenModule(String artifactId,
                                              Path sourceDirectory,
                                              Path testSourceDirectory,
                                              String testsOption) {
            return new MavenModule(artifactId, sourceDirectory, testSourceDirectory, testsOption);
        }
    }

    /// One request to the run. `format` is read only for FORMAT and `failBeforeFormat` only for PROCESS: the factories
    /// give every other command none, and the canonical constructor drops what its command does not take, so a record
    /// built around the factories still cannot carry them. The asked [Activities] are derived from the command.
    record Invocation(Command command,
                      Surface surface,
                      FormatAction format,
                      boolean failBeforeFormat,
                      Option<DensityLimit> maxDensity,
                      Option<MavenModule> module) {
        public Invocation {
            format = command == Command.FORMAT
                     ? format
                     : FormatAction.NONE;
            failBeforeFormat = command == Command.PROCESS && failBeforeFormat;
            maxDensity = command == Command.SCORE
                         ? maxDensity
                         : none();
        }

        public static Invocation lint(Surface surface) {
            return invocation(Command.LINT, surface, FormatAction.NONE, false, none());
        }

        public static Invocation check(Surface surface) {
            return invocation(Command.CHECK, surface, FormatAction.NONE, false, none());
        }

        /// Refused without an action: a format that does nothing is not a format.
        public static Result<Invocation> format(Surface surface, FormatAction action) {
            return action == FormatAction.NONE
                   ? LintRunError.General.FORMAT_ACTION_REQUIRED.result()
                   : success(invocation(Command.FORMAT, surface, action, false, none()));
        }

        public static Invocation formatCheck(Surface surface) {
            return invocation(Command.FORMAT_CHECK, surface, FormatAction.NONE, false, none());
        }

        public static Invocation process(Surface surface, boolean failBeforeFormat) {
            return invocation(Command.PROCESS, surface, FormatAction.NONE, failBeforeFormat, none());
        }

        public static Invocation score(Surface surface, Option<DensityLimit> limit) {
            return invocation(Command.SCORE, surface, FormatAction.NONE, false, limit);
        }

        public Invocation withModule(MavenModule mavenModule) {
            return new Invocation(command, surface, format, failBeforeFormat, maxDensity, some(mavenModule));
        }

        /// The asked activities of this invocation.
        Activities activities() {
            return switch (command) {
                case LINT -> Activities.activities(true, FormatAction.NONE, false, false);
                case CHECK -> Activities.activities(true, FormatAction.CHECK, false, false);
                case FORMAT -> Activities.activities(false, format, false, false);
                case FORMAT_CHECK -> Activities.activities(false, FormatAction.CHECK, false, false);
                case PROCESS -> Activities.activities(true, FormatAction.WRITE, false, failBeforeFormat);
                case SCORE -> Activities.activities(false, FormatAction.NONE, true, false);
            };
        }

        private static Invocation invocation(Command command,
                                             Surface surface,
                                             FormatAction format,
                                             boolean failBeforeFormat,
                                             Option<DensityLimit> maxDensity) {
            return new Invocation(command, surface, format, failBeforeFormat, maxDensity, none());
        }
    }

    /// What happened to a file for the lint activity.
    sealed interface LintOutcome {}

    /// What happened to a file for the format activity.
    sealed interface FormatOutcome {}

    /// The activity was not asked: no tally, no line, no verdict.
    record NotAsked() implements LintOutcome, FormatOutcome {
        public static NotAsked notAsked() {
            return new NotAsked();
        }
    }

    /// The root's mode skips the activity; the file was not opened for it.
    record SkippedByMode(JbctMode mode) implements LintOutcome, FormatOutcome {
        public static SkippedByMode skippedByMode(JbctMode mode) {
            return new SkippedByMode(mode);
        }
    }

    /// The file could not be read; the outcome of every activity that file was to do.
    record ReadFailed(String message) implements LintOutcome, FormatOutcome {
        public static ReadFailed readFailed(String message) {
            return new ReadFailed(message);
        }
    }

    /// The file could not be parsed; the outcome of every activity that file was to do.
    record ParseFailed(String message) implements LintOutcome, FormatOutcome {
        public static ParseFailed parseFailed(String message) {
            return new ParseFailed(message);
        }
    }

    /// Lint ran on the parsed tree; the diagnostics may be empty.
    record Linted(List<Diagnostic> diagnostics) implements LintOutcome {
        public Linted {
            diagnostics = List.copyOf(diagnostics);
        }

        public static Linted linted(List<Diagnostic> diagnostics) {
            return new Linted(diagnostics);
        }
    }

    /// The content differed and the write returned success.
    record Rewritten() implements FormatOutcome {
        public static Rewritten rewritten() {
            return new Rewritten();
        }
    }

    /// The file was already formatted; nothing was written.
    record Unchanged() implements FormatOutcome {
        public static Unchanged unchanged() {
            return new Unchanged();
        }
    }

    /// The format check found the file would change; nothing was written.
    record WouldChange() implements FormatOutcome {
        public static WouldChange wouldChange() {
            return new WouldChange();
        }
    }

    /// The write failed; the message is the filesystem's.
    record WriteFailed(String message) implements FormatOutcome {
        public static WriteFailed writeFailed(String message) {
            return new WriteFailed(message);
        }
    }

    /// `failBeforeFormat` and the file has lint errors: the format step was not run for it.
    record Withheld() implements FormatOutcome {
        public static Withheld withheld() {
            return new Withheld();
        }
    }

    /// The recorded outcomes of one file: one per activity, `NotAsked` where the activity was not asked, and the
    /// non-blank line count the score needs, and the package it declares (`""` when the file was not read or declares
    /// none) for the layer census.
    record FileResult(Path file,
                      Path root,
                      LintOutcome lint,
                      FormatOutcome format,
                      int linesOfCode,
                      String packageName) {
        public static FileResult fileResult(Path file,
                                            Path root,
                                            LintOutcome lint,
                                            FormatOutcome format,
                                            int linesOfCode,
                                            String packageName) {
            return new FileResult(file, root, lint, format, linesOfCode, packageName);
        }
    }

    /// The count of recorded outcomes of one activity; every field is a tally, never a difference of two others.
    record Tally(int done,
                 int unchanged,
                 int wouldChange,
                 int withheld,
                 int skippedByMode,
                 int notRead,
                 int notParsed,
                 int writeFailed) {
        public static Tally tally(int done,
                                  int unchanged,
                                  int wouldChange,
                                  int withheld,
                                  int skippedByMode,
                                  int notRead,
                                  int notParsed,
                                  int writeFailed) {
            return new Tally(done, unchanged, wouldChange, withheld, skippedByMode, notRead, notParsed, writeFailed);
        }

        public static Tally empty() {
            return tally(0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    /// What one root of a performed run came to: the mode and where it came from, the number of distinct files the run
    /// acted on in it, one [Tally] per asked activity (none when the activity was not asked), the lint findings by
    /// severity, and whether the root fails the run (errors, or warnings under its `failOnWarning`).
    record RootResult(Path root,
                      JbctMode mode,
                      ModeSource modeSource,
                      Option<String> basePackage,
                      boolean testRoot,
                      String testRootRule,
                      int files,
                      Option<Tally> lint,
                      Option<Tally> format,
                      int errors,
                      int warnings,
                      int infos,
                      boolean fails) {
        public static RootResult rootResult(Path root,
                                            JbctMode mode,
                                            ModeSource modeSource,
                                            Option<String> basePackage,
                                            boolean testRoot,
                                            String testRootRule,
                                            int files,
                                            Option<Tally> lint,
                                            Option<Tally> format,
                                            int errors,
                                            int warnings,
                                            int infos,
                                            boolean fails) {
            return new RootResult(root,
                                  mode,
                                  modeSource,
                                  basePackage,
                                  testRoot,
                                  testRootRule,
                                  files,
                                  lint,
                                  format,
                                  errors,
                                  warnings,
                                  infos,
                                  fails);
        }
    }

    /// What a performed run came to: one [FileResult] per distinct file of the plan, one [RootResult] per root, the
    /// findings (file diagnostics in file order, then the run rules'), the verdict, a function of the recorded
    /// outcomes and the invocation, and the lines the run prints: `before` the findings and `after` them. The score
    /// comes in a later step.
    record RunResult(List<FileResult> files,
                     List<RootResult> roots,
                     List<Diagnostic> findings,
                     Verdict verdict,
                     List<Line> before,
                     List<Line> after) {
        public RunResult {
            files = List.copyOf(files);
            roots = List.copyOf(roots);
            findings = List.copyOf(findings);
            before = List.copyOf(before);
            after = List.copyOf(after);
        }

        public static RunResult runResult(List<FileResult> files,
                                          List<RootResult> roots,
                                          List<Diagnostic> findings,
                                          Verdict verdict,
                                          List<Line> before,
                                          List<Line> after) {
            return new RunResult(files, roots, findings, verdict, before, after);
        }
    }

    /// The severity of a printed line.
    enum Level {
        ERROR,
        WARN,
        INFO,
        DETAIL
    }

    /// One sentence the run produced; a front end prints it at its level and holds no sentence of its own.
    record Line(Level level, String text) {
        public static Line line(Level level, String text) {
            return new Line(level, text);
        }
    }

    /// How the run ended, ordered: CLEAN < FINDINGS < INCOMPLETE; NOTHING_COLLECTED when the plan holds no file.
    enum Kind {
        CLEAN,
        FINDINGS,
        INCOMPLETE,
        NOTHING_COLLECTED
    }

    /// The verdict of a run: a function of the tallies, the plan and the invocation; `failure` is the sentence a
    /// build failure carries, present for FINDINGS and INCOMPLETE.
    record Verdict(Kind kind, Option<String> failure) {
        public static Verdict clean() {
            return new Verdict(Kind.CLEAN, none());
        }

        public static Verdict findings(String failure) {
            return new Verdict(Kind.FINDINGS, some(failure));
        }

        public static Verdict incomplete(String failure) {
            return new Verdict(Kind.INCOMPLETE, some(failure));
        }

        public static Verdict nothingCollected() {
            return new Verdict(Kind.NOTHING_COLLECTED, none());
        }
    }
}
