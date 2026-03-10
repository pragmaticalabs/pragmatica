/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.config.toml;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/// Serializes a [TomlDocument] to TOML text format.
///
/// Handles all value types: strings (with escaping), integers, doubles, booleans,
/// arrays, inline tables, and array of tables. Keys containing special characters
/// are automatically quoted.
///
/// Example usage:
/// ```java
/// var toml = TomlWriter.toToml(document);
/// var tomlWithHeader = TomlWriter.toToml(document, List.of("Generated config", "Do not edit"));
/// ```
public final class TomlWriter {
    private static final Pattern BARE_KEY_PATTERN = Pattern.compile("[a-zA-Z0-9_-]+");

    private TomlWriter() {}

    /// Serialize a TomlDocument to TOML text.
    public static String toToml(TomlDocument document) {
        return toToml(document, List.of());
    }

    /// Serialize a TomlDocument to TOML text with comment header lines.
    public static String toToml(TomlDocument document, List<String> headerComments) {
        var sb = new StringBuilder();
        appendHeaderComments(sb, headerComments);
        appendRootSection(sb, document);
        appendNamedSections(sb, document);
        appendTableArrays(sb, document);
        return sb.toString().stripTrailing() + "\n";
    }

    private static void appendHeaderComments(StringBuilder sb, List<String> comments) {
        comments.forEach(comment -> sb.append("# ").append(comment).append('\n'));
        if (!comments.isEmpty()) {
            sb.append('\n');
        }
    }

    private static void appendRootSection(StringBuilder sb, TomlDocument document) {
        var rootKeys = document.sections().getOrDefault("", Map.of());
        rootKeys.forEach((key, value) -> appendKeyValue(sb, key, value));
    }

    private static void appendNamedSections(StringBuilder sb, TomlDocument document) {
        document.sections().entrySet().stream()
            .filter(e -> !e.getKey().isEmpty())
            .forEach(e -> appendSectionBlock(sb, e.getKey(), e.getValue()));
    }

    private static void appendSectionBlock(StringBuilder sb, String name, Map<String, Object> entries) {
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append('[').append(name).append("]\n");
        entries.forEach((key, value) -> appendKeyValue(sb, key, value));
    }

    private static void appendTableArrays(StringBuilder sb, TomlDocument document) {
        document.tableArrays().forEach((name, tables) ->
            tables.forEach(table -> appendTableArrayEntry(sb, name, table))
        );
    }

    private static void appendTableArrayEntry(StringBuilder sb, String name, Map<String, Object> table) {
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append("[[").append(name).append("]]\n");
        table.forEach((key, value) -> appendKeyValue(sb, key, value));
    }

    private static void appendKeyValue(StringBuilder sb, String key, Object value) {
        sb.append(formatKey(key)).append(" = ").append(formatValue(value)).append('\n');
    }

    private static String formatKey(String key) {
        return BARE_KEY_PATTERN.matcher(key).matches() ? key : "\"" + escapeString(key) + "\"";
    }

    private static String formatValue(Object value) {
        if (value instanceof String s) {
            return formatStringValue(s);
        }
        if (value instanceof Boolean b) {
            return b.toString();
        }
        if (value instanceof Long l) {
            return l.toString();
        }
        if (value instanceof Integer i) {
            return i.toString();
        }
        if (value instanceof Double d) {
            return formatDoubleValue(d);
        }
        if (value instanceof List<?> list) {
            return formatListValue(list);
        }
        if (value instanceof Map<?, ?> map) {
            return formatInlineTable(map);
        }
        return "\"" + escapeString(value.toString()) + "\"";
    }

    private static String formatStringValue(String s) {
        return "\"" + escapeString(s) + "\"";
    }

    private static String formatDoubleValue(Double d) {
        if (d.isInfinite()) {
            return d > 0 ? "inf" : "-inf";
        }
        if (d.isNaN()) {
            return "nan";
        }
        return d.toString();
    }

    private static String formatListValue(List<?> list) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(formatValue(list.get(i)));
        }
        sb.append(']');
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static String formatInlineTable(Map<?, ?> map) {
        var sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : map.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(formatKey(entry.getKey().toString()))
              .append(" = ")
              .append(formatValue(entry.getValue()));
        }
        sb.append('}');
        return sb.toString();
    }

    private static String escapeString(String s) {
        var sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
