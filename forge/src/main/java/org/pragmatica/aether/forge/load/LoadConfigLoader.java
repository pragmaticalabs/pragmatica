package org.pragmatica.aether.forge.load;

import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/**
 * Loads load generation configuration from TOML files.
 * <p>
 * Example configuration:
 * <pre>
 * [[load]]
 * target = "InventoryService.checkStock"
 * rate = "100/s"
 * duration = "5m"
 *
 * [load.path]
 * sku = "${random:SKU-#####}"
 *
 * [load.body]
 * quantity = "${range:1-100}"
 * </pre>
 */
public sealed interface LoadConfigLoader {
    /**
     * Load configuration from file path.
     */
    static Result<LoadConfig> load(Path path) {
        return TomlParser.parseFile(path)
                         .flatMap(LoadConfigLoader::fromDocument);
    }

    /**
     * Load configuration from TOML string content.
     */
    static Result<LoadConfig> loadFromString(String content) {
        return TomlParser.parse(content)
                         .flatMap(LoadConfigLoader::fromDocument);
    }

    Cause NO_LOAD_SECTIONS = new LoadConfigError.ParseFailed("No [[load]] sections found");
    Cause TARGET_REQUIRED = new LoadConfigError.ParseFailed("target is required");
    Cause RATE_REQUIRED = new LoadConfigError.ParseFailed("rate is required");

    @SuppressWarnings("unchecked")
    private static Result<LoadConfig> fromDocument(org.pragmatica.config.toml.TomlDocument doc) {
        return doc.getTableArray("load")
                  .toResult(NO_LOAD_SECTIONS)
                  .flatMap(LoadConfigLoader::parseTables);
    }

    @SuppressWarnings("unchecked")
    private static Result<LoadConfig> parseTables(List<Map<String, Object>> tables) {
        var indexedResults = IntStream.range(0,
                                             tables.size())
                                      .mapToObj(i -> parseLoadTarget(tables.get(i),
                                                                     i)
                                                                    .mapError(cause -> indexedError(i, cause)))
                                      .toList();
        return Result.allOf(indexedResults)
                     .flatMap(LoadConfig::loadConfig);
    }

    private static Cause indexedError(int index, Cause cause) {
        return new LoadConfigError.ParseFailed("load[" + index + "]: " + cause.message());
    }

    @SuppressWarnings("unchecked")
    private static Result<LoadTarget> parseLoadTarget(Map<String, Object> table, int index) {
        // Required: target and rate
        var targetResult = Option.option((String) table.get("target"))
                                 .filter(s -> !s.isBlank())
                                 .toResult(TARGET_REQUIRED);
        var rateResult = Option.option((String) table.get("rate"))
                               .filter(s -> !s.isBlank())
                               .toResult(RATE_REQUIRED);
        return Result.all(targetResult, rateResult)
                     .flatMap((target, rateStr) -> buildLoadTarget(table, target, rateStr));
    }

    @SuppressWarnings("unchecked")
    private static Result<LoadTarget> buildLoadTarget(Map<String, Object> table, String target, String rateStr) {
        // Optional: name
        Option<String> name = table.containsKey("name")
                              ? some((String) table.get("name"))
                              : none();
        // Optional: duration
        Option<Duration> duration = table.containsKey("duration")
                                    ? parseDuration((String) table.get("duration"))
                                    : none();
        // Optional: path variables
        Map<String, String> pathVars = new HashMap<>();
        if (table.containsKey("path")) {
            var pathTable = (Map<String, Object>) table.get("path");
            for (var entry : pathTable.entrySet()) {
                pathVars.put(entry.getKey(),
                             String.valueOf(entry.getValue()));
            }
        }
        // Optional: body (can be string or table that we convert to JSON-like)
        Option<String> body = none();
        if (table.containsKey("body")) {
            var bodyValue = table.get("body");
            if (bodyValue instanceof String s) {
                body = some(s);
            } else if (bodyValue instanceof Map) {
                // Convert map to JSON-like string
                body = some(mapToJsonString((Map<String, Object>) bodyValue));
            }
        }
        return LoadTarget.loadTarget(name, target, rateStr, duration, pathVars, body);
    }

    private static Option<Duration> parseDuration(String durationStr) {
        return Option.option(durationStr)
                     .map(String::trim)
                     .filter(s -> !s.isBlank() && !"0".equals(s))
                     .flatMap(LoadConfigLoader::parseDurationValue);
    }

    private static Option<Duration> parseDurationValue(String durationStr) {
        var str = durationStr.toLowerCase();
        try{
            if (str.endsWith("ms")) {
                return some(Duration.ofMillis(Long.parseLong(str.substring(0, str.length() - 2))));
            } else if (str.endsWith("s")) {
                return some(Duration.ofSeconds(Long.parseLong(str.substring(0, str.length() - 1))));
            } else if (str.endsWith("m")) {
                return some(Duration.ofMinutes(Long.parseLong(str.substring(0, str.length() - 1))));
            } else if (str.endsWith("h")) {
                return some(Duration.ofHours(Long.parseLong(str.substring(0, str.length() - 1))));
            } else {
                // Assume seconds if no unit
                return some(Duration.ofSeconds(Long.parseLong(str)));
            }
        } catch (NumberFormatException e) {
            return none();
        }
    }

    private static String mapToJsonString(Map<String, Object> map) {
        var sb = new StringBuilder("{");
        var first = true;
        for (var entry : map.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append("\"")
              .append(entry.getKey())
              .append("\": ");
            appendJsonValue(sb, entry.getValue());
        }
        sb.append("}");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendJsonValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            sb.append("\"")
              .append(escapeJson(s))
              .append("\"");
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Map) {
            sb.append(mapToJsonString((Map<String, Object>) value));
        } else if (value instanceof List< ?> list) {
            sb.append("[");
            var first = true;
            for (var item : list) {
                if (!first) sb.append(", ");
                first = false;
                appendJsonValue(sb, item);
            }
            sb.append("]");
        } else {
            sb.append("\"")
              .append(escapeJson(value.toString()))
              .append("\"");
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    record Unused() implements LoadConfigLoader {}
}
