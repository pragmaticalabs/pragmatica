// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

import tools.jackson.databind.JsonNode;


public sealed interface OutputFormatter {
    JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    static int printQuery(String json, OutputOptions options) {
        return printQuery(json, options, null);
    }

    static int printQuery(String json, OutputOptions options, TableSpec tableSpec) {
        if (options.isQuiet()) {return ExitCode.SUCCESS;}
        return switch (options.format()){
            case JSON -> printJson(json);
            case VALUE -> printValue(json, options.field());
            case TABLE -> printTable(json, tableSpec);
            case CSV -> printCsv(json, tableSpec);
        };
    }

    static int printAction(String json, OutputOptions options, String successMessage) {
        if (options.isQuiet()) {return ExitCode.SUCCESS;}
        return switch (options.format()){
            case JSON -> printJson(json);
            case VALUE -> printValue(json, options.field());
            case TABLE -> printSuccessMessage(successMessage);
            case CSV -> printJson(json);
        };
    }

    private static int printJson(String json) {
        return MAPPER.prettyPrint(json).fold(OutputFormatter::handleParseError, OutputFormatter::printToStdout);
    }

    private static int printValue(String json, String fieldPath) {
        if (fieldPath == null) {return printJson(json);}
        // Detect server-side error envelope (e.g., `{"error":"..."}` from a forward failure
        // or 4xx/5xx response). Surface the actual error instead of the misleading
        // "Path not found: <fieldPath>" — that message implied the field was renamed
        // when in reality the response body was an error envelope without the expected shape.
        if (isErrorResponse(json)) {
            System.err.println("Error: " + extractErrorMessage(json));
            return ExitCode.ERROR;
        }
        return MAPPER.extractField(json, fieldPath)
                                  .fold(OutputFormatter::handleFieldError, OutputFormatter::printToStdout);
    }

    private static int printTable(String json, TableSpec tableSpec) {
        if (tableSpec == null) {return printJson(json);}
        return MAPPER.readTree(json).fold(OutputFormatter::handleParseError, node -> renderTable(node, tableSpec));
    }

    private static int printCsv(String json, TableSpec tableSpec) {
        if (tableSpec == null) {return printJson(json);}
        return MAPPER.readTree(json).fold(OutputFormatter::handleParseError, node -> renderCsv(node, tableSpec));
    }

    private static int renderTable(JsonNode root, TableSpec tableSpec) {
        var dataNodes = resolveDataNodes(root, tableSpec.arrayPath());
        var out = System.out;
        printTableHeader(out, tableSpec.columns());
        printTableSeparator(out, tableSpec.columns());
        dataNodes.forEach(node -> printTableRow(out, node, tableSpec.columns()));
        return ExitCode.SUCCESS;
    }

    private static void printTableHeader(PrintStream out, List<Column> columns) {
        var format = buildFormatString(columns);
        var headers = columns.stream().map(Column::header)
                                    .toArray();
        out.printf(format, headers);
    }

    private static void printTableSeparator(PrintStream out, List<Column> columns) {
        var joiner = new StringJoiner("  ");
        columns.forEach(col -> joiner.add("\u2500".repeat(col.width())));
        out.println(joiner);
    }

    private static void printTableRow(PrintStream out, JsonNode node, List<Column> columns) {
        var format = buildFormatString(columns);
        var values = columns.stream().map(col -> extractColumnValue(node, col))
                                   .toArray();
        out.printf(format, values);
    }

    private static String buildFormatString(List<Column> columns) {
        var joiner = new StringJoiner("  ");
        columns.forEach(col -> joiner.add("%-" + col.width() + "s"));
        return joiner + "%n";
    }

    private static String extractColumnValue(JsonNode node, Column column) {
        var value = navigateToField(node, column.jsonPath());
        return value.length() > column.width()
              ? value.substring(0, column.width())
              : value;
    }

    private static int renderCsv(JsonNode root, TableSpec tableSpec) {
        var dataNodes = resolveDataNodes(root, tableSpec.arrayPath());
        var out = System.out;
        printCsvHeader(out, tableSpec.columns());
        dataNodes.forEach(node -> printCsvRow(out, node, tableSpec.columns()));
        return ExitCode.SUCCESS;
    }

    private static void printCsvHeader(PrintStream out, List<Column> columns) {
        var joiner = new StringJoiner(",");
        columns.forEach(col -> joiner.add(col.header()));
        out.println(joiner);
    }

    private static void printCsvRow(PrintStream out, JsonNode node, List<Column> columns) {
        var joiner = new StringJoiner(",");
        columns.forEach(col -> joiner.add(escapeCsvValue(navigateToField(node, col.jsonPath()))));
        out.println(joiner);
    }

