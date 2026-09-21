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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// Immutable TOML document providing typed access to configuration values.
///
/// Values are accessed via fluent API that returns [Option] for nullable results:
/// <pre>{@code
/// document.getString("database", "host")     // Option<String>
/// document.getInt("server", "port")          // Option<Integer>
/// document.getBoolean("features", "enabled") // Option<Boolean>
/// document.getStringList("tags", "values")   // Option<List<String>>
/// }</pre>
///
/// Root-level properties use empty string as section:
/// <pre>{@code
/// document.getString("", "title")  // Root-level 'title' property
/// }</pre>
///
/// Array of tables (TOML `[[section]]` syntax) are accessed via:
/// <pre>{@code
/// document.getTableArray("products")  // Option<List<Map<String, Object>>>
/// }</pre>
///
/// The typed getters answer `Option`, and an absent key and a PRESENT key of the wrong TOML type
/// (`port = "80x"`, `enabled = "yes"`, `tags = "a"`) both read as empty — which is what lets every
/// `.or(default)` caller apply its default to a value the operator did write (#1098). The getters
/// keep their shape (they have ~80 `.or(default)` call sites), and instead RECORD each such read on
/// the document's [TypeMismatchLedger]; a parser checks [#requireNoTypeMismatches()] once after
/// its reads and fails the load naming every offending key, value and expected type. The ledger is
/// read-side state, not content: it is fresh on every parsed, merged or [#with]-derived document and
/// excluded from [#equals] / [#hashCode].
///
/// @param sections            Map of section names to their key-value pairs
/// @param tableArrays         Map of array table names to list of table maps
/// @param typeMismatchLedger  Reads that found the key present with the wrong type (#1098)
public record TomlDocument(Map<String, Map<String, Object>> sections,
                           Map<String, List<Map<String, Object>>> tableArrays,
                           TypeMismatchLedger typeMismatchLedger) {
    /// Empty document constant.
    public static final TomlDocument EMPTY = new TomlDocument(Map.of("", Map.of()),
                                                              Map.of());

    /// Canonical constructor ensuring immutable storage.
    public TomlDocument {
        sections = Map.copyOf(sections);
        tableArrays = Map.copyOf(tableArrays);
    }

    /// The constructor every producer uses: content only, with a fresh ledger.
    public TomlDocument(Map<String, Map<String, Object>> sections, Map<String, List<Map<String, Object>>> tableArrays) {
        this(sections, tableArrays, new TypeMismatchLedger());
    }

    /// One read that found `section.key` present but not of the type the reader asked for.
    public record TypeMismatch(String section, String key, String expected, String raw) {
        public String describe() {
            return (section.isEmpty()
                    ? key
                    : section + "." + key) + ": expected " + expected + ", got \"" + raw + "\"";
        }
    }

    /// Append-only, thread-safe (a document may be read from several threads); equality is by the
    /// recorded list so two untouched ledgers compare equal.
    public static final class TypeMismatchLedger {
        private final ConcurrentLinkedQueue<TypeMismatch> mismatches = new ConcurrentLinkedQueue<>();

        void record(String section, String key, String expected, Object raw) {
            mismatches.add(new TypeMismatch(section, key, expected, String.valueOf(raw)));
        }

        public List<TypeMismatch> snapshot() {
            return List.copyOf(mismatches);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof TypeMismatchLedger that && snapshot().equals(that.snapshot());
        }

        @Override
        public int hashCode() {
            return snapshot().hashCode();
        }

        @Override
        public String toString() {
            return snapshot().toString();
        }
    }

    /// Every typed read so far that found its key present with the wrong type, in read order.
    public List<TypeMismatch> typeMismatches() {
        return typeMismatchLedger.snapshot();
    }

    /// The load-time gate (#1098): `this` when no typed read hit a wrong-typed value, otherwise
    /// [TomlError.TypeMismatches] naming every one. A parser calls it once, after its reads.
    public Result<TomlDocument> requireNoTypeMismatches() {
        var mismatches = typeMismatches();

        return mismatches.isEmpty()
               ? Result.success(this)
               : new TomlError.TypeMismatches(mismatches).result();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TomlDocument that
               && sections.equals(that.sections)
               && tableArrays.equals(that.tableArrays);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sections, tableArrays);
    }

    /// Compatibility constructor for documents without array tables.
    public TomlDocument(Map<String, Map<String, Object>> sections) {
        this(sections, Map.of());
    }

    /// Get a string value from the document.
    ///
    /// @param section the section name (empty string for root)
    /// @param key     the property key
    /// @return Option containing the string value, or empty if not found
    public Option<String> getString(String section, String key) {
        return getValue(section, key).map(Object::toString);
    }

    /// Get an integer value from the document.
    ///
    /// @param section the section name (empty string for root)
    /// @param key     the property key
    /// @return Option containing the integer value, or empty if not found or not an integer
    public Option<Integer> getInt(String section, String key) {
        return typed(section, key, "integer", this::toInt);
    }

    /// Get a long value from the document.
    ///
    /// @param section the section name (empty string for root)
    /// @param key     the property key
    /// @return Option containing the long value, or empty if not found or not a number
    public Option<Long> getLong(String section, String key) {
        return typed(section, key, "integer", this::toLong);
    }

    /// Get a double value from the document.
    ///
    /// @param section the section name (empty string for root)
    /// @param key     the property key
    /// @return Option containing the double value, or empty if not found or not a number
    public Option<Double> getDouble(String section, String key) {
        return typed(section, key, "float", this::toDouble);
    }

    /// Get a boolean value from the document.
    ///
    /// @param section the section name (empty string for root)
    /// @param key     the property key
    /// @return Option containing the boolean value, or empty if not found or not a boolean
    public Option<Boolean> getBoolean(String section, String key) {
        return typed(section, key, "boolean", this::toBoolean);
    }

    /// Get a string list from the document.
    ///
    /// @param section the section name (empty string for root)
    /// @param key     the property key
    /// @return Option containing the string list, or empty if not found or not a list
    public Option<List<String>> getStringList(String section, String key) {
        return typed(section, key, "array of strings", this::toStringList);
    }

    /// Get all keys in a section.
    ///
    /// @param section the section name (empty string for root)
    /// @return Set of keys in the section, or empty set if section not found
    public Set<String> keys(String section) {
        return Option.option(sections.get(section))
                     .map(Map::keySet)
                     .or(Set.of());
    }

    /// Get all section names in the document.
    ///
    /// @return Set of section names (includes empty string for root if it has properties)
    public Set<String> sectionNames() {
        return sections.keySet();
    }

    /// Check if a section exists.
    ///
    /// @param section the section name
    /// @return true if the section exists
    public boolean hasSection(String section) {
        return sections.containsKey(section);
    }

    /// Check if a key exists in a section.
    ///
    /// @param section the section name
    /// @param key     the property key
    /// @return true if the key exists in the section
    public boolean hasKey(String section, String key) {
        return Option.option(sections.get(section))
                     .map(m -> m.containsKey(key))
                     .or(false);
    }

    /// Get all key-value pairs from a section as strings.
    ///
    /// @param section the section name
    /// @return Map of key-value pairs, or empty map if section not found
    public Map<String, String> getSection(String section) {
        return Option.option(sections.get(section))
                     .map(this::toStringMap)
                     .or(Map.of());
    }

    /// Create a new document with an additional or updated value.
    ///
    /// @param section the section name
    /// @param key     the property key
    /// @param value   the value to set
    /// @return new TomlDocument with the value set
    public TomlDocument with(String section, String key, Object value) {
        var newSections = new LinkedHashMap<>(sections);
        var sectionMap = new LinkedHashMap<>(newSections.getOrDefault(section, Map.of()));

        sectionMap.put(key, value);
        newSections.put(section, sectionMap);

        return new TomlDocument(Map.copyOf(newSections), tableArrays);
    }

    /// Get an array of tables by name.
    ///
    /// Each `[[name]]` occurrence in TOML creates a new table in the array.
    ///
    /// @param name the array table name
    /// @return Option containing list of table maps, or empty if not found
    public Option<List<Map<String, Object>>> getTableArray(String name) {
        return Option.option(tableArrays.get(name));
    }

    /// Check if an array of tables exists.
    ///
    /// @param name the array table name
    /// @return true if the array of tables exists
    public boolean hasTableArray(String name) {
        return tableArrays.containsKey(name);
    }

    /// Get all array table names in the document.
    ///
    /// @return Set of array table names
    public Set<String> tableArrayNames() {
        return tableArrays.keySet();
    }

    /// Get an inline table value as a map.
    ///
    /// @param section the section name (empty string for root)
    /// @param key     the property key
    /// @return Option containing the map, or empty if not found or not a map
    @SuppressWarnings("unchecked")
    public Option<Map<String, Object>> getInlineTable(String section, String key) {
        return getValue(section, key).flatMap(v -> v instanceof Map<?, ?> m
                                                   ? Option.some((Map<String, Object>) m)
                                                   : Option.none());
    }

    private Option<Object> getValue(String section, String key) {
        return Option.option(sections.get(section)).flatMap(m -> Option.option(m.get(key)));
    }

    /// Absent → empty, silently. Present and convertible → the value. Present and NOT convertible →
    /// empty, and the read is recorded so the load can refuse it (#1098).
    private <T> Option<T> typed(String section, String key, String expected, Fn1<Option<T>, Object> convert) {
        return getValue(section, key).flatMap(raw -> convertOrRecord(section, key, expected, raw, convert));
    }

    private <T> Option<T> convertOrRecord(String section, String key, String expected, Object raw, Fn1<Option<T>, Object> convert) {
        var converted = convert.apply(raw);

        if (converted.isEmpty()) {
            typeMismatchLedger.record(section, key, expected, raw);
        }

        return converted;
    }

    private Option<Integer> toInt(Object value) {
        if (value instanceof Integer i) {
            return Option.some(i);
        }

        if (value instanceof Long l && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
            return Option.some(l.intValue());
        }

        if (value instanceof String s) {
            try {
                return Option.some(Integer.parseInt(s));
            } catch (NumberFormatException _) {
                return Option.none();
            }
        }

        return Option.none();
    }

    private Option<Long> toLong(Object value) {
        if (value instanceof Long l) {
            return Option.some(l);
        }

        if (value instanceof Integer i) {
            return Option.some(i.longValue());
        }

        if (value instanceof String s) {
            try {
                return Option.some(Long.parseLong(s));
            } catch (NumberFormatException _) {
                return Option.none();
            }
        }

        return Option.none();
    }

    private Option<Double> toDouble(Object value) {
        if (value instanceof Double d) {
            return Option.some(d);
        }

        if (value instanceof Long l) {
            return Option.some(l.doubleValue());
        }

        if (value instanceof Integer i) {
            return Option.some(i.doubleValue());
        }

        if (value instanceof String s) {
            try {
                return Option.some(Double.parseDouble(s));
            } catch (NumberFormatException _) {
                return Option.none();
            }
        }

        return Option.none();
    }

    private Option<Boolean> toBoolean(Object value) {
        if (value instanceof Boolean b) {
            return Option.some(b);
        }

        if (value instanceof String s) {
            if ("true".equalsIgnoreCase(s)) {
                return Option.some(true);
            }

            if ("false".equalsIgnoreCase(s)) {
                return Option.some(false);
            }
        }

        return Option.none();
    }

    private Option<List<String>> toStringList(Object value) {
        if (value instanceof List<?> list) {
            return Option.some(list.stream().map(Object::toString).toList());
        }

        return Option.none();
    }

    private Map<String, String> toStringMap(Map<String, Object> map) {
        var result = new LinkedHashMap<String, String>();

        map.forEach((k, v) -> result.put(k, v.toString()));

        return Collections.unmodifiableMap(result);
    }
}
