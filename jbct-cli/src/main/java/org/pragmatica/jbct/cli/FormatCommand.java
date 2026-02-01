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
        var config = ConfigLoader.load(Option.option(configPath), Option.none());
        formatter = JbctFormatter.jbctFormatter(config.formatter());
        var filesToProcess = collectJavaFiles();
        if (filesToProcess.isEmpty()) {
            System.out.println("No Java files found.");
            return 0;
        }
        if (verbose) {
            System.out.println("Found " + filesToProcess.size() + " Java file(s) to process.");
        }
        var counters = new int[3]; // 0=formatted, 1=unchanged, 2=errors
        var needsFormatting = new ArrayList<Path>();
        for (var file : filesToProcess) {
            processFile(file, counters, needsFormatting);
        }
        // Print summary
        printSummary(counters[0], counters[1], counters[2], needsFormatting);
        // Return appropriate exit code
        if (counters[2] > 0) {
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

    private void processFile(Path file, int[] counters, List<Path> needsFormatting) {
        SourceFile.sourceFile(file)
                  .flatMap(source -> checkAndFormat(source, file, counters, needsFormatting))
                  .onFailure(cause -> {
                                 counters[2]++;
                                 System.err.println("  error: " + file + " - " + cause.message());
                             });
    }

    private org.pragmatica.lang.Result<SourceFile> checkAndFormat(SourceFile source,
                                                                  Path file,
                                                                  int[] counters,
                                                                  List<Path> needsFormatting) {
        return formatter.isFormatted(source)
                        .flatMap(isFormatted -> isFormatted
                                                ? handleUnchanged(source, file, counters)
                                                : handleNeedsFormatting(source, file, counters, needsFormatting));
    }

    private org.pragmatica.lang.Result<SourceFile> handleUnchanged(SourceFile source, Path file, int[] counters) {
        counters[1]++;
        if (verbose) {
            System.out.println("  unchanged: " + file);
        }
        return org.pragmatica.lang.Result.success(source);
    }

    private org.pragmatica.lang.Result<SourceFile> handleNeedsFormatting(SourceFile source,
                                                                         Path file,
                                                                         int[] counters,
                                                                         List<Path> needsFormatting) {
        needsFormatting.add(file);
        if (checkOnly) {
            System.out.println("  needs formatting: " + file);
            return org.pragmatica.lang.Result.success(source);
        }
        return formatAndWrite(source, file, counters);
    }

    private org.pragmatica.lang.Result<SourceFile> formatAndWrite(SourceFile source, Path file, int[] counters) {
        return formatter.format(source)
                        .flatMap(formattedSource -> writeFormatted(formattedSource, file, counters));
    }

    private org.pragmatica.lang.Result<SourceFile> writeFormatted(SourceFile formattedSource, Path file, int[] counters) {
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

    private void printSummary(int formatted, int unchanged, int errors, List<Path> needsFormatting) {
        System.out.println();
        if (checkOnly) {
            if (needsFormatting.isEmpty()) {
                System.out.println("All files are properly formatted.");
            } else {
                System.out.println(needsFormatting.size() + " file(s) need formatting.");
            }
        } else if (dryRun) {
            System.out.println("Dry run: " + needsFormatting.size() + " file(s) would be formatted.");
        } else {
            System.out.println("Formatted: " + formatted + ", Unchanged: " + unchanged + ", Errors: " + errors);
        }
    }
}
