package org.pragmatica.jbct.maven;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.jbct.format.JbctFormatter;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.DiagnosticSeverity;
import org.pragmatica.jbct.lint.cst.LintRun;
import org.pragmatica.jbct.parser.Java25Parser;
import org.pragmatica.jbct.shared.AnalysisCoverage;
import org.pragmatica.jbct.shared.SourceFile;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;


/// Maven goal that performs JBCT lint and format in a single pass per file.
///
/// For each Java source file the goal:
///
///   1. Reads the file (files exceeding `[files] maxFileSize` in `jbct.toml`, or
///      matching `[files] excludes`, are filtered out by `FileCollector` upstream).
///   2. Parses the file once into a CST.
///   3. Runs lint analysis on the CST (read-only, collects diagnostics).
///   4. Runs format on the CST (produces reformatted text).
///   5. Writes the reformatted file if the content changed.
///   6. Emits lint diagnostics.
///   7. Discards the CST (GC-eligible at next iteration).
///
/// Constant memory: one CST live at a time. One parse per file, instead of two
/// (which is what running `format` then `lint` as separate goals would cost).
///
/// The standalone `format` and `lint` goals remain available for users who want
/// only one of the two operations.
@Mojo(name = "process", defaultPhase = LifecyclePhase.PROCESS_SOURCES, threadSafe = true)
public class ProcessMojo extends AbstractJbctMojo {
    /// Whether `src/test/java` is collected alongside `src/main/java`.
    ///
    /// Declared per goal: there is no inherited field to shadow, which is what made this parameter
    /// inert for the format-family goals (#624). The default is `false` for every goal — test
    /// sources have never been in the gate, so honouring the value this parameter USED to claim
    /// would newly admit them wholesale; that is a policy change, deliberately not bundled with the
    /// mechanism fix. Set `-Djbct.includeTests=true` to opt in.
    @Parameter(property = "jbct.includeTests", defaultValue = "false")
    protected boolean includeTests;

    /// If true, lint errors at the configured failure threshold prevent the format write
    /// for that specific file. Default false — format always writes; lint failures still
    /// fail the build at the end.
    @Parameter(property = "jbct.failBeforeFormat", defaultValue = "false")
    protected boolean failBeforeFormat;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (shouldSkip("process")) {
            return;
        }

        var plan = resolvePlan(includeTests);

        if (plan.files().isEmpty()) {
            reportNothingToCheck("process", includeTests);

            return;
        }

        var run = LintRun.lintRun(plan);
        var parser = new Java25Parser();
        var formatted = new AtomicInteger(0);
        var unchanged = new AtomicInteger(0);
        var formatErrors = new AtomicInteger(0);
        var parseErrors = new AtomicInteger(0);
        // Counted apart from formatErrors: an unreadable file was neither formatted NOR linted, so it
        // belongs to the coverage gap, not to the format tally it used to inflate (#977).
        var unreadable = new AtomicInteger(0);
        var allDiagnostics = new ArrayList<Diagnostic>();

        for (var job : formatJobs(plan)) {
            claimAndProcess(job,
                            parser,
                            run,
                            formatted,
                            unchanged,
                            formatErrors,
                            parseErrors,
                            unreadable,
                            allDiagnostics);
        }

        var report = finishRun(run);

        allDiagnostics.addAll(report.diagnostics());
        getLog().info("Processing " + formatted(report) + " Java file(s)");
        layerCoverage(plan, report);
        // A file the run recorded as not parsed, plus the ones this goal could not read or parse before the run saw them
        // (a format-time failure is not recorded by the run): the run's own lines never claim more than they covered.
        var notParsedTotal = notParsed(report) + parseErrors.get() + unreadable.get();
        var coverage = AnalysisCoverage.analysisCoverage(examined(report) + notParsedTotal, notParsedTotal);

        reportDiagnostics(allDiagnostics);
        // The run's own outcome lines say how many files were formatted and linted, and which were skipped by mode or
        // not parsed: a root that only formatted its files has no lint line of its own to claim (#977).
        coverage.gapReport("process").onPresent(getLog()::error);
        getLog().info("Formatted: " + formatted.get()
                     + ", Unchanged: " + unchanged.get()
                     + ", Errors: " + formatErrors.get());
        report.formatLines().forEach(getLog()::info);
        report.lintLines().forEach(getLog()::info);
        if (examined(report) > 0) {
            getLog().info("Findings: " + errorsIn(report)
                         + " error(s), " + warningsIn(report)
                         + " warning(s), " + infosIn(allDiagnostics)
                         + " info(s)");
        }

