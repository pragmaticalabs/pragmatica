package org.pragmatica.jbct.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.DiagnosticSeverity;
import org.pragmatica.jbct.lint.cst.LintRun;
import org.pragmatica.jbct.lint.cst.LintRun.RunReport;
import org.pragmatica.jbct.lint.cst.RootResolver.RootPlan;
import org.pragmatica.jbct.shared.AnalysisCoverage;
import org.pragmatica.lang.Option;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;


/// Lint command for JBCT static analysis.
@Command(name = "lint", description = "Analyze Java source files for JBCT compliance", mixinStandardHelpOptions = true)
public class LintCommand implements Callable<Integer> {
    @Parameters(paramLabel = "<path>", description = "Files or directories to lint", arity = "1..*")
    List<Path> paths;

    @picocli.CommandLine.Option(names = {"--format", "-f"}, description = "Output format: text, json, sarif", defaultValue = "text")
    OutputFormat outputFormat;

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

    public enum OutputFormat {
        text,
        json,
        sarif
    }

    @Override
    public Integer call() {
        var planned = CliRoots.plan(Option.option(configPath), Option.option(mode), paths, failOnWarning, System.err);

        if (planned.isEmpty()) {
            return CliConfig.CONFIG_ERROR_EXIT;
        }

        var plan = planned.unwrap();
        var run = runFactory.apply(plan);
        var filesToProcess = plan.files();
        // #1100: a run that examined nothing is a coverage gap, never a pass, so it exits 2 like any other gap.
        if (filesToProcess.isEmpty()) {
            operatorOut().println("No Java files found: nothing was examined. This is a COVERAGE GAP, not a pass.");
            printResults(List.of(), 0, List.of(), List.of());

            return 2;
        }

        if (verbose) {
            operatorOut().println("Found " + filesToProcess.size() + " Java file(s) to lint.");
        }

        var allDiagnostics = new ArrayList<Diagnostic>();
        var unanalysed = new ArrayList<Unanalysed>();

        for (var file : filesToProcess) {
            processFile(file, run, allDiagnostics, unanalysed);
        }

        var finished = run.finish().onFailure(cause -> System.err.println("Error: " + cause.message())).option();

        if (finished.isEmpty()) {
            return CliConfig.CONFIG_ERROR_EXIT;
        }

        var report = finished.unwrap();

        allDiagnostics.addAll(report.diagnostics());
        CliRoots.layerCoverage(plan, report, System.err);
        // The denominator is what the rules examined plus what they could not read: files skipped by mode are neither.
        var notParsed = CliRoots.notParsed(report);
        var coverage = AnalysisCoverage.analysisCoverage(CliRoots.examined(report) + notParsed, notParsed);

        printResults(allDiagnostics, coverage.collected(), unanalysed, report.lintLines());
        report.summary().forEach(operatorOut()::println);
        printSummary(coverage, report, infos(allDiagnostics));
        // Exit code. 2 is reserved for "the tool could not do its job" — a coverage gap — and 1 for
        // rule violations, which the tool CAN speak to. Both used to be 2, which conflated a silent
        // coverage gap with a complete run that found real errors, so a job could not tell "your code
        // is wrong" from "I never read your code" (#977). This is the split `jbct check` already uses.
        if (coverage.isPartial()) {
            return 2;
        }

        return report.fails()
               ? 1
               : 0;
    }

    /// A collected file the linter could not read or parse, with the reason.
    private record Unanalysed(Path file, String reason) {
        static Unanalysed unanalysed(Path file, String reason) {
            return new Unanalysed(file, reason);
        }
    }

    private void processFile(Path file, LintRun run, List<Diagnostic> allDiagnostics, List<Unanalysed> unanalysed) {
        run.lintFile(file)
           .onSuccess(diagnostics -> {
                          allDiagnostics.addAll(diagnostics);
                          if (verbose && diagnostics.isEmpty()) {
                              operatorOut().println("  ✓ " + file);
                          }
                      })
           .onFailure(cause -> {
                          unanalysed.add(Unanalysed.unanalysed(file,
                                                               cause.message()));
                          System.err.println("  ✗ " + file + ": " + cause.message());
                      });
    }

    private static int infos(List<Diagnostic> diagnostics) {
        return (int) diagnostics.stream()
                                .filter(diagnostic -> diagnostic.severity() == DiagnosticSeverity.INFO)
                                .count();
    }

