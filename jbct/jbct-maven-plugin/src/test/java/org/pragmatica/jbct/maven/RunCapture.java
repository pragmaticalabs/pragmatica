package org.pragmatica.jbct.maven;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.jbct.lint.cst.LintRun;
import org.pragmatica.jbct.lint.cst.RunOutcome.Level;
import org.pragmatica.jbct.lint.cst.RunOutcome.Line;
import org.pragmatica.jbct.lint.cst.RunOutcome.RunResult;

/// Records the [RunResult] a goal's run returned through the seam, so a test asserts the goal's log against the text the
/// run built (the goal adds no sentence of its own) and never against a copy of that text.
final class RunCapture {
    private RunResult last;

    <M extends AbstractJbctMojo> M on(M mojo) {
        mojo.performer = (plan, invocation) -> LintRun.lintRun(plan)
                                                      .perform(invocation)
                                                      .onSuccess(result -> last = result);

        return mojo;
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

    String failure() {
        return last.verdict()
                   .failure()
                   .or("");
    }
}
