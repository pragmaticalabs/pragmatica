package org.pragmatica.jbct.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import org.pragmatica.jbct.format.JbctFormatter;
import org.pragmatica.jbct.shared.AnalysisCoverage;
import org.pragmatica.jbct.lint.cst.LintRun;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Option;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;


/// Format command for JBCT code formatting.
@Command(name = "format", description = "Format Java source files according to JBCT style", mixinStandardHelpOptions = true)
public class FormatCommand implements Callable<Integer> {
    @Parameters(paramLabel = "<path>", description = "Files or directories to format", arity = "1..*")
    List<Path> paths;

    @picocli.CommandLine.Option(names = {"--check", "-c"}, description = "Check if files are formatted without modifying them")
    boolean checkOnly;

    @picocli.CommandLine.Option(names = {"--dry-run", "-n"}, description = "Show what would be changed without modifying files")
    boolean dryRun;

    @picocli.CommandLine.Option(names = {"--verbose", "-v"}, description = "Show verbose output")
    boolean verbose;

    @picocli.CommandLine.Option(names = {"--config"}, description = "Path to configuration file")
    Path configPath;

    @picocli.CommandLine.Option(names = {"--mode"}, description = "JBCT mode for this run, overriding jbct.toml and --config: business, infrastructure, format-only or disabled")
    String mode;

    @Override
    public Integer call() {
        var planned = CliRoots.plan(Option.option(configPath), Option.option(mode), paths, false, System.err);

        if (planned.isEmpty()) {
            return CliConfig.CONFIG_ERROR_EXIT;
        }

        var plan = planned.unwrap();
        var jobs = CliRoots.jobs(plan);

        if (jobs.isEmpty()) {
            System.out.println("No Java files found.");

            return 0;
        }

        CliRoots.announceDisabled(plan, System.err);
        var run = LintRun.lintRun(plan);

        if (verbose) {
            System.out.println("Found " + jobs.size() + " Java file(s) to process.");
        }

        var counters = new int[3];
        // 0=formatted, 1=unchanged, 2=errors
        var needsFormatting = new ArrayList<Path>();

        for (var job : jobs) {
            claimAndProcess(job, run, counters, needsFormatting);
        }

        var finished = run.finish().onFailure(cause -> System.err.println("Error: " + cause.message())).option();

        if (finished.isEmpty()) {
            return CliConfig.CONFIG_ERROR_EXIT;
        }

        var report = finished.unwrap();
        // Print summary
        report.summary().forEach(System.out::println);
        printSummary(counters[0], counters[1], counters[2], needsFormatting, CliRoots.checked(report));
        // Return appropriate exit code
        if (counters[2]> 0) {
            return 2;
        }

        if (checkOnly && !needsFormatting.isEmpty()) {
            return 1;
        }

        return 0;
    }

    private void claimAndProcess(CliRoots.FileJob job, LintRun run, int[] counters, List<Path> needsFormatting) {
        run.claimFormat(job.file())
           .onSuccess(claimed -> {
               if (claimed) {
                   processFile(job, counters, needsFormatting);
               }
           })
           .onFailure(cause -> {
               counters[2]++;
               System.err.println("  error: " + job.file() + " - " + cause.message());
           });
    }

    private void processFile(CliRoots.FileJob job, int[] counters, List<Path> needsFormatting) {
        var file = job.file();

        SourceFile.sourceFile(file)
                  .flatMap(source -> checkAndFormat(job.formatter(),
                                                    source,
                                                    file,
                                                    counters,
                                                    needsFormatting))
                  .onFailure(cause -> {
                      counters[2]++;
                      System.err.println("  error: " + file + " - " + cause.message());
                  });
    }

    private org.pragmatica.lang.Result<SourceFile> checkAndFormat(JbctFormatter formatter,
                                                                  SourceFile source,
                                                                  Path file,
                                                                  int[] counters,
                                                                  List<Path> needsFormatting) {
        return formatter.isFormatted(source)
                        .flatMap(isFormatted -> isFormatted
                                                ? handleUnchanged(source, file, counters)
                                                : handleNeedsFormatting(formatter,
                                                                        source,
                                                                        file,
                                                                        counters,
                                                                        needsFormatting));
    }

    private org.pragmatica.lang.Result<SourceFile> handleUnchanged(SourceFile source, Path file, int[] counters) {
        counters[1]++;
        if (verbose) {
            System.out.println("  unchanged: " + file);
        }

        return org.pragmatica.lang.Result.success(source);
    }

    private org.pragmatica.lang.Result<SourceFile> handleNeedsFormatting(JbctFormatter formatter,
                                                                         SourceFile source,
                                                                         Path file,
                                                                         int[] counters,
                                                                         List<Path> needsFormatting) {
        needsFormatting.add(file);
        if (checkOnly) {
            System.out.println("  needs formatting: " + file);

            return org.pragmatica.lang.Result.success(source);
        }

        return formatAndWrite(formatter, source, file, counters);
    }

    private org.pragmatica.lang.Result<SourceFile> formatAndWrite(JbctFormatter formatter,
                                                                  SourceFile source,
                                                                  Path file,
                                                                  int[] counters) {
        return formatter.format(source)
                        .flatMap(formattedSource -> writeFormatted(formattedSource, file, counters));
    }

    private org.pragmatica.lang.Result<SourceFile> writeFormatted(SourceFile formattedSource,
                                                                  Path file,
                                                                  int[] counters) {
        if (dryRun) {
            System.out.println("  would format: " + file);

            return org.pragmatica.lang.Result.success(formattedSource);
        }

        return formattedSource.write()
                              .onSuccess(_ -> {
                                             counters[0]++;
                                             if (verbose) {
                                                 System.out.println("  formatted: " + file);
                                             }
                                         });
    }

    /// The run's summary, which may never claim a clean sweep over files it could not read.
    ///
    /// `--check` printed `All files are properly formatted.` whenever nothing NEEDED formatting —
    /// evaluated before the exit-code logic and blind to the unreadable tally, so a file the
    /// formatter could not parse produced that sentence beside a non-zero exit (#977). The exit code
    /// was honest and the line was not, and this workspace's standing rule is to quote the summary
    /// line as evidence rather than the exit status, which is exactly the consumption path the line
    /// misled.
    private void printSummary(int formatted, int unchanged, int errors, List<Path> needsFormatting, int collected) {
        var coverage = AnalysisCoverage.analysisCoverage(collected, errors);

        System.out.println();
        coverage.gapReport("format").onPresent(System.out::println);
        if (checkOnly) {
            printCheckSummary(needsFormatting, coverage);
        } else if (dryRun) {
            System.out.println("Dry run: " + needsFormatting.size() + " file(s) would be formatted.");
        } else {
            System.out.println("Formatted: " + formatted + ", Unchanged: " + unchanged + ", Errors: " + errors);
        }
    }

    /// The ✓ sentence is reachable only when every collected file was actually read; otherwise the
    /// line states the coverage the verdict was reached over.
    private void printCheckSummary(List<Path> needsFormatting, AnalysisCoverage coverage) {
        if (coverage.isPartial()) {
            System.out.println("✗ Checked " + coverage.render()
                              + ": " + needsFormatting.size()
                              + " file(s) need formatting");

            return;
        }

        if (needsFormatting.isEmpty()) {
            System.out.println("All files are properly formatted.");

            return;
        }

        System.out.println(needsFormatting.size() + " file(s) need formatting.");
    }
}