        if (formatErrors.get() > 0) {
            throw new MojoFailureException("Formatting failed for " + formatErrors.get() + " file(s)");
        }
        // Every reason is named: the old message reported the LINT ERROR count for a failure caused by
        // a parse error, so a build stopped by files it could not read said `JBCT lint found 0
        // error(s)` — true, and the opposite of an explanation. The run names the failing root(s) with
        // their counts (F4).
        var failures = new ArrayList<String>();

        if (coverage.isPartial()) {
            failures.add(coverage.unparseable() + " file(s) could not be read or parsed and were NOT analysed");
        }

        report.failure().onPresent(failures::add);
        if (!failures.isEmpty()) {
            throw new MojoFailureException("JBCT process failed: " + String.join(", ", failures));
        }
    }

    private void claimAndProcess(FormatJob job,
                                 Java25Parser parser,
                                 LintRun run,
                                 AtomicInteger formatted,
                                 AtomicInteger unchanged,
                                 AtomicInteger formatErrors,
                                 AtomicInteger parseErrors,
                                 AtomicInteger unreadable,
                                 List<Diagnostic> allDiagnostics) {
        run.claimFormat(job.file())
           .onSuccess(claimed -> {
                          if (claimed) {
                              processFile(job.file(),
                                          parser,
                                          job.formatter(),
                                          run,
                                          formatted,
                                          unchanged,
                                          formatErrors,
                                          parseErrors,
                                          unreadable,
                                          allDiagnostics);
                          }
                      })
           .onFailure(cause -> {
                          unreadable.incrementAndGet();
                          getLog().error("Cannot process " + job.file() + ": " + cause.message());
                      });
    }

    private void processFile(Path file,
                             Java25Parser parser,
                             JbctFormatter formatter,
                             LintRun run,
                             AtomicInteger formatted,
                             AtomicInteger unchanged,
                             AtomicInteger formatErrors,
                             AtomicInteger parseErrors,
                             AtomicInteger unreadable,
                             List<Diagnostic> allDiagnostics) {
        SourceFile.sourceFile(file)
                  .onSuccess(source -> handleParsedFile(file,
                                                        source,
                                                        parser,
                                                        formatter,
                                                        run,
                                                        formatted,
                                                        unchanged,
                                                        formatErrors,
                                                        parseErrors,
                                                        allDiagnostics))
                  .onFailure(cause -> {
                                 unreadable.incrementAndGet();
                                 getLog().error("Error reading " + file + ": " + cause.message());
                             });
    }

    private void handleParsedFile(Path file,
                                  SourceFile source,
                                  Java25Parser parser,
                                  JbctFormatter formatter,
                                  LintRun run,
                                  AtomicInteger formatted,
                                  AtomicInteger unchanged,
                                  AtomicInteger formatErrors,
                                  AtomicInteger parseErrors,
                                  List<Diagnostic> allDiagnostics) {
        var parseResult = parser.parse(source.content());

        if (!parseResult.isSuccess()) {
            parseErrors.incrementAndGet();
            parseResult.onFailure(cause -> getLog().error("Parse error in " + file + ": " + cause.message()));

            return;
        }

        var tree = parseResult.unwrap();
        // Lint first so diagnostics reference the as-authored source.
        var linted = run.lintParsed(tree, source);
        // The run records a lint failure as not parsed; it is not counted here a second time.
        if (linted.isFailure()) {
            linted.onFailure(cause -> getLog().error("Cannot lint " + file + ": " + cause.message()));

            return;
        }

        var diagnostics = linted.unwrap();
        var lintHasErrors = diagnostics.stream().anyMatch(d -> d.severity() == DiagnosticSeverity.ERROR);

        if (failBeforeFormat && lintHasErrors) {
            allDiagnostics.addAll(diagnostics);

            return;
        }
        // Format using the same CST.
        var formattedSource = formatter.formatParsed(tree, source);

        if (formattedSource.content().equals(source.content())) {
            unchanged.incrementAndGet();
        } else {
            formattedSource.write()
                           .onSuccess(written -> {
                               formatted.incrementAndGet();
                               getLog().warn("Formatted: " + file);
                           })
                           .onFailure(cause -> {
                                          formatErrors.incrementAndGet();
                                          getLog().error("Error writing " + file + ": " + cause.message());
                                      });
        }

        allDiagnostics.addAll(diagnostics);
    }
}
