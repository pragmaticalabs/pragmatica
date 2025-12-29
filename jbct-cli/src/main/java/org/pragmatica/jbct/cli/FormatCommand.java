package org.pragmatica.jbct.cli;

import org.pragmatica.jbct.config.ConfigLoader;
import org.pragmatica.jbct.config.JbctConfig;
import org.pragmatica.jbct.format.JbctFormatter;
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
 * Format command for JBCT code formatting.
 */
@Command(
 name = "format",
 description = "Format Java source files according to JBCT style",
 mixinStandardHelpOptions = true)
public class FormatCommand implements Callable<Integer> {
    @Parameters(
    paramLabel = "<path>",
    description = "Files or directories to format",
    arity = "1..*")
    List<Path> paths;

    @picocli.CommandLine.Option(
    names = {"--check", "-c"},
    description = "Check if files are formatted without modifying them")
    boolean checkOnly;

    @picocli.CommandLine.Option(
    names = {"--dry-run", "-n"},
    description = "Show what would be changed without modifying files")
    boolean dryRun;

    @picocli.CommandLine.Option(
    names = {"--verbose", "-v"},
    description = "Show verbose output")
    boolean verbose;

    @picocli.CommandLine.Option(
    names = {"--config"},
    description = "Path to configuration file")
    Path configPath;

    private JbctFormatter formatter;

    @Override
    public Integer call() {
        // Load configuration
        var config = ConfigLoader.load(
        Option.option(configPath), Option.none());
        formatter = JbctFormatter.jbctFormatter(config.formatter());
        var filesToProcess = collectJavaFiles();
        if (filesToProcess.isEmpty()) {
            System.out.println("No Java files found.");
            return 0;
        }
        if (verbose) {
            System.out.println("Found " + filesToProcess.size() + " Java file(s) to process.");
        }
        var formatted = new AtomicInteger(0);
        var unchanged = new AtomicInteger(0);
        var errors = new AtomicInteger(0);
        var needsFormatting = new ArrayList<Path>();
        for (var file : filesToProcess) {
            processFile(file, formatted, unchanged, errors, needsFormatting);
        }
        // Print summary
        printSummary(formatted.get(), unchanged.get(), errors.get(), needsFormatting);
        // Return appropriate exit code
        if (errors.get() > 0) {
            return 2;
        }
        if (checkOnly && !needsFormatting.isEmpty()) {
            return 1;
        }
        return 0;
    }

    private List<Path> collectJavaFiles() {
        return FileCollector.collectJavaFiles(paths, System.err::println);
    }

    private void processFile(Path file,
                             AtomicInteger formatted,
                             AtomicInteger unchanged,
                             AtomicInteger errors,
                             List<Path> needsFormatting) {
        SourceFile.sourceFile(file)
                  .flatMap(source -> {
                               // Check if already formatted
        return formatter.isFormatted(source)
                        .flatMap(isFormatted -> {
                                     if (isFormatted) {
                                     unchanged.incrementAndGet();
                                     if (verbose) {
                                     System.out.println("  unchanged: " + file);
                                 }
                                     return org.pragmatica.lang.Result.success(source);
                                 }
                                     // Needs formatting
        needsFormatting.add(file);
                                     if (checkOnly) {
                                     System.out.println("  needs formatting: " + file);
                                     return org.pragmatica.lang.Result.success(source);
                                 }
                                     // Format the file
        return formatter.format(source)
                        .flatMap(formattedSource -> {
                                     if (dryRun) {
                                     System.out.println("  would format: " + file);
                                     return org.pragmatica.lang.Result.success(formattedSource);
                                 }
                                     // Write back
        return formattedSource.write()
                              .map(written -> {
                                       formatted.incrementAndGet();
                                       if (verbose) {
                                       System.out.println("  formatted: " + file);
                                   }
                                       return written;
                                   });
                                 });
                                 });
                           })
                  .onFailure(cause -> {
                                 errors.incrementAndGet();
                                 System.err.println("  error: " + file + " - " + cause.message());
                             });
    }

    private void printSummary(int formatted, int unchanged, int errors, List<Path> needsFormatting) {
        System.out.println();
        if (checkOnly) {
            if (needsFormatting.isEmpty()) {
                System.out.println("All files are properly formatted.");
            }else {
                System.out.println(needsFormatting.size() + " file(s) need formatting.");
            }
        }else if (dryRun) {
            System.out.println("Dry run: " + needsFormatting.size() + " file(s) would be formatted.");
        }else {
            System.out.println("Formatted: " + formatted + ", Unchanged: " + unchanged + ", Errors: " + errors);
        }
    }
}
