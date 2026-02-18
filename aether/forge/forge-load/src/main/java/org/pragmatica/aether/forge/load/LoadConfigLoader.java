package org.pragmatica.aether.forge.load;

import org.pragmatica.config.toml.TomlDocument;
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
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.parse.Number.parseLong;

/// Loads load generation configuration from TOML files.
///
/// Example configuration:
/// ```
/// [[load]]
/// target = "InventoryService.checkStock"
/// rate = "100/s"
/// duration = "5m"
///
/// [load.path]
/// sku = "${random:SKU-#####}"
///
/// [load.body]
/// quantity = "${range:1-100}"
/// ```
public sealed interface LoadConfigLoader {
    /// Load configuration from file path.
    static Result<LoadConfig> load(Path path) {
        return TomlParser.parseFile(path)
                         .flatMap(LoadConfigLoader::fromDocument);
    }

    /// Load configuration from TOML string content.
    static Result<LoadConfig> loadFromString(String content) {
        return TomlParser.parse(content)
                         .flatMap(LoadConfigLoader::fromDocument);
    }

    Cause NO_LOAD_SECTIONS = LoadConfigError.ParseFailed.parseFailed("No [[load]] sections found")
                                           .unwrap();
    Cause TARGET_REQUIRED = LoadConfigError.ParseFailed.parseFailed("target is required")
                                          .unwrap();
    Cause RATE_REQUIRED = LoadConfigError.ParseFailed.parseFailed("rate is required")
                                        .unwrap();

    @SuppressWarnings("unchecked")
    private static Result<LoadConfig> fromDocument(TomlDocument doc) {
        return doc.getTableArray("load")
                  .toResult(NO_LOAD_SECTIONS)
                  .flatMap(LoadConfigLoader::collectTargets);
    }

    @SuppressWarnings("unchecked")
    private static Result<LoadConfig> collectTargets(List<Map<String, Object>> tables) {
        var indexedResults = IntStream.range(0,
                                             tables.size())
                                      .mapToObj(i -> toTargetWithIndex(tables.get(i),
                                                                       i))
                                      .toList();
        return Result.allOf(indexedResults)
                     .flatMap(LoadConfigLoader::toLoadConfig);
    }

    private static Result<LoadConfig> toLoadConfig(List<LoadTarget> targets) {
        return LoadConfig.loadConfig(targets);
    }

    private static Result<LoadTarget> toTargetWithIndex(Map<String, Object> table, int index) {
        return toLoadTarget(table, index).mapError(cause -> indexedError(index, cause));
    }

    private static Cause indexedError(int index, Cause cause) {
        return LoadConfigError.ParseFailed.parseFailed("load[" + index + "]: " + cause.message())
                              .unwrap();
    }

    @SuppressWarnings("unchecked")
    private static Result<LoadTarget> toLoadTarget(Map<String, Object> table, int index) {
        var targetResult = extractRequired(table, "target", TARGET_REQUIRED);
        var rateResult = extractRequired(table, "rate", RATE_REQUIRED);
        return Result.all(targetResult, rateResult)
                     .flatMap((target, rateStr) -> assembleTarget(table, target, rateStr));
    }

    private static Result<String> extractRequired(Map<String, Object> table, String key, Cause cause) {
        return option((String) table.get(key)).filter(s -> !s.isBlank())
                     .toResult(cause);
    }

    @SuppressWarnings("unchecked")
    private static Result<LoadTarget> assembleTarget(Map<String, Object> table, String target, String rateStr) {
        var name = extractName(table);
        var duration = extractDuration(table);
        var pathVars = extractPathVars(table);
        var body = extractBody(table);
        return LoadTarget.loadTarget(name, target, rateStr, duration, pathVars, body);
    }

    private static Option<String> extractName(Map<String, Object> table) {
        return table.containsKey("name")
               ? some((String) table.get("name"))
               : none();
    }

    private static Option<Duration> extractDuration(Map<String, Object> table) {
        return table.containsKey("duration")
               ? toDuration((String) table.get("duration"))
               : none();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> extractPathVars(Map<String, Object> table) {
        if (!table.containsKey("path")) {
            return Map.of();
        }
        var pathTable = (Map<String, Object>) table.get("path");
        var pathVars = new HashMap<String, String>();
        pathTable.forEach((key, value) -> pathVars.put(key, String.valueOf(value)));
        return pathVars;
    }

    @SuppressWarnings("unchecked")
    private static Option<String> extractBody(Map<String, Object> table) {
        if (!table.containsKey("body")) {
            return none();
        }
        var bodyValue = table.get("body");
        if (bodyValue instanceof String s) {
            return some(s);
        } else if (bodyValue instanceof Map) {
            return some(mapToJsonString((Map<String, Object>) bodyValue));
        }
        return none();
    }

    private static Option<Duration> toDuration(String durationStr) {
        var trimmed = option(durationStr).map(String::trim)
                            .filter(s -> !s.isBlank() && !"0".equals(s));
        return trimmed.map(String::toLowerCase)
                      .flatMap(LoadConfigLoader::toDurationValue);
    }

    private static Option<Duration> toDurationValue(String str) {
        if (str.endsWith("ms")) {
            return toDurationFromParts(stripSuffix(str, 2), "ms");
        } else if (str.endsWith("s")) {
            return toDurationFromParts(stripSuffix(str, 1), "s");
        } else if (str.endsWith("m")) {
            return toDurationFromParts(stripSuffix(str, 1), "m");
        } else if (str.endsWith("h")) {
            return toDurationFromParts(stripSuffix(str, 1), "h");
        } else {
            return toDurationFromParts(str, "s");
        }
    }

    private static String stripSuffix(String str, int suffixLength) {
        return str.substring(0, str.length() - suffixLength);
    }

    private static Option<Duration> toDurationFromParts(String value, String unit) {
        return parseLong(value).map(v -> toDurationWithUnit(v, unit))
                        .option();
    }

    private static Duration toDurationWithUnit(long value, String unit) {
        return switch (unit) {
            case "ms" -> Duration.ofMillis(value);
            case "s" -> Duration.ofSeconds(value);
            case "m" -> Duration.ofMinutes(value);
            case "h" -> Duration.ofHours(value);
            default -> Duration.ofSeconds(value);
        };
    }

    private static String mapToJsonString(Map<String, Object> map) {
        var sb = new StringBuilder("{");
        var first = true;
        for (var entry : map.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            appendJsonKey(sb, entry.getKey());
            appendJsonValue(sb, entry.getValue());
        }
        sb.append("}");
        return sb.toString();
    }

    private static void appendJsonKey(StringBuilder sb, String key) {
        sb.append("\"");
        sb.append(key);
        sb.append("\": ");
    }

    @SuppressWarnings("unchecked")
    private static void appendJsonValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            appendQuoted(sb, escapeJson(s));
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Map) {
            sb.append(mapToJsonString((Map<String, Object>) value));
        } else if (value instanceof List<?> list) {
            appendJsonList(sb, list);
        } else {
            appendQuoted(sb, escapeJson(value.toString()));
        }
    }

    private static void appendQuoted(StringBuilder sb, String text) {
        sb.append("\"");
        sb.append(text);
        sb.append("\"");
    }

    private static void appendJsonList(StringBuilder sb, List<?> list) {
        sb.append("[");
        var first = true;
        for (var item : list) {
            if (!first) sb.append(", ");
            first = false;
            appendJsonValue(sb, item);
        }
        sb.append("]");
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    record unused() implements LoadConfigLoader {}
}
