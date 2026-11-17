package org.pragmatica.jbct.maven;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.jbct.format.JbctFormatter;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.cst.LintRun;
import org.pragmatica.jbct.lint.cst.LintRun.RunReport;
import org.pragmatica.jbct.shared.AnalysisCoverage;
import org.pragmatica.jbct.shared.SourceFile;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;


/// Maven goal combining format check and lint (for CI).
@Mojo(name = "check", defaultPhase = LifecyclePhase.VERIFY)
public class CheckMojo extends AbstractJbctMojo {
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
        if (shouldSkip("check")) {
            return;
        }

        var plan = resolvePlan(includeTests);

        if (plan.files().isEmpty()) {
            reportNothingToCheck("check", includeTests);

            return;
        }

        var run = LintRun.lintRun(plan);

        announceDisabled(plan, "Not formatted");
        var needsFormatting = new ArrayList<Path>();
        var formatErrors = new AtomicInteger(0);
        var allDiagnostics = new ArrayList<Diagnostic>();
        var parseErrors = new AtomicInteger(0);

        for (var job : formatJobs(plan)) {
            checkFile(job, run, needsFormatting, formatErrors, allDiagnostics, parseErrors);
        }

        var report = finishRun(run);

        allDiagnostics.addAll(report.diagnostics());
        getLog().info("Running JBCT check on " + checked(report) + " Java file(s)");
        layerCoverage(plan);
        var coverage = AnalysisCoverage.analysisCoverage(checked(report), parseErrors.get());
        // Report format issues
        if (!needsFormatting.isEmpty()) {
            getLog().error("Files not properly formatted:");
            for (var file : needsFormatting) {
                getLog().error("  " + file);
            }
        }
        // Report lint issues
        reportDiagnostics(allDiagnostics);
        // Summary. `Running JBCT check on N Java file(s)` above counts the files no root skipped; this
        // line carries what was ANALYSED, because a file that could not be read or parsed contributes
        // zero of everything, and an unqualified `0 lint error(s)` reads as clean when it means
        // unexamined (#977). This is the line quoted as gate evidence, so it is the line that has to
        // be honest about its own denominator.
        coverage.gapReport("check").onPresent(getLog()::error);
        getLog().info("Check results (checked " + coverage.render()
                     + "): " + needsFormatting.size()
                     + " format issue(s), " + errorsIn(report)
                     + " lint error(s), " + warningsIn(report)
                     + " warning(s)");
        // Fail build if needed; the run names the failing root(s) with their counts (F4).
        var failures = new ArrayList<String>();

        if (!needsFormatting.isEmpty()) {
            var fileList = new StringBuilder();

            for (var file : needsFormatting) {
                fileList.append("\n  ").append(file);
            }

            failures.add(needsFormatting.size() + " file(s) need formatting:" + fileList);
        }

        if (formatErrors.get() > 0) {
            failures.add(formatErrors.get() + " format check error(s)");
        }

        if (coverage.isPartial()) {
            failures.add(coverage.unparseable() + " file(s) could not be read or parsed and were NOT analysed");
        }

        report.failure().onPresent(failures::add);
        if (!failures.isEmpty()) {
            throw new MojoFailureException("JBCT check failed: " + String.join(", ", failures));
        }

        getLog().info(verdict(report));
    }

    /// The pass line, worded by what ran: a run in which no root ran a rule never claims a lint pass.
    private static String verdict(RunReport report) {
        if (checked(report) == 0) {
            return "Nothing was checked: every source root is skipped by its mode. This is not a pass.";
        }

        return report.ranNoRule()
               ? "JBCT format check passed; no JBCT rule ran in any source root, so nothing was linted."
               : "JBCT check passed.";
    }

    private void checkFile(FormatJob job,
                           LintRun run,
                           List<Path> needsFormatting,
                           AtomicInteger formatErrors,
                           List<Diagnostic> allDiagnostics,
                           AtomicInteger parseErrors) {
        run.claimFormat(job.file())
           .onSuccess(claimed -> {
                          if (claimed) {
                              checkFormat(job.file(),
                                          job.formatter(),
                                          needsFormatting,
                                          formatErrors);
                          }
                      })
           .onFailure(cause -> {
                          formatErrors.incrementAndGet();
                          getLog().error("Error checking format of " + job.file() + ": " + cause.message());
                      });
        checkLint(job.file(), run, allDiagnostics, parseErrors);
    }

    private void checkFormat(Path file, JbctFormatter formatter, List<Path> needsFormatting, AtomicInteger errors) {
        SourceFile.sourceFile(file)
                  .flatMap(formatter::isFormatted)
                  .onSuccess(isFormatted -> {
                      if (!isFormatted) {
                          needsFormatting.add(file);
                      }
                  })
                  .onFailure(cause -> {
                                 errors.incrementAndGet();
                                 getLog().error("Error checking format of " + file + ": " + cause.message());
                             });
    }

    private void checkLint(Path file, LintRun run, List<Diagnostic> allDiagnostics, AtomicInteger parseErrors) {
        run.lintFile(file)
           .onSuccess(allDiagnostics::addAll)
           .onFailure(cause -> {
                          parseErrors.incrementAndGet();
                          getLog().error("Parse error in " + file + ": " + cause.message());
                      });
    }
}
