package org.pragmatica.jbct.maven;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

        for (var file : plan.files()) {
            processFile(file, run, allDiagnostics);
        }

        var report = finishRun(run);

        allDiagnostics.addAll(report.diagnostics());
        layerCoverage(plan, report);
        var notParsed = notParsed(report);
        var coverage = AnalysisCoverage.analysisCoverage(examined(report) + notParsed, notParsed);
        // Print diagnostics
        reportDiagnostics(allDiagnostics);
        // The run's own outcome lines say how many files were linted and which were not parsed or skipped by mode (#977).
        coverage.gapReport("lint").onPresent(getLog()::error);
        report.lintLines().forEach(getLog()::info);
        getLog().info("Findings: " + errorsIn(report)
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

    private void processFile(Path file, LintRun run, List<Diagnostic> allDiagnostics) {
        run.lintFile(file)
           .onSuccess(allDiagnostics::addAll)
           .onFailure(cause -> {
                          getLog().error("Parse error in " + file + ": " + cause.message());
                      });
    }
}
