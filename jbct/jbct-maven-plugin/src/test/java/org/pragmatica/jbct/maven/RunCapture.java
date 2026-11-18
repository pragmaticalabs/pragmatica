package org.pragmatica.jbct.maven;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.jbct.lint.cst.LintRun;
import org.pragmatica.jbct.lint.cst.RootResolver.RootPlan;
import org.pragmatica.jbct.lint.cst.RunOutcome.Invocation;
import org.pragmatica.jbct.lint.cst.RunOutcome.Level;
import org.pragmatica.jbct.lint.cst.RunOutcome.Line;
import org.pragmatica.jbct.lint.cst.RunOutcome.RunResult;
import org.pragmatica.lang.Result;

/// Records the [RunResult] a goal's run returned through the seam, so a test asserts the goal's log against the text the
/// run built (the goal adds no sentence of its own) and never against a copy of that text.
final class RunCapture {
    private RunResult last;

    <M extends AbstractJbctMojo> M on(M mojo) {
        mojo.performer = (plan, invocation) -> perform(plan, invocation);

        return mojo;
    }

    /// A run that is refused returns no result: the previous goal's result must not be read as this one's.
    private Result<RunResult> perform(RootPlan plan, Invocation invocation) {
        last = null;

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
}
