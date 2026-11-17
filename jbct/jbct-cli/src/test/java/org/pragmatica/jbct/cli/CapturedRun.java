package org.pragmatica.jbct.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.cst.RootResolver.RootPlan;
import org.pragmatica.jbct.lint.cst.RunOutcome.Invocation;
import org.pragmatica.jbct.lint.cst.RunOutcome.Level;
import org.pragmatica.jbct.lint.cst.RunOutcome.Line;
import org.pragmatica.jbct.lint.cst.RunOutcome.RunResult;
import org.pragmatica.lang.Result;

/// The seam of a front-end fixture (#2525): it performs the run the way the command would and keeps the result the command
/// received, so a test compares what the command printed with that very result and never with a second `perform` over the
/// same tree, and never with a sentence of its own (the sentences live in `RunLinesTest`).
final class CapturedRun implements BiFunction<RootPlan, Invocation, Result<RunResult>> {
    private final List<RunResult> received = new ArrayList<>();

    @Override
    public Result<RunResult> apply(RootPlan plan, Invocation invocation) {
        return CliRoots.PERFORM.apply(plan, invocation)
                               .onSuccess(received::add);
    }

    /// The one result the command received.
    RunResult result() {
        return received.getFirst();
    }

    /// How many times the command asked the run.
    int performs() {
        return received.size();
    }

    /// What the command prints to stdout: the lines before the findings, the findings, the lines after them; DETAIL lines only when `verbose`.
    String expectedStdout(boolean verbose) {
        return Stream.of(textOf(result().before(), verbose),
                         result().findings()
                                 .stream()
                                 .map(finding -> finding.toHumanReadable())
                                 .collect(Collectors.joining()),
                         textOf(result().after(), verbose))
                     .collect(Collectors.joining());
    }

    /// The lines the command prints to stderr (ERROR lines), in order.
    List<String> expectedStderrLines() {
        return Stream.concat(result().before().stream(), result().after().stream())
                     .filter(line -> line.level() == Level.ERROR)
                     .map(Line::text)
                     .toList();
    }

    /// The text of every line printed to stdout, one entry per line, in order, findings excluded.
    List<String> stdoutLines(boolean verbose) {
        return Stream.concat(result().before().stream(), result().after().stream())
                     .filter(line -> toStdout(line, verbose))
                     .map(Line::text)
                     .toList();
    }

    private static String textOf(List<Line> lines, boolean verbose) {
        return lines.stream()
                    .filter(line -> toStdout(line, verbose))
                    .map(line -> line.text() + System.lineSeparator())
                    .collect(Collectors.joining());
    }

    private static boolean toStdout(Line line, boolean verbose) {
        return line.level() == Level.WARN || line.level() == Level.INFO || (verbose && line.level() == Level.DETAIL);
    }
}
