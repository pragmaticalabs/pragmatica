package org.pragmatica.aether.cli;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;

import tools.jackson.databind.JsonNode;

/// Formats CLI output in various formats (JSON, table, CSV, single value).
public sealed interface OutputFormatter {
    JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    /// Print query results using automatic format detection.
    static int printQuery(String json, OutputOptions options) {
        return printQuery(json, options, null);
    }

    /// Print query results with table specification for structured rendering.
    static int printQuery(String json, OutputOptions options, TableSpec tableSpec) {
        if (options.isQuiet()) {
            return ExitCode.SUCCESS;
        }

        return switch (options.format()) {
            case JSON -> printJson(json);
            case VALUE -> printValue(json, options.field());
            case TABLE -> printTable(json, tableSpec);
            case CSV -> printCsv(json, tableSpec);
        };
    }

    /// Print action result with a success message.
    static int printAction(String json, OutputOptions options, String successMessage) {
        if (options.isQuiet()) {
            return ExitCode.SUCCESS;
        }

        return switch (options.format()) {
            case JSON -> printJson(json);
            case VALUE -> printValue(json, options.field());
            case TABLE -> printSuccessMessage(successMessage);
            case CSV -> printJson(json);
        };
    }

    /// Format and print pretty-printed JSON.
    private static int printJson(String json) {
        return MAPPER.prettyPrint(json)
                     .fold(OutputFormatter::handleParseError, OutputFormatter::printToStdout);
    }

    /// Extract and print a single field value.
    private static int printValue(String json, String fieldPath) {
        if (fieldPath == null) {
            return printJson(json);
        }

        return MAPPER.extractField(json, fieldPath)
                     .fold(OutputFormatter::handleFieldError, OutputFormatter::printToStdout);
    }

    /// Print data as a formatted table.
    private static int printTable(String json, TableSpec tableSpec) {
        if (tableSpec == null) {
            return printJson(json);
        }

        return MAPPER.readTree(json)
                     .fold(OutputFormatter::handleParseError, node -> renderTable(node, tableSpec));
    }

    /// Print data as CSV.
    private static int printCsv(String json, TableSpec tableSpec) {
        if (tableSpec == null) {
            return printJson(json);
        }

        return MAPPER.readTree(json)
                     .fold(OutputFormatter::handleParseError, node -> renderCsv(node, tableSpec));
    }

    /// Render a table from parsed JSON.
    private static int renderTable(JsonNode root, TableSpec tableSpec) {
        var dataNodes = resolveDataNodes(root, tableSpec.arrayPath());
        var out = System.out;

        printTableHeader(out, tableSpec.columns());
        printTableSeparator(out, tableSpec.columns());
        dataNodes.forEach(node -> printTableRow(out, node, tableSpec.columns()));

        return ExitCode.SUCCESS;
    }

    /// Print table header row.
    private static void printTableHeader(PrintStream out, List<Column> columns) {
        var format = buildFormatString(columns);
        var headers = columns.stream().map(Column::header).toArray();
        out.printf(format, headers);
    }

    /// Print table separator line.
    private static void printTableSeparator(PrintStream out, List<Column> columns) {
        var joiner = new StringJoiner("  ");
        columns.forEach(col -> joiner.add("\u2500".repeat(col.width())));
        out.println(joiner);
    }

    /// Print a single table row.
    private static void printTableRow(PrintStream out, JsonNode node, List<Column> columns) {
        var format = buildFormatString(columns);
        var values = columns.stream()
                            .map(col -> extractColumnValue(node, col))
                            .toArray();
        out.printf(format, values);
    }

    /// Build printf format string from column definitions.
    private static String buildFormatString(List<Column> columns) {
        var joiner = new StringJoiner("  ");
        columns.forEach(col -> joiner.add("%-" + col.width() + "s"));
        return joiner + "%n";
    }

    /// Extract a column value from a JSON node, truncating to column width.
    private static String extractColumnValue(JsonNode node, Column column) {
        var value = navigateToField(node, column.jsonPath());
        return value.length() > column.width() ? value.substring(0, column.width()) : value;
    }

    /// Render CSV from parsed JSON.
    private static int renderCsv(JsonNode root, TableSpec tableSpec) {
        var dataNodes = resolveDataNodes(root, tableSpec.arrayPath());
        var out = System.out;

        printCsvHeader(out, tableSpec.columns());
        dataNodes.forEach(node -> printCsvRow(out, node, tableSpec.columns()));

        return ExitCode.SUCCESS;
    }

    /// Print CSV header row.
    private static void printCsvHeader(PrintStream out, List<Column> columns) {
        var joiner = new StringJoiner(",");
        columns.forEach(col -> joiner.add(col.header()));
        out.println(joiner);
    }