    /// #633 — stdout is the document channel for the machine formats: `json`/`sarif` always emit
    /// exactly one document there (an empty one for a clean run — a parser fails on empty input the
    /// same way it fails on trailing text), and everything operator-facing goes through
    /// [#operatorOut]. Text keeps stdout for both, as its readers expect.
    ///
    /// #1100 — the document states its own coverage, because most SARIF uploaders never read the exit
    /// status: a run that examined nothing, or skipped files it could not parse, must not read as a
    /// clean scan to a consumer that sees only the document.
    private void printResults(List<Diagnostic> diagnostics,
                              int collected,
                              List<Unanalysed> unanalysed,
                              List<String> outcome) {
        switch (outputFormat) {
            case text -> {
                if (!diagnostics.isEmpty()) {
                    printTextResults(diagnostics);
                }
            }
            case json -> printJsonResults(diagnostics, collected, unanalysed, outcome);
            case sarif -> printSarifResults(diagnostics, collected, unanalysed, outcome);
        }
    }

    /// Where the summary, progress and "nothing to do" lines go: stdout for `text`, stderr for the
    /// machine formats — the same split `FileCollector`'s diagnostics and `score --format json`
    /// already make, so a consumer can hand stdout straight to a parser.
    private java.io.PrintStream operatorOut() {
        return outputFormat == OutputFormat.text
               ? System.out
               : System.err;
    }

    private void printTextResults(List<Diagnostic> diagnostics) {
        System.out.println();
        for (var d : diagnostics) {
            System.out.print(d.toHumanReadable());
        }
    }

    /// One object: `collected` files found, `examined` of them analysed, `skipped` the rest with the reason,
    /// and `diagnostics`. A clean scan is `examined > 0 and skipped == [] and diagnostics == []`.
    private void printJsonResults(List<Diagnostic> diagnostics,
                                  int collected,
                                  List<Unanalysed> unanalysed,
                                  List<String> outcome) {
        var sb = new StringBuilder();

        sb.append("{\n");
        sb.append("  \"collected\": %d,\n".formatted(collected));
        sb.append("  \"examined\": %d,\n".formatted(collected - unanalysed.size()));
        sb.append("  \"skipped\": [");
        sb.append(unanalysed.isEmpty()
                  ? "],\n"
                  : "\n" + unanalysed.stream().map(this::skippedJson).collect(Collectors.joining(",\n")) + "\n  ],\n");
        sb.append("  \"outcome\": [%s],\n".formatted(outcome.stream()
                                                            .map(line -> "\"" + escapeJson(line) + "\"")
                                                            .collect(Collectors.joining(", "))));
        sb.append("  \"diagnostics\": [\n");
        for (int i = 0; i < diagnostics.size(); i++) {
            var d = diagnostics.get(i);

            sb.append("    {\n");
            sb.append("      \"ruleId\": \"%s\",\n".formatted(d.ruleId()));
            sb.append("      \"severity\": \"%s\",\n".formatted(d.severity().name().toLowerCase(Locale.ROOT)));
            sb.append("      \"file\": \"%s\",\n".formatted(escapeJson(d.file())));
            sb.append("      \"line\": %d,\n".formatted(d.line()));
            sb.append("      \"column\": %d,\n".formatted(d.column()));
            sb.append("      \"message\": \"%s\"\n".formatted(escapeJson(d.message())));
            sb.append("    }");
            if (i < diagnostics.size() - 1) {
                sb.append(",");
            }

            sb.append("\n");
        }

        sb.append("  ]\n");
        sb.append("}\n");
        System.out.print(sb);
    }

    private String skippedJson(Unanalysed skipped) {
        return "    { \"file\": \"%s\", \"reason\": \"%s\" }".formatted(escapeJson(skipped.file().toString()),
                                                                        escapeJson(skipped.reason()));
    }

