package org.pragmatica.jbct.lint.cst;

import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.cst.RunOutcome.Command;
import org.pragmatica.jbct.lint.cst.RunOutcome.DensityLimit;
import org.pragmatica.jbct.lint.cst.RunOutcome.FileResult;
import org.pragmatica.jbct.lint.cst.RunOutcome.Invocation;
import org.pragmatica.jbct.lint.cst.RunOutcome.Linted;
import org.pragmatica.jbct.lint.cst.RunOutcome.ParseFailed;
import org.pragmatica.jbct.lint.cst.RunOutcome.ReadFailed;
import org.pragmatica.jbct.score.DensityGate;
import org.pragmatica.jbct.score.ScoreCalculator;
import org.pragmatica.jbct.score.ScoreResult;
import org.pragmatica.jbct.score.SourceScan;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The score of a performed run (#2525), built from the run's own recorded outcomes: the diagnostics and line counts of
/// the files whose lint outcome is `Linted`, and the count of files that were read or parsed unsuccessfully as the
/// unanalysed ones. A file skipped by mode is in neither number. No file is read here: `perform` read each file once.
sealed interface RunScore permits RunScore.unused {
    record unused() implements RunScore {}

    /// The score of a SCORE invocation that scored at least one file; none otherwise (nothing to measure).
    static Option<ScoreResult> scoreOf(Invocation invocation, List<FileResult> files) {
        var scored = files.stream()
                          .filter(file -> file.lint() instanceof Linted)
                          .toList();

        return invocation.command() == Command.SCORE && !scored.isEmpty()
               ? some(ScoreCalculator.calculate(scan(scored, unanalysed(files))))
               : none();
    }

    /// The gate's breach message when a limit was given, something was scored and the density exceeds it.
    static Option<String> breachOf(Option<ScoreResult> score, Option<DensityLimit> limit) {
        return Option.all(score, limit)
                     .map(RunScore::breachMessage)
                     .flatMap(message -> message);
    }

    private static Option<String> breachMessage(ScoreResult score, DensityLimit limit) {
        return DensityGate.exceeds(score.totalDensityPerKloc(), limit.perKloc())
               ? some(DensityGate.breachMessage(score.totalDensityPerKloc(), limit.perKloc()))
               : none();
    }

    private static SourceScan scan(List<FileResult> scored, int unanalysed) {
        return new SourceScan(scored.stream()
                                    .flatMap(RunScore::diagnosticsOf)
                                    .toList(),
                              scored.stream()
                                    .mapToInt(FileResult::linesOfCode)
                                    .sum(),
                              scored.size(),
                              unanalysed);
    }

    private static Stream<Diagnostic> diagnosticsOf(FileResult file) {
        return file.lint() instanceof Linted(var diagnostics)
               ? diagnostics.stream()
               : Stream.empty();
    }

    private static int unanalysed(List<FileResult> files) {
        return (int) files.stream()
                          .filter(file -> file.lint() instanceof ReadFailed || file.lint() instanceof ParseFailed)
                          .count();
    }
}