    /// Print a single CSV data row.
    private static void printCsvRow(PrintStream out, JsonNode node, List<Column> columns) {
        var joiner = new StringJoiner(",");
        columns.forEach(col -> joiner.add(escapeCsvValue(navigateToField(node, col.jsonPath()))));
        out.println(joiner);
    }

    /// Escape a CSV value, quoting if it contains commas or quotes.
    private static String escapeCsvValue(String value) {
        return value.contains(",") || value.contains("\"")
            ? "\"" + value.replace("\"", "\"\"") + "\""
            : value;
    }

    /// Navigate a JSON node using dot-notation path.
    private static String navigateToField(JsonNode node, String dotPath) {
        var current = node;

        for (var segment : dotPath.split("\\.")) {
            current = current.path(segment);

            if (current.isMissingNode()) {
                return "";
            }
        }

        return current.isValueNode() ? current.asText() : current.toString();
    }

    /// Resolve data nodes from the root, following the array path if specified.
    private static List<JsonNode> resolveDataNodes(JsonNode root, String arrayPath) {
        var target = arrayPath != null ? navigateToNode(root, arrayPath) : root;
        var nodes = new ArrayList<JsonNode>();

        if (target.isArray()) {
            target.forEach(nodes::add);
        } else {
            nodes.add(target);
        }

        return nodes;
    }

    /// Navigate to a JsonNode at the given dot-notation path.
    private static JsonNode navigateToNode(JsonNode root, String dotPath) {
        var current = root;

        for (var segment : dotPath.split("\\.")) {
            current = current.path(segment);
        }

        return current;
    }

    /// Print text to stdout and return SUCCESS.
    private static int printToStdout(String text) {
        System.out.println(text);
        return ExitCode.SUCCESS;
    }

    /// Print a success message to stdout.
    private static int printSuccessMessage(String message) {
        System.out.println(message);
        return ExitCode.SUCCESS;
    }

    /// Handle JSON parse errors by printing to stderr.
    private static int handleParseError(Cause cause) {
        System.err.println("Error: failed to parse JSON: " + cause.message());
        return ExitCode.ERROR;
    }

    /// Handle field extraction errors by printing to stderr.
    private static int handleFieldError(Cause cause) {
        System.err.println("Error: field not found: " + cause.message());
        return ExitCode.NOT_FOUND;
    }

    /// Check if a JSON response contains an error field using Jackson.
    static boolean isErrorResponse(String json) {
        return MAPPER.readTree(json)
                     .map(node -> node.has("error"))
                     .or(false);
    }

    /// Check if a JSON error response indicates a not-found condition.
    static boolean isNotFoundResponse(String json) {
        return MAPPER.readTree(json)
                     .map(OutputFormatter::containsNotFound)
                     .or(false);
    }

    /// Extract the error message from a JSON error response.
    static String extractErrorMessage(String json) {
        return MAPPER.readTree(json)
                     .map(OutputFormatter::extractErrorField)
                     .or(json);
    }

    private static String extractErrorField(JsonNode node) {
        var errorNode = node.path("error");
        return errorNode.isMissingNode() ? node.toString() : errorNode.asText();
    }

    /// Print an error to stderr, respecting output format.
    static int printError(String message, OutputOptions options) {
        if (options.format() == OutputFormat.JSON) {
            System.err.println("{\"error\":\"" + message.replace("\"", "\\\"") + "\"}");
        } else {
            System.err.println("Error: " + message);
        }
        return ExitCode.ERROR;
    }

    /// Print an error to stderr, returning NOT_FOUND exit code.
    static int printNotFound(String message, OutputOptions options) {
        if (options.format() == OutputFormat.JSON) {
            System.err.println("{\"error\":\"" + message.replace("\"", "\\\"") + "\"}");
        } else {
            System.err.println("Error: " + message);
        }
        return ExitCode.NOT_FOUND;
    }

    /// Check an API response for errors and print appropriate output.
    /// Returns the exit code if an error was detected, or -1 if no error.
    static int checkResponseError(String response, OutputOptions options, String actionLabel) {
        if (!isErrorResponse(response)) {
            return -1;
        }
        var message = actionLabel + ": " + extractErrorMessage(response);
        return isNotFoundResponse(response)
               ? printNotFound(message, options)
               : printError(message, options);
    }

    private static boolean containsNotFound(JsonNode node) {
        if (!node.has("error")) {
            return false;
        }
        var errorText = node.path("error").asText().toLowerCase();
        return errorText.contains("not found") || errorText.contains("404");
    }

    /// Specification for rendering tabular output.
    record TableSpec(String title, List<Column> columns, String arrayPath) {}

    /// Column definition for table rendering.
    record Column(String header, String jsonPath, int width) {}

    record unused() implements OutputFormatter {}
}