    private void printSarifResults(List<Diagnostic> diagnostics,
                                   int collected,
                                   List<Unanalysed> unanalysed,
                                   List<String> outcome) {
        // Simplified SARIF output
        var sb = new StringBuilder();

        sb.append("{\n");
        sb.append("  \"$schema\": \"https://json.schemastore.org/sarif-2.1.0.json\",\n");
        sb.append("  \"version\": \"2.1.0\",\n");
        sb.append("  \"runs\": [{\n");
        sb.append("    \"tool\": {\n");
        sb.append("      \"driver\": {\n");
        sb.append("        \"name\": \"jbct\",\n");
        sb.append("        \"informationUri\": \"https://github.com/siy/coding-technology\"\n");
        sb.append("      }\n");
        sb.append("    },\n");
        sb.append("    \"results\": [\n");
        for (int i = 0; i < diagnostics.size(); i++) {
            var d = diagnostics.get(i);

            sb.append("      {\n");
            sb.append("        \"ruleId\": \"%s\",\n".formatted(d.ruleId()));
            sb.append("        \"level\": \"%s\",\n".formatted(sarifLevel(d.severity())));
            sb.append("        \"message\": { \"text\": \"%s\" },\n".formatted(escapeJson(d.message())));
            sb.append("        \"locations\": [{\n");
            sb.append("          \"physicalLocation\": {\n");
            sb.append("            \"artifactLocation\": { \"uri\": \"%s\" },\n".formatted(escapeJson(d.file())));
            sb.append("            \"region\": { \"startLine\": %d, \"startColumn\": %d }\n".formatted(d.line(),
                                                                                                       d.column()));
            sb.append("          }\n");
            sb.append("        }]\n");
            sb.append("      }");
            if (i < diagnostics.size() - 1) {
                sb.append(",");
            }

            sb.append("\n");
        }

        sb.append("    ],\n");
        appendSarifInvocation(sb, collected, unanalysed, outcome);
        sb.append("  }]\n");
        sb.append("}\n");
        System.out.print(sb);
    }

    /// The run's coverage in SARIF's own terms. A file the linter could not analyse, and a run that collected
    /// no files at all, are both coverage gaps (exit 2): each is an `error` notification and makes
    /// `executionSuccessful` false. A clean scan is `executionSuccessful`, no notifications and no results.
    private void appendSarifInvocation(StringBuilder sb,
                                       int collected,
                                       List<Unanalysed> unanalysed,
                                       List<String> outcome) {
        sb.append("    \"invocations\": [{\n");
        sb.append("      \"executionSuccessful\": %s,\n".formatted(collected > 0 && unanalysed.isEmpty()));
        sb.append("      \"toolExecutionNotifications\": [");
        var notifications = new ArrayList<String>();

        if (collected == 0) {
            notifications.add("        { \"level\": \"error\", \"message\": { \"text\": \"%s\" } }".formatted(escapeJson(noneExamined(outcome))));
        }

        for (var skipped : unanalysed) {
            notifications.add(("        { \"level\": \"error\", \"message\": { \"text\": \"Not analysed: %s\" }, "
                              + "\"locations\": [{ \"physicalLocation\": { \"artifactLocation\": { \"uri\": \"%s\" } } }] }").formatted(escapeJson(skipped.reason()),
                                                                                                                                        escapeJson(skipped.file()
                                                                                                                                                          .toString())));
        }

        sb.append(notifications.isEmpty()
                  ? "]\n"
                  : "\n" + String.join(",\n", notifications) + "\n      ]\n");
        sb.append("    }]\n");
    }

    /// Why nothing was examined: the run's own sentence when it had files to examine (skipped by mode, not parsed),
    /// the "no files" sentence when the run had none.
    private static String noneExamined(List<String> outcome) {
        return outcome.isEmpty()
               ? "No Java files found: nothing was examined."
               : String.join(" ", outcome);
    }

    private String sarifLevel(DiagnosticSeverity severity) {
        return switch (severity) {
            case ERROR -> "error";
            case WARNING -> "warning";
            case INFO -> "note";
        };
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /// The run's outcome: the coverage gap when files were not parsed, the failure, and the run's own sentences about what
    /// was linted and what was not. No pass is worded here: a clean run over nothing says "Nothing was linted" (#977, #1100).
    private void printSummary(AnalysisCoverage coverage, RunReport report, int infos) {
        var out = operatorOut();
        var errors = CliRoots.errors(report);
        var warnings = CliRoots.warnings(report);

        out.println();
        coverage.gapReport("lint").onPresent(out::println);
        report.failure().onPresent(out::println);
        report.lintLines().forEach(out::println);
        out.println(errors + " error(s), " + warnings + " warning(s), " + infos + " info(s)");
    }
}
