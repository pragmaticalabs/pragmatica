// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.pg.schema.validator;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.pragmatica.aether.pg.parser.PostgresParser;
import org.pragmatica.aether.pg.parser.transform.CstNavigator;
import org.pragmatica.aether.pg.schema.builder.MigrationProcessor;
import org.pragmatica.aether.pg.schema.model.Schema;
import org.pragmatica.aether.pg.schema.validator.OutputNullability.Nullability;
import org.pragmatica.aether.pg.schema.validator.OutputNullability.Output;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// #2889: nullability is computed per output expression, never from column metadata alone.
/// Schema: `t(id, name, qty NOT NULL; note nullable)`, `u(id, t_id NOT NULL; label nullable)`.
class OutputNullabilityTest {
    static PostgresParser parser;
    static Schema schema;

    @BeforeAll
    static void setup() {
        parser = PostgresParser.create();
        schema = MigrationProcessor.create().processAll(List.of(
            "CREATE TABLE t (id bigint NOT NULL, name text NOT NULL, qty integer NOT NULL, note text, PRIMARY KEY (id))",
            "CREATE TABLE u (id bigint NOT NULL, t_id bigint NOT NULL, label text, PRIMARY KEY (id))")).unwrap();
    }

    private static List<Output> outputs(String sql) {
        var cst = parser.parseCst(sql).unwrap();

        return OutputNullability.analyze(CstNavigator.wrap(cst).unwrap(), schema).unwrap();
    }

    private static Nullability verdict(String sql, String output) {
        return outputs(sql).stream()
                           .filter(o -> o.name().map(output::equals).or(false))
                           .findFirst()
                           .orElseThrow()
                           .nullability();
    }

