package org.pragmatica.jbct.cli;

import org.pragmatica.jbct.config.ConfigLoader;
import org.pragmatica.jbct.config.JbctConfig;
import org.pragmatica.jbct.format.JbctFormatter;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.DiagnosticSeverity;
import org.pragmatica.jbct.lint.JbctLinter;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.shared.FileCollector;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Option;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * Check command - combines format check and lint (for CI).
 */
@Command(
 name = "check",
 description = "Run both format check and lint (for CI)",
 mixinStandardHelpOptions = true)
public class CheckCommand implements Callable<Integer> {
    @Parameters(
    paramLabel = "<path>",
    description = "Files or directories to check",
    arity = "1..*")
    List<Path> paths;

    @picocli.CommandLine.Option(
    names = {"--fail-on-warning", "-w"},
    description = "Treat warnings as errors")
    boolean failOnWarning;

    @picocli.CommandLine.Option(
    names = {"--verbose", "-v"},
    description = "Show verbose output")
    boolean verbose;

    @picocli.CommandLine.Option(
    names = {"--config"},
    description = "Path to configuration file")
    Path configPath;

    @Override
    public Integer call() {
        // Load configuration
        var config = ConfigLoader.load(
        Option.option(configPath), Option.none());
        var formatter = JbctFormatter.jbctFormatter(config.formatter());
        var context = createContext(config);
        var linter = JbctLinter.jbctLinter(context);
        var filesToProcess = collectJavaFiles();
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
        for (var file : filesToProcess) {
            checkFormat(file, formatter, needsFormatting, formatErrors);
            checkLint(file, linter, allDiagnostics, lintErrors, warnings, parseErrors);
        }
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
        // Summary
        System.out.println();
        System.out.println("Check results: " + needsFormatting.size() + " format issue(s), " + lintErrors.get()
                           + " lint error(s), " + warnings.get() + " warning(s)");
        // Determine exit code
        if (formatErrors.get() > 0 || parseErrors.get() > 0) {
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

    private LintContext createContext(JbctConfig jbctConfig) {
        var lintConfig = jbctConfig.lint();
        if (failOnWarning) {
            lintConfig = lintConfig.withFailOnWarning(true);
        }
        return LintContext.defaultContext()
                          .withConfig(lintConfig)
                          .withBusinessPackages(jbctConfig.businessPackages());
    }

    private List<Path> collectJavaFiles() {
        return FileCollector.collectJavaFiles(paths, System.err::println);
    }

    private void checkFormat(Path file, JbctFormatter formatter, List<Path> needsFormatting, AtomicInteger errors) {
        SourceFile.sourceFile(file)
                  .flatMap(formatter::isFormatted)
                  .onSuccess(isFormatted -> {
                                 if (!isFormatted) {
                                 needsFormatting.add(file);
                             }else if (verbose) {
                                 System.out.println("  ✓ format: " + file.getFileName());
                             }
                             })
                  .onFailure(cause -> {
                                 errors.incrementAndGet();
                                 System.err.println("  ✗ format error: " + file + ": " + cause.message());
                             });
    }

    private void checkLint(Path file,
                           JbctLinter linter,
                           List<Diagnostic> allDiagnostics,
                           AtomicInteger errors,
                           AtomicInteger warnings,
                           AtomicInteger parseErrors) {
        SourceFile.sourceFile(file)
                  .flatMap(linter::lint)
                  .onSuccess(diagnostics -> {
                                 allDiagnostics.addAll(diagnostics);
                                 for (var d : diagnostics) {
                                 switch (d.severity()) {
            case ERROR -> errors.incrementAndGet();
            case WARNING -> warnings.incrementAndGet();
            default -> {}
        }
                             }
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
