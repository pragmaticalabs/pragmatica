package org.pragmatica.aether.cli.cluster;

import java.util.HashMap;
import java.util.Map;

/// Minimal JSON field extractor for CLI display purposes.
///
/// Extracts top-level scalar fields from a flat JSON object.
/// Does not handle nested objects or arrays -- returns them as raw strings.
/// Used only for human-readable CLI output formatting.
sealed interface SimpleJsonReader {
    record unused() implements SimpleJsonReader {}

    /// Parse a JSON object string into a map of field name to string value.
    /// Handles strings, numbers, booleans, and null at the top level.
    @SuppressWarnings({"JBCT-PAT-01", "JBCT-SEQ-01"})
    static Map<String, String> parseObject(String json) {
        var result = new HashMap<String, String>();
        var trimmed = json.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return result;
        }
        var content = trimmed.substring(1, trimmed.length() - 1);
        var pos = 0;
        while (pos < content.length()) {
            pos = skipWhitespace(content, pos);
            if (pos >= content.length()) break;
            if (content.charAt(pos) != '"') break;
            var keyEnd = content.indexOf('"', pos + 1);
            if (keyEnd < 0) break;
            var key = content.substring(pos + 1, keyEnd);
            pos = skipWhitespace(content, keyEnd + 1);
            if (pos >= content.length() || content.charAt(pos) != ':') break;
            pos = skipWhitespace(content, pos + 1);
            var valueResult = extractValue(content, pos);
            result.put(key, valueResult.value());
            pos = valueResult.endPos();
            pos = skipWhitespace(content, pos);
            if (pos < content.length() && content.charAt(pos) == ',') pos++;
        }
        return result;
    }

    private static int skipWhitespace(String s, int pos) {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        return pos;
    }

    private static ValueResult extractValue(String content, int pos) {
        if (pos >= content.length()) return new ValueResult("", pos);
        var ch = content.charAt(pos);
        if (ch == '"') return extractStringValue(content, pos);
        if (ch == '[' || ch == '{') return extractNestedValue(content, pos);
        return extractScalarValue(content, pos);
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static ValueResult extractStringValue(String content, int pos) {
        var sb = new StringBuilder();
        var i = pos + 1;
        while (i < content.length()) {
            var c = content.charAt(i);
            if (c == '\\' && i + 1 < content.length()) {
                sb.append(content.charAt(i + 1));
                i += 2;
            } else if (c == '"') {
                return new ValueResult(sb.toString(), i + 1);
            } else {
                sb.append(c);
                i++;
            }
        }
        return new ValueResult(sb.toString(), i);
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static ValueResult extractNestedValue(String content, int pos) {
        var open = content.charAt(pos);
        var close = open == '['
                    ? ']'
                    : '}';
        var depth = 1;
        var i = pos + 1;
        while (i < content.length() && depth > 0) {
            var c = content.charAt(i);
            if (c == open) depth++;else if (c == close) depth--;else if (c == '"') i = skipQuotedString(content, i);
            i++;
        }
        return new ValueResult(content.substring(pos, i), i);
    }

    private static int skipQuotedString(String content, int pos) {
        var i = pos + 1;
        while (i < content.length()) {
            if (content.charAt(i) == '\\') i++;else if (content.charAt(i) == '"') return i;
            i++;
        }
        return i;
    }

    private static ValueResult extractScalarValue(String content, int pos) {
        var end = pos;
        while (end < content.length() && content.charAt(end) != ',' && content.charAt(end) != '}') end++;
        return new ValueResult(content.substring(pos, end)
                                      .trim(),
                               end);
    }

    record ValueResult(String value, int endPos) {}
}
