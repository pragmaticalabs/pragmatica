package org.pragmatica.jbct.derive.emit;

import java.util.List;

/// A minimal JSON emitter for the machine output of `derive` (SPEC.md §4 emit).
///
/// Rationale (SPEC.md §7 asks to check for an existing codec first): pragmatica-lite's only JSON
/// codec is the `integrations/json` Jackson aggregator, an integrations-tier module. Depending on
/// it would pull Jackson and the integrations stack into `jbct-derive`, whose POM and SPEC mandate
/// a `jbct-core`-only dependency. The derive result is a small, fixed, tree of strings, numbers and
/// booleans, so a hand-rolled compact emitter — a few lines with correct escaping — keeps the
/// module dependency-clean, exactly the trade the SPEC's own Rule-5 arithmetic recommends.
public sealed interface Json permits Json.unused {
    record unused() implements Json {}

    /// One `"key": value` member of a JSON object, the value already rendered as JSON.
    record Field(String key, String valueJson) {}

    /// A string member with the value JSON-escaped.
    static Field str(String key, String value) {
        return new Field(key, string(value));
    }

    /// A numeric member.
    static Field num(String key, long value) {
        return new Field(key, Long.toString(value));
    }

    /// A raw member whose value is already rendered JSON (an object or array).
    static Field raw(String key, String valueJson) {
        return new Field(key, valueJson);
    }

    /// A JSON object from its members.
    static String object(List<Field> fields) {
        return "{" + String.join(",", fields.stream().map(Json::member).toList()) + "}";
    }

    /// A JSON array from already-rendered element JSON.
    static String array(List<String> elementsJson) {
        return "[" + String.join(",", elementsJson) + "]";
    }

    /// An array of strings, each escaped.
    static String stringArray(List<String> values) {
        return array(values.stream().map(Json::string).toList());
    }

    /// A JSON string literal with escaping.
    static String string(String value) {
        return "\"" + escape(value) + "\"";
    }

    private static String member(Field field) {
        return string(field.key()) + ":" + field.valueJson();
    }

    private static String escape(String value) {
        var out = new StringBuilder(value.length() + 8);

        for (int i = 0; i < value.length(); i++) {
            out.append(escapeChar(value.charAt(i)));
        }

        return out.toString();
    }

    private static String escapeChar(char c) {
        return switch (c) {
            case '"' -> "\\\"";
            case '\\' -> "\\\\";
            case '\b' -> "\\b";
            case '\f' -> "\\f";
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            case '\t' -> "\\t";
            default -> c < 0x20
                       ? "\\u%04x".formatted((int) c)
                       : String.valueOf(c);
        };
    }
}
