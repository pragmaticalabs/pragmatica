package org.pragmatica.jbct.cli;

import org.pragmatica.jbct.config.ConfigLoader;
import org.pragmatica.jbct.config.JbctConfig;
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

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * Lint command for JBCT static analysis.
 */
@Command(
 name = "lint",
 description = "Analyze Java source files for JBCT compliance",
 mixinStandardHelpOptions = true)
public class LintCommand implements Callable<Integer> {
    @Parameters(
    paramLabel = "<path>",
    description = "Files or directories to lint",
    arity = "1..*")
    List<Path> paths;

    @picocli.CommandLine.Option(
    names = {"--format", "-f"},
    description = "Output format: text, json, sarif",
    defaultValue = "text")
    OutputFormat outputFormat;

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

    public enum OutputFormat {
        text,
        json,
        sarif
    }

    @Override
    public Integer call() {
        // Load configuration
        var config = ConfigLoader.load(Option.option(configPath), Option.none());
        var context = createContext(config);
        var linter = JbctLinter.jbctLinter(context);
        var filesToProcess = collectJavaFiles();
        if (filesToProcess.isEmpty()) {
            System.out.println("No Java files found.");
            return 0;
        }
        if (verbose) {
            System.out.println("Found " + filesToProcess.size() + " Java file(s) to lint.");
        }
        var allDiagnostics = new ArrayList<Diagnostic>();
        var counters = new int[4]; // 0=errors, 1=warnings, 2=infos, 3=parseErrors
        for (var file : filesToProcess) {
            processFile(file, linter, allDiagnostics, counters);
        }
        // Output results
        printResults(allDiagnostics);
        // Print summary
        printSummary(filesToProcess.size(), counters[0], counters[1], counters[2], counters[3]);
        // Return appropriate exit code
        if (counters[3] > 0 || counters[0] > 0) {
            return 2;
        }
        if (failOnWarning && counters[1] > 0) {
            return 1;
        }
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

    private void processFile(Path file, JbctLinter linter, List<Diagnostic> allDiagnostics, int[] counters) {
        SourceFile.sourceFile(file)
                  .flatMap(linter::lint)
                  .onSuccess(diagnostics -> {
                                 allDiagnostics.addAll(diagnostics);
                                 for (var d : diagnostics) {
                                     switch (d.severity()) {
            case ERROR -> counters[0]++;
            case WARNING -> counters[1]++;
            case INFO -> counters[2]++;
        }
                                 }
                                 if (verbose && diagnostics.isEmpty()) {
                                     System.out.println("  ✓ " + file);
                                 }
                             })
                  .onFailure(cause -> {
                                 counters[3]++;
                                 System.err.println("  ✗ " + file + ": " + cause.message());
                             });
    }

    private void printResults(List<Diagnostic> diagnostics) {
        if (diagnostics.isEmpty()) {
            return;
        }
        switch (outputFormat) {
            case text -> printTextResults(diagnostics);
            case json -> printJsonResults(diagnostics);
            case sarif -> printSarifResults(diagnostics);
        }
    }

    private void printTextResults(List<Diagnostic> diagnostics) {
        System.out.println();
        for (var d : diagnostics) {
            System.out.print(d.toHumanReadable());
        }
    }

    private void printJsonResults(List<Diagnostic> diagnostics) {
        var sb = new StringBuilder();
        sb.append("[\n");
        for (int i = 0; i < diagnostics.size(); i++) {
            var d = diagnostics.get(i);
            sb.append("  {\n");
            sb.append("    \"ruleId\": \"%s\",\n".formatted(d.ruleId()));
            sb.append("    \"severity\": \"%s\",\n".formatted(d.severity()
                                                               .name()
                                                               .toLowerCase()));
            sb.append("    \"file\": \"%s\",\n".formatted(escapeJson(d.file())));
            sb.append("    \"line\": %d,\n".formatted(d.line()));
            sb.append("    \"column\": %d,\n".formatted(d.column()));
            sb.append("    \"message\": \"%s\"\n".formatted(escapeJson(d.message())));
            sb.append("  }");
            if (i < diagnostics.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("]\n");
        System.out.print(sb);
    }

    private void printSarifResults(List<Diagnostic> diagnostics) {
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
        sb.append("    ]\n");
        sb.append("  }]\n");
        sb.append("}\n");
        System.out.print(sb);
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

    private void printSummary(int filesChecked, int errors, int warnings, int infos, int parseErrors) {
        System.out.println();
        if (parseErrors > 0) {
            System.out.println("Parse errors: " + parseErrors);
        }
        if (errors == 0 && warnings == 0 && infos == 0) {
            System.out.println("✓ All " + filesChecked + " file(s) passed JBCT compliance check.");
        } else {
            System.out.println("Checked " + filesChecked + " file(s): " + errors + " error(s), " + warnings
                               + " warning(s), " + infos + " info(s)");
        }
    }
}
