package org.pragmatica.jbct.maven;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.cst.LintRun;
import org.pragmatica.jbct.lint.cst.RootResolver.RootPlan;
import org.pragmatica.jbct.lint.cst.RunOutcome.Invocation;
import org.pragmatica.jbct.lint.cst.RunOutcome.Level;
import org.pragmatica.jbct.lint.cst.RunOutcome.Line;
import org.pragmatica.jbct.lint.cst.RunOutcome.RunResult;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;

/// Records the [RunResult] a goal's run returned through the seam, so a test asserts the goal's log against the text the
/// run built (the goal adds no sentence of its own) and never against a copy of that text.
final class RunCapture {
    private RunResult last;
    private List<String> planMessages = List.of();

    <M extends AbstractJbctMojo> M on(M mojo) {
        mojo.performer = (plan, invocation) -> perform(plan, invocation);

        return mojo;
    }

    /// A run that is refused returns no result: the previous goal's result must not be read as this one's.
    private Result<RunResult> perform(RootPlan plan, Invocation invocation) {
        last = null;
        planMessages = plan.messages().stream().map(Object::toString).toList();

        return LintRun.lintRun(plan)
                      .perform(invocation)
                      .onSuccess(result -> last = result);
    }

    /// The run's lines at one level, in the order the goal logs them: before the findings, then after them.
    List<String> texts(Level level) {
        var all = new ArrayList<Line>(last.before());

        all.addAll(last.after());

        return all.stream()
                  .filter(line -> line.level() == level)
                  .map(Line::text)
                  .toList();
    }

    /// Every line the run returned, before and after the findings, whatever its level.
    List<String> lines() {
        var all = new ArrayList<Line>();

        if (last != null) {
            all.addAll(last.before());
            all.addAll(last.after());
        }

        return all.stream()
                  .map(Line::text)
                  .toList();
    }

    String failure() {
        return last.verdict()
                   .failure()
                   .or("");
    }

    /// Precondition and containment in one place: a goal whose run returned a result logged every line of it, and that
    /// result carried at least one line (a run that returns no lines would make the containment hold for nothing). A
    /// goal whose run was refused returned no result: nothing to compare.
    void assertLogged(List<String> logged) {
        if (last == null) {
            return;
        }
        assertThat(lines()).as("a run that returned a result returns at least one line").isNotEmpty();
        assertThat(logged).containsAll(lines());
    }

    /// Exactly what the goal must log for the run it performed, in order, one entry per log call as `LEVEL text`: the
    /// plan's messages, the run's `before` lines, the findings, the run's `after` lines. Nothing else.
    List<String> expectedLog() {
        var expected = new ArrayList<String>();

        planMessages.forEach(message -> expected.add("INFO " + message));
        last.before().forEach(line -> expected.add(entry(line)));
        last.findings().forEach(d -> expected.add(severity(d) + " [" + d.ruleId() + "] " + d.file() + ":" + d.line() + ":" + d.column() + " - " + d.message()));
        last.after().forEach(line -> expected.add(entry(line)));

        return expected;
    }

    private static String entry(Line line) {
        return switch (line.level()) {
            case ERROR -> "ERROR ";
            case WARN -> "WARN ";
            case INFO -> "INFO ";
            case DETAIL -> "DEBUG ";
        } + line.text();
    }

    private static String severity(Diagnostic diagnostic) {
        return switch (diagnostic.severity()) {
            case ERROR -> "ERROR";
            case WARNING -> "WARN";
            case INFO -> "INFO";
        };
    }
}
