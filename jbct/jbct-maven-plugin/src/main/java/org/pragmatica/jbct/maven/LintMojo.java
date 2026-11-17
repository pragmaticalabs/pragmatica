package org.pragmatica.jbct.maven;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.cst.LintRun;
import org.pragmatica.jbct.shared.AnalysisCoverage;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;


/// Maven goal for linting Java source files for JBCT compliance.
@Mojo(name = "lint", defaultPhase = LifecyclePhase.VERIFY)
public class LintMojo extends AbstractJbctMojo {
    /// Whether `src/test/java` is collected alongside `src/main/java`.
    ///
    /// Declared per goal: there is no inherited field to shadow, which is what made this parameter
    /// inert for the format-family goals (#624). The default is `false` for every goal — test
    /// sources have never been in the gate, so honouring the value this parameter USED to claim
    /// would newly admit them wholesale; that is a policy change, deliberately not bundled with the
    /// mechanism fix. Set `-Djbct.includeTests=true` to opt in.
    @Parameter(property = "jbct.includeTests", defaultValue = "false")
    protected boolean includeTests;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (shouldSkip("lint")) {
            return;
        }

        var plan = resolvePlan(includeTests);

        if (plan.files().isEmpty()) {
            reportNothingToCheck("lint", includeTests);

            return;
        }

        var run = LintRun.lintRun(plan);
        var allDiagnostics = new ArrayList<Diagnostic>();
        var parseErrors = new AtomicInteger(0);

        for (var file : plan.files()) {
            processFile(file, run, allDiagnostics, parseErrors);
        }

        var report = finishRun(run);

        allDiagnostics.addAll(report.diagnostics());
        // `Linting N Java file(s)` counts the files the rules examined, plus the ones they could not read: files
        // skipped by their root's mode are neither.
        var linted = examined(report) + parseErrors.get();

        getLog().info("Linting " + linted + " Java file(s)");
        layerCoverage(plan);
        var coverage = AnalysisCoverage.analysisCoverage(linted, parseErrors.get());
        // Print diagnostics
        reportDiagnostics(allDiagnostics);
        // Print summary. `Linting N Java file(s)` above counts what the rules examined; this line
        // carries what was ANALYSED, because that is the denominator the counts were taken over (#977).
        coverage.gapReport("lint").onPresent(getLog()::error);
        getLog().info("Lint results (checked " + coverage.render()
                     + "): " + errorsIn(report)
                     + " error(s), " + warningsIn(report)
                     + " warning(s), " + infosIn(allDiagnostics)
                     + " info(s)");
        // Fail build if needed. Every reason is named: the old message reported the LINT ERROR count
        // for a failure caused by a parse error, so a build stopped by files it could not read said
        // `JBCT lint found 0 error(s)` — true, and the opposite of an explanation. The run names the
        // failing root(s) with their counts (F4).
        var failures = new ArrayList<String>();

        if (coverage.isPartial()) {
            failures.add(coverage.unparseable() + " file(s) could not be read or parsed and were NOT analysed");
        }

        report.failure().onPresent(failures::add);
        if (!failures.isEmpty()) {
            throw new MojoFailureException("JBCT lint failed: " + String.join(", ", failures));
        }
    }

    private void processFile(Path file, LintRun run, List<Diagnostic> allDiagnostics, AtomicInteger parseErrors) {
        run.lintFile(file)
           .onSuccess(allDiagnostics::addAll)
           .onFailure(cause -> {
                          parseErrors.incrementAndGet();
                          getLog().error("Parse error in " + file + ": " + cause.message());
                      });
    }
}