    /// Every nullable form: the verdict is MayBeNull and the reason contains the expected cause.
    @ParameterizedTest(name = "{0} -> {2}")
    @CsvSource(delimiter = '|', value = {
        "SELECT note AS x FROM t | x | column 't.note' is nullable",
        "SELECT t.id AS x FROM t LEFT JOIN u ON u.t_id = t.id | x | NOT_USED",
        "SELECT u.id AS x FROM t LEFT JOIN u ON u.t_id = t.id | x | nullable side of a LEFT JOIN",
        "SELECT t.id AS x FROM u RIGHT JOIN t ON u.t_id = t.id | x | NOT_USED",
        "SELECT u.id AS x FROM u RIGHT JOIN t ON u.t_id = t.id | x | nullable side of a RIGHT JOIN",
        "SELECT t.id AS x FROM t FULL JOIN u ON u.t_id = t.id | x | nullable side of a FULL JOIN",
        "SELECT u.id AS x FROM t FULL JOIN u ON u.t_id = t.id | x | nullable side of a FULL JOIN",
        "SELECT max(qty) AS x FROM t | x | max() yields NULL over an empty group",
        "SELECT min(qty) AS x FROM t | x | min() yields NULL",
        "SELECT sum(qty) AS x FROM t | x | sum() yields NULL",
        "SELECT CASE WHEN qty > 1 THEN 1 END AS x FROM t | x | CASE without ELSE",
        "SELECT CASE WHEN qty > 1 THEN 1 ELSE 2 END AS x FROM t | x | CASE result is not proven",
        "SELECT NULLIF(qty, 1) AS x FROM t | x | NULLIF",
        "SELECT (SELECT u.id FROM u WHERE u.t_id = 0) AS x FROM t | x | scalar subquery",
        "SELECT NULL AS x FROM t | x | NULL literal",
        "SELECT COALESCE(1, note) AS x FROM t | x | COALESCE whose last argument may be NULL",
        "SELECT qty + 1 AS x FROM t | x | arithmetic",
        "SELECT CAST(note AS text) AS x FROM t | x | column 't.note' is nullable",
        "SELECT qty AS x FROM t GROUP BY GROUPING SETS ((qty), ()) | x | subtotal rows",
        "SELECT lower(name) AS x FROM t | x | lower() is not proven",
        "SELECT x FROM (SELECT 1 AS x) d | x | derived table",
        "SELECT t.id AS x FROM t, missing m | x | NOT_USED",
        "SELECT qty AS x FROM t GROUP BY ROLLUP (qty) | x | subtotal rows",
        "SELECT note AS x FROM t UNION SELECT name FROM t | x | column 't.note' is nullable",
        "SELECT name AS x FROM t UNION SELECT note FROM t | x | set operation branch 2",
        "UPDATE t SET qty = 1 RETURNING note AS x | x | column 't.note' is nullable"
    })
    void nullableForm_isMayBeNull(String sql, String output, String cause) {
        if ("NOT_USED".equals(cause)) {
            assertThat(verdict(sql, output)).isEqualTo(Nullability.NON_NULL);
            return;
        }

        assertThat(verdict(sql, output)).isInstanceOfSatisfying(Nullability.MayBeNull.class,
                                                                n -> assertThat(n.reason()).contains(cause));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "SELECT id AS x FROM t",
        "SELECT t.name AS x FROM t",
        "SELECT t.id AS x FROM t JOIN u ON u.t_id = t.id",
        "SELECT u.id AS x FROM t JOIN u ON u.t_id = t.id",
        "SELECT u.id AS x FROM t INNER JOIN u ON u.t_id = t.id",
        "SELECT u.id AS x FROM t CROSS JOIN u",
        "SELECT t.id AS x FROM t LEFT JOIN u ON u.t_id = t.id",
        "SELECT count(*) AS x FROM t",
        "SELECT count(note) AS x FROM t",
        "SELECT 1 AS x FROM t",
        "SELECT 'a' AS x FROM t",
        "SELECT -1 AS x FROM t",
        "SELECT true AS x FROM t",
        "SELECT DATE '2020-01-01' AS x FROM t",
        "SELECT COALESCE(note, 'n') AS x FROM t",
        "SELECT COALESCE(max(qty), 0) AS x FROM t",
        "SELECT (qty) AS x FROM t",
        "SELECT qty::text AS x FROM t",
        "SELECT CAST(qty AS text) AS x FROM t",
        "SELECT t.id AS x FROM t, u",
        "SELECT x.id AS x FROM t x",
        "SELECT name AS x FROM t UNION ALL SELECT name FROM t",
        "UPDATE t SET qty = 1 RETURNING id AS x",
        "INSERT INTO t (id, name, qty) VALUES (1, 'a', 1) RETURNING qty AS x",
        "DELETE FROM t RETURNING name AS x"
    })
    void provenForm_isNonNull(String sql) {
        assertThat(verdict(sql, "x")).isEqualTo(Nullability.NON_NULL);
    }

    @Test
    void star_expandsToTableColumnsWithTheirNullability() {
        var result = outputs("SELECT * FROM t");

        assertThat(result.stream().map(o -> o.name().unwrap()).toList()).containsExactly("id", "name", "qty", "note");
        assertThat(result.stream().map(o -> o.nullability().isNonNull()).toList()).containsExactly(true, true, true, false);
    }

    @Test
    void qualifiedStar_overOuterJoin_nullExtendsTheJoinedSide() {
        var result = outputs("SELECT t.*, u.* FROM t LEFT JOIN u ON u.t_id = t.id");

        assertThat(result).hasSize(7);
        assertThat(result.subList(0, 3).stream().allMatch(o -> o.nullability().isNonNull())).isTrue();
        assertThat(result.subList(4, 7).stream().noneMatch(o -> o.nullability().isNonNull())).isTrue();
    }

    @Test
    void cteShadowingATable_isNotTrusted() {
        assertThat(verdict("WITH t AS (SELECT note AS id FROM t) SELECT id AS x FROM t", "x"))
            .isInstanceOf(Nullability.MayBeNull.class);
    }

    @Test
    void unlistableOutputs_areAbsent() {
        assertThat(OutputNullability.analyze(CstNavigator.wrap(parser.parseCst("VALUES (1)").unwrap()).unwrap(), schema).isEmpty())
            .isTrue();
        assertThat(OutputNullability.analyze(CstNavigator.wrap(parser.parseCst("SELECT * FROM (SELECT 1) d").unwrap()).unwrap(), schema).isEmpty())
            .isTrue();
        assertThat(OutputNullability.analyze(CstNavigator.wrap(parser.parseCst("SELECT id FROM t UNION SELECT id, name FROM t").unwrap()).unwrap(), schema).isEmpty())
            .isTrue();
    }
}
