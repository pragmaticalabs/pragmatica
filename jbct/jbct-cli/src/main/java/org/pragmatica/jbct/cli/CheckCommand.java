package org.pragmatica.jbct.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.jbct.format.JbctFormatter;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.cst.LintRun;
import org.pragmatica.jbct.lint.cst.LintRun.RunReport;
import org.pragmatica.jbct.lint.cst.RootResolver.RootPlan;
import org.pragmatica.jbct.shared.AnalysisCoverage;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Option;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;


/// Check command - combines format check and lint (for CI).
@Command(name = "check", description = "Run both format check and lint (for CI)", mixinStandardHelpOptions = true)
public class CheckCommand implements Callable<Integer> {
    @Parameters(paramLabel = "<path>", description = "Files or directories to check", arity = "1..*")
    List<Path> paths;

    @picocli.CommandLine.Option(names = {"--fail-on-warning", "-w"}, description = "Treat warnings as errors")
    boolean failOnWarning;

    @picocli.CommandLine.Option(names = {"--verbose", "-v"}, description = "Show verbose output")
    boolean verbose;

    @picocli.CommandLine.Option(names = {"--config"}, description = "Path to configuration file")
    Path configPath;

    @picocli.CommandLine.Option(names = {"--mode"}, description = "JBCT mode for this run, overriding jbct.toml and --config: business, infrastructure, format-only or disabled")
    String mode;

    /// The one place the resolved plan is handed to the run; tests replace it to observe or adjust it (#2525).
    Function<RootPlan, LintRun> runFactory = LintRun::lintRun;

    @Override
    public Integer call() {
        var planned = CliRoots.plan(Option.option(configPath), Option.option(mode), paths, failOnWarning, System.err);

        if (planned.isEmpty()) {
            return CliConfig.CONFIG_ERROR_EXIT;
        }

        var plan = planned.unwrap();
        var run = runFactory.apply(plan);
        var jobs = CliRoots.jobs(plan);

        if (jobs.isEmpty()) {
            System.out.println("No Java files found.");

            return 0;
        }

        if (verbose) {
            System.out.println("Checking " + jobs.size() + " Java file(s)...");
        }

        CliRoots.announceDisabled(plan, System.err);
        var needsFormatting = new ArrayList<Path>();
        var formatErrors = new AtomicInteger(0);
        var allDiagnostics = new ArrayList<Diagnostic>();
        var parseErrors = new AtomicInteger(0);

        for (var job : jobs) {
            checkFile(job, run, needsFormatting, formatErrors, allDiagnostics, parseErrors);
        }

        var finished = run.finish().onFailure(cause -> System.err.println("Error: " + cause.message())).option();

        if (finished.isEmpty()) {
            return CliConfig.CONFIG_ERROR_EXIT;
        }

        var report = finished.unwrap();

        allDiagnostics.addAll(report.diagnostics());
        CliRoots.layerCoverage(plan, System.err);
        var coverage = AnalysisCoverage.analysisCoverage(CliRoots.checked(report), parseErrors.get());

        if (!needsFormatting.isEmpty()) {
            System.out.println();
            System.out.println("Files not properly formatted:");
            for (var file : needsFormatting) {
                System.out.println("  " + file);
            }
        }

        if (!allDiagnostics.isEmpty()) {
            System.out.println();
            for (var d : allDiagnostics) {
                System.out.print(d.toHumanReadable());
            }
        }

        report.summary().forEach(System.out::println);
        // Summary. The counts are meaningless without the denominator they were taken over: a file
        // that could not be read or parsed contributes zero of everything, so an unqualified
        // `0 lint error(s)` reads as clean when it means unexamined (#977).
        System.out.println();
        coverage.gapReport("check").onPresent(System.out::println);
        System.out.println("Check results (checked " + coverage.render()
                          + "): " + needsFormatting.size()
                          + " format issue(s), " + CliRoots.errors(report)
                          + " lint error(s), " + CliRoots.warnings(report)
                          + " warning(s)");
        report.failure().onPresent(System.out::println);
        if (formatErrors.get() > 0 || coverage.isPartial()) {
            return 2;
        }

        if (!needsFormatting.isEmpty() || report.fails()) {
            return 1;
        }

        System.out.println(verdict(report, coverage));

        return 0;
    }

    /// The pass line, worded by what ran: a run in which no root ran a rule never claims a lint pass.
    private static String verdict(RunReport report, AnalysisCoverage coverage) {
        if (coverage.collected() == 0) {
            return "Nothing was checked: every source root is skipped by its mode. This is not a pass.";
        }

        return report.ranNoRule()
               ? "✓ Format checks passed; no JBCT rule ran in any source root, so nothing was linted."
               : "✓ All checks passed.";
    }

    private void checkFile(CliRoots.FileJob job,
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
                          System.err.println("  ✗ format error: " + job.file() + ": " + cause.message());
                      });
        checkLint(job.file(), run, allDiagnostics, parseErrors);
    }

    private void checkFormat(Path file, JbctFormatter formatter, List<Path> needsFormatting, AtomicInteger errors) {
        SourceFile.sourceFile(file)
                  .flatMap(formatter::isFormatted)
                  .onSuccess(isFormatted -> {
                                 if (!isFormatted) {
                                     needsFormatting.add(file);
                                 } else if (verbose) {
                                     System.out.println("  ✓ format: " + file.getFileName());
                                 }
                             })
                  .onFailure(cause -> {
                                 errors.incrementAndGet();
                                 System.err.println("  ✗ format error: " + file + ": " + cause.message());
                             });
    }

    private void checkLint(Path file, LintRun run, List<Diagnostic> allDiagnostics, AtomicInteger parseErrors) {
        run.lintFile(file)
           .onSuccess(diagnostics -> {
                          allDiagnostics.addAll(diagnostics);
                          if (verbose && diagnostics.isEmpty()) {
                              System.out.println("  ✓ lint: " + file.getFileName());
                          }
                      })
           .onFailure(cause -> {
                          parseErrors.incrementAndGet();
                          System.err.println("  ✗ parse error: " + file + ": " + cause.message());
                      });
    }
}
