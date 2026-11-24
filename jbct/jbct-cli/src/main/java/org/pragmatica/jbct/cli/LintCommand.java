package org.pragmatica.jbct.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.DiagnosticSeverity;
import org.pragmatica.jbct.lint.cst.RunOutcome.Invocation;
import org.pragmatica.jbct.lint.cst.RunOutcome.Notification;
import org.pragmatica.jbct.lint.cst.RunOutcome.RunResult;
import org.pragmatica.jbct.lint.cst.RunOutcome.Surface;
import org.pragmatica.jbct.lint.cst.RunOutcome.Unanalysed;
import org.pragmatica.jbct.lint.cst.RootResolver.RootPlan;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;


/// Lint command for JBCT static analysis: resolves the plan, hands it to the run and prints what the run returned.
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

    /// The exit status when the plan holds no file: a run that examined nothing is a coverage gap, never a pass (#1100).
    static final int NOTHING_COLLECTED_EXIT = 2;

    /// The one place the resolved plan and the invocation reach the run; a test replaces it to capture the result the command received (#2525).
    BiFunction<RootPlan, Invocation, Result<RunResult>> runSeam = CliRoots.PERFORM;

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

        var performed = runSeam.apply(planned.unwrap(), Invocation.lint(Surface.CLI));

        return outputFormat == OutputFormat.text
               ? CliRoots.finish(performed, verbose, NOTHING_COLLECTED_EXIT)
               : performed.fold(CliRoots::refused, this::printedMachine);
    }

    /// #633 - stdout is the document channel for the machine formats: `json`/`sarif` always emit exactly one document
    /// there, and the run's own lines go to stderr. The document copies the run's values and computes nothing (#1100).
    private int printedMachine(RunResult result) {
        CliRoots.printLinesToStderr(result, verbose);
        printDocument(result);

        return CliRoots.exitCode(result.verdict(), NOTHING_COLLECTED_EXIT);
    }

    @Contract
    private void printDocument(RunResult result) {
        switch (outputFormat) {
            case json -> printJsonResults(result);
            case sarif -> printSarifResults(result);
            case text -> {}
        }
    }

    /// One object: `collected` files found, `examined` of them analysed, `skipped` the rest with the reason,
    /// and `diagnostics`. A clean scan is `examined > 0 and skipped == [] and diagnostics == []`.
    private void printJsonResults(RunResult result) {
        var diagnostics = result.findings();
        var unanalysed = result.unanalysed();
        var outcome = result.outcome();
        var sb = new StringBuilder();

        sb.append("{\n");
        sb.append(String.format(Locale.ROOT, "  \"collected\": %d,\n", result.collected()));
        sb.append(String.format(Locale.ROOT, "  \"examined\": %d,\n", result.examined()));
        sb.append(String.format(Locale.ROOT, "  \"skippedByMode\": %d,\n", result.skippedByMode()));
        sb.append("  \"skipped\": [");
        sb.append(unanalysed.isEmpty()
                  ? "],\n"
                  : "\n" + unanalysed.stream().map(this::skippedJson).collect(Collectors.joining(",\n")) + "\n  ],\n");
        sb.append(String.format(Locale.ROOT,
                                "  \"outcome\": [%s],\n",
                                outcome.stream()
                                       .map(line -> "\"" + escapeJson(line) + "\"")
                                       .collect(Collectors.joining(", "))));
        sb.append("  \"diagnostics\": [\n");
        for (int i = 0; i < diagnostics.size(); i++) {
            var d = diagnostics.get(i);

            sb.append("    {\n");
            sb.append(String.format(Locale.ROOT, "      \"ruleId\": \"%s\",\n", d.ruleId()));
            sb.append(String.format(Locale.ROOT,
                                    "      \"severity\": \"%s\",\n",
                                    d.severity().name().toLowerCase(Locale.ROOT)));
            sb.append(String.format(Locale.ROOT, "      \"file\": \"%s\",\n", escapeJson(d.file())));
            sb.append(String.format(Locale.ROOT, "      \"line\": %d,\n", d.line()));
            sb.append(String.format(Locale.ROOT, "      \"column\": %d,\n", d.column()));
            sb.append(String.format(Locale.ROOT, "      \"message\": \"%s\"\n", escapeJson(d.message())));
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
        return String.format(Locale.ROOT,
                             "    { \"file\": \"%s\", \"reason\": \"%s\" }",
                             escapeJson(skipped.file().toString()),
                             escapeJson(skipped.reason()));
    }

    private void printSarifResults(RunResult result) {
        var diagnostics = result.findings();
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
            sb.append(String.format(Locale.ROOT, "        \"ruleId\": \"%s\",\n", d.ruleId()));
            sb.append(String.format(Locale.ROOT, "        \"level\": \"%s\",\n", sarifLevel(d.severity())));
            sb.append(String.format(Locale.ROOT, "        \"message\": { \"text\": \"%s\" },\n", escapeJson(d.message())));
            sb.append("        \"locations\": [{\n");
            sb.append("          \"physicalLocation\": {\n");
            sb.append(String.format(Locale.ROOT,
                                    "            \"artifactLocation\": { \"uri\": \"%s\" },\n",
                                    escapeJson(d.file())));
            sb.append(String.format(Locale.ROOT,
                                    "            \"region\": { \"startLine\": %d, \"startColumn\": %d }\n",
                                    d.line(),
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
        appendSarifInvocation(sb, result);
        sb.append("  }]\n");
        sb.append("}\n");
        System.out.print(sb);
    }

    /// The run's coverage in SARIF's own terms, copied from the run's result: `executionSuccessful`, the notification
    /// level and, for a run that analysed no file, the run's own sentences; each file not read or not parsed is one notification.
    private void appendSarifInvocation(StringBuilder sb, RunResult result) {
        sb.append("    \"invocations\": [{\n");
        sb.append(String.format(Locale.ROOT, "      \"executionSuccessful\": %s,\n", result.executionSuccessful()));
        sb.append("      \"toolExecutionNotifications\": [");
        var notifications = result.notifications().stream().map(this::notificationJson).toList();

        sb.append(notifications.isEmpty()
                  ? "]\n"
                  : "\n" + String.join(",\n", notifications) + "\n      ]\n");
        sb.append("    }]\n");
    }

    private String notificationJson(Notification notification) {
        return notification.file()
                           .map(file -> String.format(Locale.ROOT,
                                                      "        { \"level\": \"%s\", \"message\": { \"text\": \"%s\" }, \"locations\": [{ \"physicalLocation\": { \"artifactLocation\": { \"uri\": \"%s\" } } }] }",
                                                      notification.level(),
                                                      escapeJson(notification.text()),
                                                      escapeJson(file.toString())))
                           .or(() -> String.format(Locale.ROOT,
                                                   "        { \"level\": \"%s\", \"message\": { \"text\": \"%s\" } }",
                                                   notification.level(),
                                                   escapeJson(notification.text())));
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
}
