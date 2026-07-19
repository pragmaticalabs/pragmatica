package org.pragmatica.jbct.derive.parse;

import java.util.List;
import java.util.Map;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.lang.Option;

/// Low-level, null-safe accessors over the raw `Map`/`Object` shapes the [TomlParser] produces.
///
/// [org.pragmatica.config.toml.TomlDocument] exposes typed getters for scalar section values, but
/// answer sheets lean on array-of-tables and arrays of inline tables, whose elements arrive as
/// plain `Map`/`List` objects. These helpers wrap that untyped boundary in [Option] once, so the
/// parsers above stay free of null checks and casts.
public sealed interface TomlAccess {
    record unused() implements TomlAccess {}

    /// A trimmed string value from a row map, if present.
    static Option<String> str(Map<String, Object> row, String key) {
        return Option.option(row.get(key)).map(Object::toString).map(String::trim);
    }

    /// A boolean value from a row map, if present and boolean-typed.
    static Option<Boolean> bool(Map<String, Object> row, String key) {
        return Option.option(row.get(key)).flatMap(TomlAccess::asBoolean);
    }

    /// A long value from a row map, if present and integer-typed.
    static Option<Long> longVal(Map<String, Object> row, String key) {
        return Option.option(row.get(key)).flatMap(TomlAccess::asLong);
    }

    /// A list of strings from a row map, or an empty list if absent or not a list.
    static List<String> strList(Map<String, Object> row, String key) {
        return Option.option(row.get(key))
                     .flatMap(TomlAccess::asList)
                     .map(TomlAccess::toStrings)
                     .or(List.of());
    }

    /// A raw section value (used to reach arrays of inline tables inside a `[section]`).
    static Option<Object> sectionValue(TomlDocument doc, String section, String key) {
        return Option.option(doc.sections().get(section)).flatMap(map -> Option.option(map.get(key)));
    }

    /// View an object as a nested table map, if it is one.
    @SuppressWarnings("unchecked")
    static Option<Map<String, Object>> asMap(Object value) {
        return value instanceof Map<?, ?> map
               ? Option.some((Map<String, Object>) map)
               : Option.none();
    }

    /// View an object as a list, if it is one.
    static Option<List<Object>> asList(Object value) {
        return value instanceof List<?> list
               ? Option.some(List.<Object>copyOf(list))
               : Option.none();
    }

    private static Option<Boolean> asBoolean(Object value) {
        return value instanceof Boolean b
               ? Option.some(b)
               : Option.none();
    }

    private static Option<Long> asLong(Object value) {
        return switch (value) {
            case Long l -> Option.some(l);
            case Integer i -> Option.some(i.longValue());
            default -> Option.none();
        };
    }

    private static List<String> toStrings(List<Object> list) {
        return list.stream().map(Object::toString).toList();
    }
}
