package org.pragmatica.jbct.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.jbct.config.JbctConfig;
import org.pragmatica.jbct.format.JbctFormatter;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.DiagnosticSeverity;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.LintRun;
import org.pragmatica.jbct.lint.layer.LayerCoverage;
import org.pragmatica.jbct.shared.AnalysisCoverage;
import org.pragmatica.jbct.shared.FileCollector;
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

    @picocli.CommandLine.Option(names = {"--mode"}, description = "JBCT mode for this run, overriding jbct.toml and --config: business or infrastructure")
    String mode;

    /// The one place the resolved source roots, each with its context, are handed to the run; tests replace it to
    /// observe or adjust them (#2525).
    Function<List<LintRun.SourceRoot>, LintRun> runFactory = LintRun::lintRun;

    @Override
    public Integer call() {
        // Resolve one configuration per argument path's source root
        var resolvedRoots = CliRoots.resolve(Option.option(configPath),
                                             Option.option(mode),
                                             paths,
                                             this::createContext,
                                             System.err);

        if (resolvedRoots.isEmpty()) {
            return CliConfig.CONFIG_ERROR_EXIT;
        }

        var roots = resolvedRoots.unwrap();
        var run = runFactory.apply(roots.stream().map(CliRoots.ResolvedRoot::sourceRoot).toList());
        var filesToProcess = CliRoots.allFiles(roots);

        if (filesToProcess.isEmpty()) {
            System.out.println("No Java files found.");

            return 0;
        }

        if (verbose) {
            System.out.println("Checking " + filesToProcess.size() + " Java file(s)...");
        }
        // Format check
        var needsFormatting = new ArrayList<Path>();
        var formatErrors = new AtomicInteger(0);
        // Lint check
        var allDiagnostics = new ArrayList<Diagnostic>();
        var lintErrors = new AtomicInteger(0);
        var warnings = new AtomicInteger(0);
        var parseErrors = new AtomicInteger(0);

        for (var job : CliRoots.formatJobs(roots, System.err)) {
            checkFormat(job.file(), job.formatter(), needsFormatting, formatErrors);
        }

        for (var file : filesToProcess) {
            checkLint(file, run, allDiagnostics, lintErrors, warnings, parseErrors);
        }

        var report = run.finish().onFailure(cause -> System.err.println("Error: " + cause.message())).option();

        if (report.isEmpty()) {
            return CliConfig.CONFIG_ERROR_EXIT;
        }

        report.unwrap().diagnostics().forEach(diagnostic -> record(diagnostic, allDiagnostics, lintErrors, warnings));
        roots.stream()
             .filter(root -> !root.files()
                                  .isEmpty())
             .forEach(root -> LayerCoverage.coverage(root.files(),
                                                     root.context())
                                           .map(LayerCoverage::render)
                                           .onPresent(System.err::println));
        var coverage = AnalysisCoverage.analysisCoverage(filesToProcess.size(), parseErrors.get());
        // Report format issues
        if (!needsFormatting.isEmpty()) {
            System.out.println();
            System.out.println("Files not properly formatted:");
            for (var file : needsFormatting) {
                System.out.println("  " + file);
            }
        }
        // Report lint issues
        if (!allDiagnostics.isEmpty()) {
            System.out.println();
            for (var d : allDiagnostics) {
                System.out.print(d.toHumanReadable());
            }
        }

        report.unwrap().summary().forEach(System.out::println);
        // Summary. The counts are meaningless without the denominator they were taken over: a file
        // that could not be read or parsed contributes zero of everything, so an unqualified
        // `0 lint error(s)` reads as clean when it means unexamined (#977).
        System.out.println();
        coverage.gapReport("check").onPresent(System.out::println);
        System.out.println("Check results (checked " + coverage.render()
                          + "): " + needsFormatting.size()
                          + " format issue(s), " + lintErrors.get()
                          + " lint error(s), " + warnings.get()
                          + " warning(s)");
        // Determine exit code
        if (formatErrors.get() > 0 || coverage.isPartial()) {
            return 2;
        }

        if (!needsFormatting.isEmpty() || lintErrors.get() > 0) {
            return 1;
        }

        if (failOnWarning && warnings.get() > 0) {
            return 1;
        }

        System.out.println("✓ All checks passed.");

        return 0;
    }

    LintContext createContext(JbctConfig jbctConfig) {
        return CliConfig.lintContext(jbctConfig, failOnWarning);
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

    private void checkLint(Path file,
                           LintRun run,
                           List<Diagnostic> allDiagnostics,
                           AtomicInteger errors,
                           AtomicInteger warnings,
                           AtomicInteger parseErrors) {
        SourceFile.sourceFile(file)
                  .flatMap(run::lint)
                  .onSuccess(diagnostics -> {
                                 diagnostics.forEach(diagnostic -> record(diagnostic, allDiagnostics, errors, warnings));
                                 if (verbose && diagnostics.isEmpty()) {
                                     System.out.println("  ✓ lint: " + file.getFileName());
                                 }
                             })
                  .onFailure(cause -> {
                                 parseErrors.incrementAndGet();
                                 System.err.println("  ✗ parse error: " + file + ": " + cause.message());
                             });
    }

    private static void record(Diagnostic diagnostic,
                               List<Diagnostic> allDiagnostics,
                               AtomicInteger errors,
                               AtomicInteger warnings) {
        allDiagnostics.add(diagnostic);
        switch (diagnostic.severity()) {
            case ERROR -> errors.incrementAndGet();
            case WARNING -> warnings.incrementAndGet();
            default -> {}
        }
    }
}
