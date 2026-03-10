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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class TomlWriterTest {

    @Nested
    class RoundTrip {

        @Test
        void roundTrip_simpleDocument_preservesValues() {
            var toml = """
                title = "My Config"
                count = 42
                enabled = true
                ratio = 3.14
                """;
            TomlParser.parse(toml)
                .onFailure(e -> fail("Parse failed: " + e.message()))
                .onSuccess(doc -> {
                    var written = TomlWriter.toToml(doc);
                    TomlParser.parse(written)
                        .onFailure(e -> fail("Re-parse failed: " + e.message()))
                        .onSuccess(reparsed -> {
                            assertThat(reparsed.getString("", "title").or("")).isEqualTo("My Config");
                            assertThat(reparsed.getLong("", "count").or(0L)).isEqualTo(42L);
                            assertThat(reparsed.getBoolean("", "enabled").or(false)).isTrue();
                            assertThat(reparsed.getDouble("", "ratio").or(0.0)).isEqualTo(3.14);
                        });
                });
        }

        @Test
        void roundTrip_multipleSections_preservesStructure() {
            var toml = """
                [database]
                host = "localhost"
                port = 5432

                [server]
                name = "main"
                debug = false
                """;
            TomlParser.parse(toml)
                .onFailure(e -> fail("Parse failed: " + e.message()))
                .onSuccess(doc -> {
                    var written = TomlWriter.toToml(doc);
                    TomlParser.parse(written)
                        .onFailure(e -> fail("Re-parse failed: " + e.message()))
                        .onSuccess(reparsed -> {
                            assertThat(reparsed.getString("database", "host").or("")).isEqualTo("localhost");
                            assertThat(reparsed.getLong("database", "port").or(0L)).isEqualTo(5432L);
                            assertThat(reparsed.getString("server", "name").or("")).isEqualTo("main");
                            assertThat(reparsed.getBoolean("server", "debug").or(true)).isFalse();
                        });
                });
        }

        @Test
        void roundTrip_withInlineTables_preservesValues() {
            var sections = new LinkedHashMap<String, Map<String, Object>>();
            var root = new LinkedHashMap<String, Object>();
            var inlineMap = new LinkedHashMap<String, Object>();
            inlineMap.put("x", 1L);
            inlineMap.put("y", "hello");
            inlineMap.put("z", true);
            root.put("point", inlineMap);
            sections.put("", root);
            var doc = new TomlDocument(sections);

            var written = TomlWriter.toToml(doc);
            TomlParser.parse(written)
                .onFailure(e -> fail("Re-parse failed: " + e.message()))
                .onSuccess(reparsed -> {
                    var table = reparsed.getInlineTable("", "point");
                    assertThat(table.isPresent()).isTrue();
                    var map = table.unwrap();
                    assertThat(map.get("x")).isEqualTo(1L);
                    assertThat(map.get("y")).isEqualTo("hello");
                    assertThat(map.get("z")).isEqualTo(true);
                });
        }
    }

    @Nested
    class ValueFormatting {

        @Test
        void stringEscaping_specialCharacters_escaped() {
            var doc = TomlDocument.EMPTY
                .with("", "path", "line1\nline2")
                .with("", "quoted", "say \"hi\"")
                .with("", "backslash", "a\\b")
                .with("", "tab", "a\tb");

            var written = TomlWriter.toToml(doc);
            assertThat(written).contains("\"line1\\nline2\"");
            assertThat(written).contains("\"say \\\"hi\\\"\"");
            assertThat(written).contains("\"a\\\\b\"");
            assertThat(written).contains("\"a\\tb\"");

            TomlParser.parse(written)
                .onFailure(e -> fail("Re-parse failed: " + e.message()))
                .onSuccess(reparsed -> {
                    assertThat(reparsed.getString("", "path").or("")).isEqualTo("line1\nline2");
                    assertThat(reparsed.getString("", "quoted").or("")).isEqualTo("say \"hi\"");
                    assertThat(reparsed.getString("", "backslash").or("")).isEqualTo("a\\b");
                    assertThat(reparsed.getString("", "tab").or("")).isEqualTo("a\tb");
                });
        }

        @Test
        void keyQuoting_specialChars_quoted() {
            var sections = new LinkedHashMap<String, Map<String, Object>>();
            var root = new LinkedHashMap<String, Object>();
            root.put("simple-key", "ok");
            root.put("key/with/slashes", "quoted");
            root.put("key:colon", "also quoted");
            sections.put("", root);
            var doc = new TomlDocument(sections);

            var written = TomlWriter.toToml(doc);
            assertThat(written).contains("simple-key = ");
            assertThat(written).contains("\"key/with/slashes\" = ");
            assertThat(written).contains("\"key:colon\" = ");
        }

        @Test
        void numericValues_writtenBare() {
            var doc = TomlDocument.EMPTY
                .with("", "int_val", 42L)
                .with("", "double_val", 3.14);

            var written = TomlWriter.toToml(doc);
            assertThat(written).contains("int_val = 42");
            assertThat(written).contains("double_val = 3.14");
            assertThat(written).doesNotContain("\"42\"");
            assertThat(written).doesNotContain("\"3.14\"");
        }

        @Test
        void booleanValues_writtenBare() {
            var doc = TomlDocument.EMPTY
                .with("", "yes", true)
                .with("", "no", false);

            var written = TomlWriter.toToml(doc);
            assertThat(written).contains("yes = true");
            assertThat(written).contains("no = false");
        }

        @Test
        void arrayValues_writtenAsTomlArrays() {
            var doc = TomlDocument.EMPTY
                .with("", "tags", List.of("a", "b", "c"))
                .with("", "nums", List.of(1L, 2L, 3L));

            var written = TomlWriter.toToml(doc);
            assertThat(written).contains("tags = [\"a\", \"b\", \"c\"]");
            assertThat(written).contains("nums = [1, 2, 3]");
        }
    }

    @Nested
    class SpecialCases {

        @Test
        void headerComments_included_atTop() {
            var doc = TomlDocument.EMPTY.with("", "key", "value");
            var written = TomlWriter.toToml(doc, List.of("Generated config", "Do not edit"));

            assertThat(written).startsWith("# Generated config\n# Do not edit\n");
        }

        @Test
        void emptyDocument_writesEmpty() {
            var written = TomlWriter.toToml(TomlDocument.EMPTY);
            assertThat(written.trim()).isEmpty();
        }

        @Test
        void arrayOfTables_writtenCorrectly() {
            var toml = """
                [[products]]
                name = "Hammer"
                price = 9

                [[products]]
                name = "Nail"
                price = 1
                """;
            TomlParser.parse(toml)
                .onFailure(e -> fail("Parse failed: " + e.message()))
                .onSuccess(doc -> {
                    var written = TomlWriter.toToml(doc);
                    assertThat(written).contains("[[products]]");
                    TomlParser.parse(written)
                        .onFailure(e -> fail("Re-parse failed: " + e.message()))
                        .onSuccess(reparsed -> {
                            var tables = reparsed.getTableArray("products");
                            assertThat(tables.isPresent()).isTrue();
                            var list = tables.unwrap();
                            assertThat(list).hasSize(2);
                            assertThat(list.get(0).get("name")).isEqualTo("Hammer");
                            assertThat(list.get(1).get("name")).isEqualTo("Nail");
                        });
                });
        }
    }

    @Nested
    class InlineTableParsing {

        @Test
        void parse_inlineTable_returnsMap() {
            var toml = """
                point = { x = 1, y = 2 }
                """;
            TomlParser.parse(toml)
                .onFailure(e -> fail("Parse failed: " + e.message()))
                .onSuccess(doc -> {
                    var table = doc.getInlineTable("", "point");
                    assertThat(table.isPresent()).isTrue();
                    var map = table.unwrap();
                    assertThat(map.get("x")).isEqualTo(1L);
                    assertThat(map.get("y")).isEqualTo(2L);
                });
        }

        @Test
        void parse_nestedInlineValues_preservesTypes() {
            var toml = """
                config = { name = "test", count = 5, active = true, rate = 1.5 }
                """;
            TomlParser.parse(toml)
                .onFailure(e -> fail("Parse failed: " + e.message()))
                .onSuccess(doc -> {
                    var table = doc.getInlineTable("", "config");
                    assertThat(table.isPresent()).isTrue();
                    var map = table.unwrap();
                    assertThat(map.get("name")).isEqualTo("test");
                    assertThat(map.get("count")).isEqualTo(5L);
                    assertThat(map.get("active")).isEqualTo(true);
                    assertThat(map.get("rate")).isEqualTo(1.5);
                });
        }

        @Test
        void roundTrip_inlineTable_preservesValues() {
            var toml = """
                [section]
                metadata = { author = "Alice", version = 3 }
                """;
            TomlParser.parse(toml)
                .onFailure(e -> fail("Parse failed: " + e.message()))
                .onSuccess(doc -> {
                    var written = TomlWriter.toToml(doc);
                    TomlParser.parse(written)
                        .onFailure(e -> fail("Re-parse failed: " + e.message()))
                        .onSuccess(reparsed -> {
                            var table = reparsed.getInlineTable("section", "metadata");
                            assertThat(table.isPresent()).isTrue();
                            var map = table.unwrap();
                            assertThat(map.get("author")).isEqualTo("Alice");
                            assertThat(map.get("version")).isEqualTo(3L);
                        });
                });
        }

        @Test
        void parse_emptyInlineTable_returnsEmptyMap() {
            var toml = """
                empty = {}
                """;
            TomlParser.parse(toml)
                .onFailure(e -> fail("Parse failed: " + e.message()))
                .onSuccess(doc -> {
                    var table = doc.getInlineTable("", "empty");
                    assertThat(table.isPresent()).isTrue();
                    assertThat(table.unwrap()).isEmpty();
                });
        }
    }
}
