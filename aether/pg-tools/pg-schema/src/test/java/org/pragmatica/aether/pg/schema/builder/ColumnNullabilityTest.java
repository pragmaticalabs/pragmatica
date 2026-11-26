// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.pg.schema.builder;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// #2795: `Column.nullable()` says what PostgreSQL says. A primary key makes its columns NOT NULL wherever it is declared,
/// a serial type is NOT NULL, a stored generated column is nullable unless it says NOT NULL.
class ColumnNullabilityTest {
    private static final String ADD_USING_INDEX = "ALTER TABLE t ADD CONSTRAINT t_pkey PRIMARY KEY USING INDEX t_ids";

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "CREATE TABLE t (a bigint, b bigint, note text, PRIMARY KEY (a, b))",
        "CREATE TABLE t (PRIMARY KEY (a, b), a bigint, b bigint, note text)",
        "CREATE TABLE t (a bigint, CONSTRAINT t_pkey PRIMARY KEY (a, b), b bigint, note text)",
        "CREATE TABLE t (a bigint, b bigint, note text, PRIMARY KEY (a, b) INCLUDE (note))",
        "CREATE TABLE t (a bigint, b bigint, note text); ALTER TABLE t ADD PRIMARY KEY (a, b)",
        "CREATE TABLE t (a bigint, b bigint, note text); ALTER TABLE ONLY t ADD CONSTRAINT t_pkey PRIMARY KEY (a, b)",
        "CREATE TABLE t (a bigint, note text); ALTER TABLE t ADD COLUMN b bigint, ADD PRIMARY KEY (b, a)",
        "CREATE TABLE t (A bigint, \"b\" bigint, note text, PRIMARY KEY (a, B))"})
    void primaryKey_makesItsColumnsNotNull_andNoOther(String script) {
        assertThat(nullable(script, "a")).isFalse();
        assertThat(nullable(script, "b")).isFalse();
        assertThat(nullable(script, "note")).isTrue();
    }

    @Test
    void partitionedTable_primaryKey_makesItsColumnsNotNull() {
        var script = "CREATE TABLE t (a bigint, b bigint, note text, PRIMARY KEY (a, b)) PARTITION BY RANGE (a)";

        assertThat(nullable(script, "a")).isFalse();
        assertThat(nullable(script, "b")).isFalse();
        assertThat(nullable(script, "note")).isTrue();
    }

    /// Tables not built by a column list are not modelled: PARTITION OF and LIKE must be refused by the parser, never
    /// produce a table whose copied key columns read nullable. If this fails, the grammar accepts the form: model it.
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "CREATE TABLE p PARTITION OF t FOR VALUES FROM (1) TO (10)",
        "CREATE TABLE c (LIKE t INCLUDING ALL)"})
    void tablesBuiltFromAnotherTable_areRefusedByTheParser(String ddl) {
        var result = MigrationProcessor.create()
                                       .processAll(List.of("CREATE TABLE t (a bigint, PRIMARY KEY (a)) PARTITION BY RANGE (a)",
                                                           ddl));

        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void inlinePrimaryKey_onAnAddedColumn_isNotNull() {
        var script = "CREATE TABLE t (note text); ALTER TABLE t ADD COLUMN a bigint PRIMARY KEY";

        assertThat(nullable(script, "a")).isFalse();
        assertThat(nullable(script, "note")).isTrue();
    }

    @Test
    void schemaQualifiedTable_addedPrimaryKey_makesItsColumnsNotNull() {
        var schema = MigrationProcessor.create()
                                       .processAll(List.of("CREATE TABLE app.t (a bigint, note text)",
                                                           "ALTER TABLE app.t ADD PRIMARY KEY (a)"))
                                       .unwrap();
        var table = schema.table("app.t").unwrap();

        assertThat(table.column("a").unwrap().nullable()).isFalse();
        assertThat(table.column("note").unwrap().nullable()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "CREATE TABLE t (a bigint, UNIQUE (a))",
        "CREATE TABLE t (a bigint UNIQUE)",
        "CREATE TABLE t (a bigint); ALTER TABLE t ADD CONSTRAINT t_key UNIQUE (a)",
        "CREATE TABLE t (a bigint, CHECK (a > 0))"})
    void otherConstraints_leaveTheColumnNullable(String script) {
        assertThat(nullable(script, "a")).isTrue();
    }

    /// PostgreSQL 17 drops the key and leaves its columns NOT NULL; an explicit DROP NOT NULL relaxes a column.
    @Test
    void droppedPrimaryKey_leavesItsColumnsNotNull_untilDropNotNull() {
        var dropped = "CREATE TABLE t (a bigint, b bigint, CONSTRAINT t_pkey PRIMARY KEY (a, b)); ALTER TABLE t DROP CONSTRAINT t_pkey";
        var relaxed = dropped + "; ALTER TABLE t ALTER COLUMN a DROP NOT NULL";

        assertThat(nullable(dropped, "a")).isFalse();
        assertThat(nullable(dropped, "b")).isFalse();
        assertThat(nullable(relaxed, "a")).isTrue();
        assertThat(nullable(relaxed, "b")).isFalse();
    }

    @Test
    void renamedPrimaryKeyColumn_staysNotNull() {
        assertThat(nullable("CREATE TABLE t (a bigint, PRIMARY KEY (a)); ALTER TABLE t RENAME COLUMN a TO b", "b")).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"smallserial", "serial2", "serial", "serial4", "bigserial", "serial8", "BIGSERIAL"})
    void serialColumn_isNotNull(String type) {
        assertThat(nullable("CREATE TABLE t (a " + type + ", note text)", "a")).isFalse();
        assertThat(nullable("CREATE TABLE t (note text); ALTER TABLE t ADD COLUMN a " + type, "a")).isFalse();
        assertThat(nullable("CREATE TABLE t (a " + type + "); ALTER TABLE t ALTER COLUMN a DROP NOT NULL", "a")).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"smallint", "integer", "bigint", "text", "numeric(10,2)", "uuid", "bigint[]", "my_type"})
    void otherTypes_areNullableByDefault(String type) {
        assertThat(nullable("CREATE TABLE t (a " + type + ")", "a")).isTrue();
    }

    @Test
    void storedGeneratedColumn_isNullable_unlessItSaysNotNull() {
        assertThat(nullable("CREATE TABLE t (b bigint, a bigint GENERATED ALWAYS AS (b * 2) STORED)", "a")).isTrue();
        assertThat(nullable("CREATE TABLE t (b bigint, a bigint GENERATED ALWAYS AS (b * 2) STORED NOT NULL)", "a")).isFalse();
        assertThat(nullable("CREATE TABLE t (b bigint, a bigint NOT NULL GENERATED ALWAYS AS (b * 2) STORED)", "a")).isFalse();
        assertThat(nullable("CREATE TABLE t (b bigint, a bigint GENERATED ALWAYS AS (b * 2) STORED, PRIMARY KEY (a))", "a")).isFalse();
    }

    @Test
    void identityColumn_isNotNull() {
        assertThat(nullable("CREATE TABLE t (a bigint GENERATED ALWAYS AS IDENTITY, note text)", "a")).isFalse();
        assertThat(nullable("CREATE TABLE t (a bigint GENERATED BY DEFAULT AS IDENTITY, note text)", "a")).isFalse();
    }

    /// Tripwire: the grammar does not parse USING INDEX, so a migration that uses it is refused instead of leaving the
    /// columns nullable. If this fails, the grammar accepts it now: enable the test below.
    @Test
    void primaryKeyUsingIndex_isRefusedByTheParser() {
        var result = MigrationProcessor.create().processAll(List.of("CREATE TABLE t (a bigint)",
                                                                   "CREATE UNIQUE INDEX t_ids ON t (a)",
                                                                   ADD_USING_INDEX));

        assertThat(result.isFailure()).isTrue();
    }

    @Disabled("the grammar does not parse PRIMARY KEY USING INDEX; the tripwire above fails when it does")
    @Test
    void primaryKeyUsingIndex_makesTheIndexedColumnsNotNull() {
        var script = "CREATE TABLE t (a bigint, note text); CREATE UNIQUE INDEX t_ids ON t (a); " + ADD_USING_INDEX;

        assertThat(nullable(script, "a")).isFalse();
        assertThat(nullable(script, "note")).isTrue();
    }

    private static boolean nullable(String script, String column) {
        return MigrationProcessor.create()
                                 .processAll(List.of(script))
                                 .unwrap()
                                 .table("t")
                                 .unwrap()
                                 .column(column)
                                 .unwrap()
                                 .nullable();
    }
}
