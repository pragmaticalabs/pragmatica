// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.pg.codegen.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/// #2795: a column PostgreSQL makes NOT NULL without the words NOT NULL (a primary-key column declared at table level or
/// added later, a serial column) reads into a plain record field; the nullable column of the same table is still
/// refused. Both the CRUD check and the `@Query` check (#2889) read the same schema model.
///
/// Schema: `src/test/resources/schema/V006__not_null_forms.sql`. The same rule without the processor:
/// `ColumnNullabilityTest` in pg-schema.
class NotNullFormsCrudValidationTest {
    private static final String KEY_FIELDS = "long leftId, long rightId";
    private static final String SERIAL_FIELDS = "Short smallN, Short smallTwo, int plainN, int plainFour, long bigN, long bigEight, long lateN";

    @TempDir
    Path tempDir;

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"PkTableRow", "PkFirstRow", "PkAddedRow", "PkNamedRow", "PkDroppedRow"})
    void primaryKeyColumns_onPlainFields_areAccepted(String row) throws Exception {
        var result = compile(row, KEY_FIELDS, "Promise<List<%s>> findByLeftId(Long leftId)".formatted(row));

        assertThat(result.success()).as(result.diagnostics()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"PkTableRow", "PkFirstRow", "PkAddedRow", "PkNamedRow", "PkDroppedRow"})
    void nullableColumn_besideThePrimaryKey_isStillRefused(String row) throws Exception {
        var result = compile(row, KEY_FIELDS + ", String note", "Promise<List<%s>> findByLeftId(Long leftId)".formatted(row));

        assertThat(result.success()).isFalse();
        assertThat(result.diagnostics())
            .contains(".note' is nullable")
            .doesNotContain(".left_id' is nullable")
            .doesNotContain(".right_id' is nullable");
    }

    /// Dropping the key leaves its columns NOT NULL (PostgreSQL 17); only the explicit DROP NOT NULL relaxes one.
    @Test
    void droppedKeyColumn_afterDropNotNull_isRefused() throws Exception {
        var result = compile("PkRelaxedRow", KEY_FIELDS, "Promise<List<PkRelaxedRow>> findByRightId(Long rightId)");

        assertThat(result.success()).isFalse();
        assertThat(result.diagnostics())
            .contains("column 'pk_relaxed.left_id' is nullable")
            .doesNotContain("column 'pk_relaxed.right_id' is nullable");
    }

    @Test
    void serialColumns_onPlainFields_areAccepted() throws Exception {
        var result = compile("SerialFormsRow", SERIAL_FIELDS, "Promise<Option<SerialFormsRow>> findByBigN(Long bigN)");

        assertThat(result.success()).as(result.diagnostics()).isTrue();
    }

    @Test
    void nullableColumn_besideSerialColumns_isStillRefused() throws Exception {
        var result = compile("SerialFormsRow", SERIAL_FIELDS + ", String note", "Promise<Option<SerialFormsRow>> findByBigN(Long bigN)");

        assertThat(result.success()).isFalse();
        assertThat(result.diagnostics())
            .contains("column 'serial_forms.note' is nullable")
            .doesNotContain("_n' is nullable")
            .doesNotContain("small_two' is nullable")
            .doesNotContain("plain_four' is nullable")
            .doesNotContain("big_eight' is nullable");
    }

    /// PostgreSQL allows NULL in a stored generated column (here: whenever `amount` is NULL) unless it says NOT NULL.
    @Test
    void storedGeneratedColumn_onPlainField_isRefused() throws Exception {
        var result = compile("StoredFormsRow",
                             "long id, BigDecimal doubled, BigDecimal tripled",
                             "Promise<Option<StoredFormsRow>> findById(Long id)");

        assertThat(result.success()).isFalse();
        assertThat(result.diagnostics())
            .contains("column 'stored_forms.doubled' is nullable")
            .doesNotContain("column 'stored_forms.tripled' is nullable");
    }

    @Test
    void storedGeneratedColumn_onOptionField_isAccepted() throws Exception {
        var result = compile("StoredFormsRow",
                             "long id, Option<BigDecimal> doubled, BigDecimal tripled",
                             "Promise<Option<StoredFormsRow>> findById(Long id)");

        assertThat(result.success()).as(result.diagnostics()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "SELECT left_id, right_id FROM pk_table",
        "SELECT left_id, right_id FROM pk_added",
        "SELECT big_n AS left_id, plain_n::bigint AS right_id FROM serial_forms"})
    void query_notNullFormColumns_onPlainFields_areAccepted(String sql) throws Exception {
        var result = compile("KeyRow", KEY_FIELDS, query(sql, "Promise<List<KeyRow>> run()"));

        assertThat(result.success()).as(result.diagnostics()).isTrue();
    }

    @Test
    void query_nullableColumn_besideThePrimaryKey_isStillRefused() throws Exception {
        var result = compile("KeyRow",
                             KEY_FIELDS + ", String note",
                             query("SELECT left_id, right_id, note FROM pk_table", "Promise<List<KeyRow>> run()"));

        assertThat(result.success()).isFalse();
        assertThat(result.diagnostics())
            .contains("column 'pk_table.note' is nullable")
            .doesNotContain("left_id' is nullable")
            .doesNotContain("right_id' is nullable");
    }

    /// The other direction of the `@Query` check (#2962): a proven non-null output refuses `Option<T>`.
    @Test
    void query_primaryKeyColumn_onOptionField_failsTheBuild() throws Exception {
        var result = compile("KeyRow",
                             "Option<Long> leftId, long rightId",
                             query("SELECT left_id, right_id FROM pk_table", "Promise<List<KeyRow>> run()"));

        assertThat(result.success()).isFalse();
        assertThat(result.diagnostics()).contains("column 'pk_table.left_id' is a NOT NULL column");
    }

    private static String query(String sql, String method) {
        return "@Query(\"" + sql + "\")\n    " + method;
    }

    private TestCompilationHelper.CompilationResult compile(String rowName, String fields, String method) throws Exception {
        var source = """
            package test;

            import org.pragmatica.aether.pg.codegen.annotation.Query;
            import org.pragmatica.aether.resource.db.PgSql;
            import org.pragmatica.lang.Option;
            import org.pragmatica.lang.Promise;
            import java.math.BigDecimal;
            import java.util.List;

            @PgSql
            public interface NotNullFormsRepo {
                %s

                %s;
            }
            """.formatted(TestCompilationHelper.rowRecord(rowName, fields), method);

        return TestCompilationHelper.compileWithProcessor(source, "test/NotNullFormsRepo.java", tempDir);
    }
}