    private static String escapeCsvValue(String value) {
        return value.contains(",") || value.contains("\"")
              ? "\"" + value.replace("\"", "\"\"") + "\""
              : value;
    }

    private static String navigateToField(JsonNode node, String dotPath) {
        var current = node;
        for (var segment : dotPath.split("\\.")) {
            current = current.path(segment);
            if (current.isMissingNode()) {return "";}
        }
        return current.isValueNode()
              ? current.asText()
              : current.toString();
    }

    private static List<JsonNode> resolveDataNodes(JsonNode root, String arrayPath) {
        var target = arrayPath != null
                    ? navigateToNode(root, arrayPath)
                    : root;
        var nodes = new ArrayList<JsonNode>();
        if (target.isArray()) {target.forEach(nodes::add);} else {nodes.add(target);}
        return nodes;
    }

    private static JsonNode navigateToNode(JsonNode root, String dotPath) {
        var current = root;
        for (var segment : dotPath.split("\\.")) {current = current.path(segment);}
        return current;
    }

    private static int printToStdout(String text) {
        System.out.println(text);
        return ExitCode.SUCCESS;
    }

    private static int printSuccessMessage(String message) {
        System.out.println(message);
        return ExitCode.SUCCESS;
    }

    private static int handleParseError(Cause cause) {
        System.err.println("Error: failed to parse JSON: " + cause.message());
        return ExitCode.ERROR;
    }

    private static int handleFieldError(Cause cause) {
        System.err.println("Error: field not found: " + cause.message());
        return ExitCode.NOT_FOUND;
    }

    static boolean isErrorResponse(String json) {
        return MAPPER.readTree(json).map(OutputFormatter::looksLikeError).or(false);
    }

    static boolean isNotFoundResponse(String json) {
        return MAPPER.readTree(json).map(OutputFormatter::containsNotFound).or(false);
    }

    static String extractErrorMessage(String json) {
        return MAPPER.readTree(json).map(OutputFormatter::extractErrorField).or(json);
    }

    /// RFC 9457 ProblemDetail (`application/problem+json`) carries an integer `status` member
    /// for client classification. Legacy `{"error":"..."}` envelopes do not — those remain
    /// detectable via the `error` field for backward compat with any external endpoint that
    /// hasn't migrated to the canonical envelope.
    private static boolean looksLikeError(JsonNode node) {
        if (node == null || !node.isObject()) {return false;}
        if (isProblemDetail(node)) {return true;}
        return node.has("error");
    }

    private static boolean isProblemDetail(JsonNode node) {
        var statusNode = node.path("status");
        return statusNode.isInt() && statusNode.asInt() >= 400 && statusNode.asInt() < 600;
    }

    private static String extractErrorField(JsonNode node) {
        if (isProblemDetail(node)) {
            var detail = node.path("detail");
            if (detail.isTextual()) {return detail.asText();}
            var title = node.path("title");
            if (title.isTextual()) {return title.asText();}
            return node.toString();
        }
        var errorNode = node.path("error");
        return errorNode.isMissingNode() ? node.toString() : errorNode.asText();
    }

    static int printError(String message, OutputOptions options) {
        if (options.format() == OutputFormat.JSON) {System.err.println("{\"error\":\"" + message.replace("\"", "\\\"") + "\"}");} else {System.err.println("Error: " + message);}
        return ExitCode.ERROR;
    }

    static int printNotFound(String message, OutputOptions options) {
        if (options.format() == OutputFormat.JSON) {System.err.println("{\"error\":\"" + message.replace("\"", "\\\"") + "\"}");} else {System.err.println("Error: " + message);}
        return ExitCode.NOT_FOUND;
    }

    static int checkResponseError(String response, OutputOptions options, String actionLabel) {
        if (!isErrorResponse(response)) {return - 1;}
        var message = actionLabel + ": " + extractErrorMessage(response);
        return isNotFoundResponse(response)
              ? printNotFound(message, options)
              : printError(message, options);
    }

    private static boolean containsNotFound(JsonNode node) {
        if (isProblemDetail(node)) {return node.path("status").asInt() == 404;}
        if (!node.has("error")) {return false;}
        var errorText = node.path("error").asText()
                                 .toLowerCase();
        return errorText.contains("not found") || errorText.contains("404");
    }

    record TableSpec(String title, List<Column> columns, String arrayPath){}

    record Column(String header, String jsonPath, int width){}

    record unused() implements OutputFormatter{}
}
