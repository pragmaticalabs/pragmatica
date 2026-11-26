// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.pg.codegen.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// #2795: a CRUD method (generated SQL, no `@Query`) that reads a NULLABLE table column into a non-`Option` record
/// field fails the build, naming the method, the column and the field; the same column read into `Option<T>`
/// compiles. Without the check the row mapper fails at runtime on the first NULL row.
///
/// Schema (`src/test/resources/schema/V005__nullables.sql`): `nullables` has one nullable column per column type,
/// plus `id` (primary key) and `kept` (NOT NULL).
class NullableColumnCrudValidationTest {
    @TempDir
    Path tempDir;

    /// field type, boxed element type for the `Option` form, field name, column name, column type
    static Stream<Arguments> nullableColumns() {
        return Stream.of(
            Arguments.of("String", "String", "note", "note", "text"),
            Arguments.of("Integer", "Integer", "qty", "qty", "integer"),
            Arguments.of("int", "Integer", "qty", "qty", "integer"),
            Arguments.of("Long", "Long", "bigN", "big_n", "bigint"),
            Arguments.of("long", "Long", "bigN", "big_n", "bigint"),
            Arguments.of("Short", "Short", "smallN", "small_n", "smallint"),
            Arguments.of("Double", "Double", "ratio", "ratio", "double precision"),
            Arguments.of("double", "Double", "ratio", "ratio", "double precision"),
            Arguments.of("Float", "Float", "score", "score", "real"),
            Arguments.of("Boolean", "Boolean", "flag", "flag", "boolean"),
            Arguments.of("boolean", "Boolean", "flag", "flag", "boolean"),
            Arguments.of("BigDecimal", "BigDecimal", "amount", "amount", "numeric"),
            Arguments.of("LocalDate", "LocalDate", "bornOn", "born_on", "date"),
            Arguments.of("LocalDateTime", "LocalDateTime", "seenAt", "seen_at", "timestamp"),
            Arguments.of("Instant", "Instant", "seenTz", "seen_tz", "timestamptz"),
            Arguments.of("LocalTime", "LocalTime", "atTime", "at_time", "time"),
            Arguments.of("UUID", "UUID", "ident", "ident", "uuid"),
            Arguments.of("byte[]", "byte[]", "payload", "payload", "bytea"),
            Arguments.of("String[]", "String[]", "tags", "tags", "text[]"),
            Arguments.of("Integer[]", "Integer[]", "nums", "nums", "integer[]"),
            Arguments.of("Doc", "Doc", "doc", "doc", "jsonb"));
    }

    @ParameterizedTest(name = "{4}")
    @MethodSource("nullableColumns")
    void nullableColumn_onNonOptionField_failsTheBuild(String type, String boxed, String field, String column, String sqlType) throws Exception {
        var result = compile("NullableFieldRepo",
                             "NullablesRow",
                             "long id, " + type + " " + field,
                             "Promise<Option<NullablesRow>> findById(Long id)");

        assertThat(result.success()).isFalse();
        assertThat(result.diagnostics())
            .contains("ERROR")
            .contains("Method 'findById'")
            .contains("column 'nullables." + column + "' is nullable")
            .contains("field '" + field + "'")
            .contains("Option<T>");
    }

    @ParameterizedTest(name = "{4}")
    @MethodSource("nullableColumns")
    void nullableColumn_onOptionField_isAccepted(String type, String boxed, String field, String column, String sqlType) throws Exception {
        var result = compile("NullableOptionRepo",
                             "NullablesRow",
                             "long id, Option<" + boxed + "> " + field,
                             "Promise<Option<NullablesRow>> findById(Long id)");

        assertThat(result.success()).as(result.diagnostics()).isTrue();
    }

    /// The check holds for every row wrapper and for the methods that read the row back without a SELECT.
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "Promise<NullablesRow> save(NullablesRow row)",
        "Promise<NullablesRow> insert(NullablesRow row)",
        "Promise<List<NullablesRow>> findByNote(String note)",
        "Promise<Option<NullablesRow>> findByQtyOrderByIdAsc(Integer qty)",
        "Promise<List<NullablesRow>> findByNoteIsNull()"})
    void nullableColumn_onNonOptionField_failsTheBuild_underEveryCrudShape(String method) throws Exception {
        var result = compile("ShapeRepo", "NullablesRow", "long id, Instant seenTz", method);

        assertThat(result.success()).isFalse();
        assertThat(result.diagnostics())
            .contains("Method '" + method.substring(method.indexOf(' ', method.indexOf('>') + 1) + 1, method.indexOf('(')) + "'")
            .contains("column 'nullables.seen_tz' is nullable")
            .contains("field 'seenTz'");
    }

    /// Control: a NOT NULL column and the primary key read into plain fields compile.
    @Test
    void notNullColumns_onPlainFields_areAccepted() throws Exception {
        var result = compile("NotNullRepo", "NullablesRow", "long id, String kept", "Promise<Option<NullablesRow>> findById(Long id)");

        assertThat(result.success()).as(result.diagnostics()).isTrue();
    }

    /// The check is per field: a nullable column the record does not read is not blamed on it.
    @Test
    void nullableColumn_notReadByTheRecord_isAccepted() throws Exception {
        var result = compile("UnreadRepo", "NullablesRow", "long id, String kept", "Promise<List<NullablesRow>> findByNote(String note)");

        assertThat(result.success()).as(result.diagnostics()).isTrue();
    }

    /// Every offending field is reported, not only the first.
    @Test
    void severalNullableColumns_areAllReported() throws Exception {
        var result = compile("SeveralRepo",
                             "NullablesRow",
                             "long id, String note, Instant seenTz, Option<Integer> qty",
                             "Promise<Option<NullablesRow>> findById(Long id)");

        assertThat(result.success()).isFalse();
        assertThat(result.diagnostics())
            .contains("column 'nullables.note' is nullable")
            .contains("column 'nullables.seen_tz' is nullable")
            .doesNotContain("column 'nullables.qty' is nullable");
    }

    /// A field that names no column is reported by the existing column check, not by this one.
    @Test
    void fieldWithoutColumn_isNotBlamedOnNullability() throws Exception {
        var result = compile("NoColumnRepo", "NullablesRow", "long id, String missing", "Promise<Option<NullablesRow>> findById(Long id)");

        assertThat(result.diagnostics()).doesNotContain("is nullable");
    }

    private TestCompilationHelper.CompilationResult compile(String repoName, String rowName, String fields, String method) throws Exception {
        var source = """
            package test;

            import org.pragmatica.aether.resource.db.PgSql;
            import org.pragmatica.lang.Option;
            import org.pragmatica.lang.Promise;
            import java.math.BigDecimal;
            import java.time.Instant;
            import java.time.LocalDate;
            import java.time.LocalDateTime;
            import java.time.LocalTime;
            import java.util.List;
            import java.util.UUID;

            @PgSql
            public interface %s {
                record Doc(Option<String> a) {}

                %s

                %s;
            }
            """.formatted(repoName, TestCompilationHelper.rowRecord(rowName, fields), method);

        return TestCompilationHelper.compileWithProcessor(source, "test/" + repoName + ".java", tempDir);
    }
}
