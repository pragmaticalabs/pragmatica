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
        "SELECT x AS y FROM (SELECT 1 AS x) d(c) | y | derived table",
        "SELECT d.x AS y FROM (SELECT note AS x FROM t) d | y | column 't.note' is nullable",
        "SELECT x AS y FROM (SELECT max(qty) AS x FROM t) d | y | max() yields NULL",
        "WITH c AS (SELECT note AS x FROM t) SELECT x AS y FROM c | y | column 't.note' is nullable",
        "SELECT name AS x FROM t UNION VALUES (NULL) | x | NULL literal",
        "SELECT name AS x FROM t UNION ALL SELECT NULL | x | NULL literal",
        "SELECT x FROM (SELECT t.id AS x FROM u LEFT JOIN t ON u.t_id = t.id) d | x | nullable side of a LEFT JOIN",
        "SELECT t.id AS x FROM t, missing m | x | NOT_USED",
        "SELECT qty AS x FROM t GROUP BY ROLLUP (qty) | x | subtotal rows",
        "SELECT qty AS x FROM t GROUP BY CUBE (qty) | x | subtotal rows",
        "SELECT public.u.id AS x FROM t LEFT JOIN u ON u.t_id = t.id | x | nullable side of a LEFT JOIN",
        "SELECT note AS x FROM t UNION SELECT name FROM t | x | column 't.note' is nullable",
        "SELECT name AS x FROM t UNION SELECT note FROM t | x | set operation branch 2",
        "UPDATE t SET qty = 1 RETURNING note AS x | x | column 't.note' is nullable"
    })
    void nullableForm_isMayBeNull(String sql, String output, String cause) {
        if ("NOT_USED".equals(cause)) {
            assertThat(verdict(sql, output)).isInstanceOf(Nullability.NonNull.class);
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
        assertThat(verdict(sql, "x")).isInstanceOf(Nullability.NonNull.class);
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
    void schemaQualifiedStar_matchesTheLastQualifierPart() {
        var result = outputs("SELECT public.u.* FROM t LEFT JOIN u ON u.t_id = t.id");

        assertThat(result).hasSize(3);
        assertThat(result.stream().noneMatch(o -> o.nullability().isNonNull())).isTrue();
    }

    @Test
    void cteShadowingATable_isNotTrusted() {
        assertThat(verdict("WITH t AS (SELECT note AS id FROM t) SELECT id AS x FROM t", "x"))
            .isInstanceOf(Nullability.MayBeNull.class);
    }

    /// Unaliased compound outputs get PostgreSQL's default label, so a field of that name is checked (#2889 round 2).
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "SELECT max(qty) FROM t | max",
        "SELECT lower(name) FROM t | lower",
        "SELECT sum(qty) OVER () FROM t | sum",
        "SELECT note::text FROM t | note",
        "SELECT CAST(note AS text) FROM t | note",
        "SELECT (note) FROM t | note",
        "SELECT max(qty)::text FROM t | max",
        "SELECT CASE WHEN qty > 1 THEN 1 END FROM t | case",
        "SELECT COALESCE(note, name) FROM t | coalesce",
        "SELECT NULLIF(qty, 1) FROM t | nullif",
        "SELECT t.note FROM t | note"
    })
    void unaliasedOutput_getsPostgresDefaultLabel(String sql, String label) {
        assertThat(outputs(sql).getFirst().name()).isEqualTo(org.pragmatica.lang.Option.present(label));
    }

    @Test
    void unaliasedCompoundOutput_isCheckedLikeTheAliasedOne() {
        assertThat(verdict("SELECT max(qty) FROM t", "max")).isInstanceOf(Nullability.MayBeNull.class);
        assertThat(verdict("SELECT note::text FROM t", "note")).isInstanceOf(Nullability.MayBeNull.class);
        assertThat(verdict("SELECT max(qty) AS max FROM t", "max")).isInstanceOf(Nullability.MayBeNull.class);
    }

    @Test
    void castOfNullableColumn_staysNullable_castOfNonNull_staysNonNull() {
        assertThat(verdict("SELECT note::text AS x FROM t", "x")).isInstanceOf(Nullability.MayBeNull.class);
        assertThat(verdict("SELECT CAST(note AS text) AS x FROM t", "x")).isInstanceOf(Nullability.MayBeNull.class);
        assertThat(verdict("SELECT qty::text AS x FROM t", "x")).isInstanceOf(Nullability.NonNull.class);
    }

    @Test
    void operatorOutput_staysUnnamed() {
        assertThat(outputs("SELECT qty + 1 FROM t").getFirst().name().isEmpty()).isTrue();
    }

    @Test
    void starOverDerivedTable_propagatesEachOutputsNullability() {
        var result = outputs("SELECT * FROM (SELECT id, note FROM t) d");

        assertThat(result.stream().map(o -> o.name().unwrap()).toList()).containsExactly("id", "note");
        assertThat(result.stream().map(o -> o.nullability().isNonNull()).toList()).containsExactly(true, false);
    }

    @Test
    void starOverCte_propagatesEachOutputsNullability() {
        var result = outputs("WITH c AS (SELECT id, note FROM t) SELECT * FROM c");

        assertThat(result.stream().map(o -> o.nullability().isNonNull()).toList()).containsExactly(true, false);
    }

    @Test
    void starOverDerivedTableOnTheNullableSide_isNullExtended() {
        var result = outputs("SELECT d.* FROM t LEFT JOIN (SELECT id FROM u) d ON d.id = t.id");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().nullability().isNonNull()).isFalse();
    }

    @Test
    void starOverUnresolvableRelation_isOneUnnamedUnprovenOutput() {
        var derivedWithColumnList = outputs("SELECT * FROM (SELECT id FROM t) d(a)");
        var function = outputs("SELECT * FROM generate_series(1, 2) g");

        assertThat(derivedWithColumnList).hasSize(1);
        assertThat(derivedWithColumnList.getFirst().name().isEmpty()).isTrue();
        assertThat(derivedWithColumnList.getFirst().nullability().isNonNull()).isFalse();
        assertThat(function).hasSize(1);
        assertThat(function.getFirst().nullability().isNonNull()).isFalse();
    }

    @Test
    void valuesBranch_isAnalysedPerPosition() {
        assertThat(outputs("VALUES (1, NULL), (2, 3)").stream().map(o -> o.nullability().isNonNull()).toList())
            .containsExactly(true, false);
        assertThat(outputs("VALUES (1), (NULL)").getFirst().nullability().isNonNull()).isFalse();
        assertThat(outputs("VALUES (1)").getFirst().name()).isEqualTo(org.pragmatica.lang.Option.present("column1"));
    }

    @Test
    void setOperationBranchesOfDifferentWidth_areAllUnproven() {
        var result = outputs("SELECT id, name FROM t UNION SELECT id FROM t");

        assertThat(result.stream().map(o -> o.name().unwrap()).toList()).containsExactly("id", "name");
        assertThat(result.stream().noneMatch(o -> o.nullability().isNonNull())).isTrue();
    }

    @Test
    void recursiveCte_isNotTrusted() {
        assertThat(verdict("WITH RECURSIVE t(id) AS (SELECT 1 UNION ALL SELECT id + 1 FROM t) SELECT id AS x FROM t", "x"))
            .isInstanceOf(Nullability.MayBeNull.class);
    }

    @Test
    void cteWithColumnList_isUnresolved() {
        var result = outputs("WITH c(a) AS (SELECT id FROM t) SELECT * FROM c");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().name().isEmpty()).isTrue();
        assertThat(result.getFirst().nullability().isNonNull()).isFalse();
    }

    @Test
    void selfReferencingCte_shadowingATable_isNotTrusted() {
        assertThat(verdict("WITH RECURSIVE t AS (SELECT id FROM t) SELECT id AS x FROM t", "x"))
            .isInstanceOf(Nullability.MayBeNull.class);
    }

    @Test
    void cteSeesEarlierCtes_andNestedQueriesSeeOuterCtes() {
        assertThat(verdict("WITH a AS (SELECT id AS x FROM t), b AS (SELECT x FROM a) SELECT x AS y FROM b", "y"))
            .isInstanceOf(Nullability.NonNull.class);
        assertThat(verdict("WITH c AS (SELECT id AS x FROM t) SELECT y FROM (SELECT x AS y FROM c) d", "y"))
            .isInstanceOf(Nullability.NonNull.class);
    }

    @Test
    void valuesWithDefaultOrRaggedRows_areUnproven() {
        assertThat(outputs("VALUES (1), (DEFAULT)").getFirst().nullability().isNonNull()).isFalse();
        assertThat(outputs("VALUES (1, 2), (3)").get(1).nullability().isNonNull()).isFalse();
        assertThat(outputs("VALUES (1, 2), (3)").getFirst().nullability().isNonNull()).isTrue();
    }

    @Test
    void starWithUnmatchedQualifier_isOneUnnamedUnprovenOutput() {
        var result = outputs("SELECT x.* FROM t");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().name().isEmpty()).isTrue();
        assertThat(result.getFirst().nullability().isNonNull()).isFalse();
    }

    @Test
    void derivedColumn_missingOrDuplicated_orOnTheNullableSide_isUnproven() {
        assertThat(verdict("SELECT d.zzz AS y FROM (SELECT id FROM t) d", "y")).isInstanceOf(Nullability.MayBeNull.class);
        assertThat(verdict("SELECT d.x AS y FROM (SELECT id AS x, note AS x FROM t) d", "y")).isInstanceOf(Nullability.MayBeNull.class);
        assertThat(verdict("SELECT d.id AS y FROM t LEFT JOIN (SELECT id FROM u) d ON d.id = t.id", "y"))
            .isInstanceOf(Nullability.MayBeNull.class);
        assertThat(verdict("SELECT d.id AS y FROM t JOIN (SELECT id FROM u) d ON d.id = t.id", "y")).isInstanceOf(Nullability.NonNull.class);
    }

    @Test
    void nullableSide_doesNotReplaceTheColumnsOwnReason() {
        assertThat(verdict("SELECT d.x AS y FROM t LEFT JOIN (SELECT max(t_id) AS x FROM u GROUP BY id) d ON true", "y"))
            .isInstanceOfSatisfying(Nullability.MayBeNull.class, n -> assertThat(n.reason()).contains("max() yields NULL"));
    }

    /// Rule C (#2889, know 24474f6010): a NULL-rejecting predicate on a bare column, as a top-level AND conjunct of
    /// the WHERE of the same SELECT, proves that column non-null.
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "SELECT note AS x FROM t WHERE note IS NOT NULL",
        "SELECT note AS x FROM t WHERE note NOTNULL",
        "SELECT note AS x FROM t WHERE note = 'a'",
        "SELECT note AS x FROM t WHERE note <> 'a'",
        "SELECT note AS x FROM t WHERE note != 'a'",
        "SELECT note AS x FROM t WHERE note < 'a'",
        "SELECT note AS x FROM t WHERE note > 'a'",
        "SELECT note AS x FROM t WHERE note <= 'a'",
        "SELECT note AS x FROM t WHERE note >= 'a'",
        "SELECT note AS x FROM t WHERE note BETWEEN 'a' AND 'z'",
        "SELECT note AS x FROM t WHERE note LIKE 'a%'",
        "SELECT note AS x FROM t WHERE note ILIKE 'a%'",
        "SELECT note AS x FROM t WHERE note IN ('a', 'b')",
        "SELECT note AS x FROM t WHERE note IN (SELECT name FROM t)",
        "SELECT t.note AS x FROM t WHERE t.note IS NOT NULL",
        "SELECT note AS x FROM t WHERE t.note IS NOT NULL",
        "SELECT t.note AS x FROM t WHERE note IS NOT NULL",
        "SELECT note AS x FROM t WHERE 'a' = note",
        "SELECT note AS x FROM t WHERE qty > 1 AND note IS NOT NULL",
        "SELECT note AS x FROM t WHERE note IS NOT NULL AND qty > 1 AND name = 'a'",
        "SELECT note AS x FROM t WHERE (note IS NOT NULL AND qty > 1)",
        "SELECT note AS x FROM t WHERE qty > 1 AND (name = 'a' AND note IS NOT NULL)",
        "SELECT u.label AS x FROM t LEFT JOIN u ON u.t_id = t.id WHERE u.label IS NOT NULL",
        "SELECT label AS x FROM t LEFT JOIN u ON u.t_id = t.id WHERE label = 'a'",
        "SELECT u.id AS x FROM t LEFT JOIN u ON u.t_id = t.id WHERE u.id IS NOT NULL",
        "SELECT t.note AS x FROM u RIGHT JOIN t ON u.t_id = t.id WHERE t.note IS NOT NULL",
        "SELECT d.x AS y FROM (SELECT note AS x FROM t) d WHERE d.x IS NOT NULL",
        "SELECT x AS y FROM (SELECT note AS x FROM t) d WHERE x IS NOT NULL",
        "SELECT x AS y FROM (SELECT note AS x FROM t WHERE note IS NOT NULL) d",
        "WITH c AS (SELECT note AS x FROM t WHERE note IS NOT NULL) SELECT x AS y FROM c"
    })
    void rejectingPredicateOnBareColumn_provesTheColumn(String sql) {
        assertThat(verdict(sql, sql.contains(" AS y ") ? "y" : "x")).isInstanceOf(Nullability.NonNull.class);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "SELECT note AS x FROM t WHERE note IS NOT NULL OR qty > 1",
        "SELECT note AS x FROM t WHERE qty > 1 OR note IS NOT NULL",
        "SELECT note AS x FROM t WHERE (note IS NOT NULL OR qty > 1) AND qty > 0",
        "SELECT note AS x FROM t WHERE NOT note IS NULL",
        "SELECT note AS x FROM t WHERE NOT (note IS NOT NULL)",
        "SELECT note AS x FROM t WHERE NOT (note IS NOT NULL AND qty > 1)",
        "SELECT note AS x FROM t WHERE note NOT IN ('a')",
        "SELECT note AS x FROM t WHERE note NOT LIKE 'a%'",
        "SELECT note AS x FROM t WHERE note NOT BETWEEN 'a' AND 'z'",
        "SELECT note AS x FROM t WHERE EXISTS (SELECT 1 FROM u WHERE t.note IS NOT NULL)",
        "SELECT note AS x FROM t WHERE qty IN (SELECT t2.qty FROM t t2 WHERE t2.note IS NOT NULL)",
        "SELECT (SELECT u.label FROM u WHERE u.label IS NOT NULL LIMIT 1) AS x FROM t",
        "SELECT note AS x FROM t GROUP BY note HAVING note IS NOT NULL",
        "SELECT u.label AS x FROM t JOIN u ON u.label IS NOT NULL",
        "SELECT u.label AS x FROM t LEFT JOIN u ON u.t_id = t.id AND u.label IS NOT NULL",
        "SELECT u.id AS x FROM t LEFT JOIN u ON u.t_id = t.id AND u.id IS NOT NULL",
        "SELECT note AS x FROM t WHERE lower(note) = 'a'",
        "SELECT note AS x FROM t WHERE note::text = 'a'",
        "SELECT note AS x FROM t WHERE coalesce(note, '') = ''",
        "SELECT note AS x FROM t WHERE (note) IS NOT NULL",
        "SELECT note AS x FROM t WHERE note || 'a' IS NOT NULL",
        "SELECT note AS x FROM t WHERE note IS NULL",
        "SELECT note AS x FROM t WHERE note IS DISTINCT FROM 'a'",
        "SELECT note AS x FROM t WHERE note IS DISTINCT FROM NULL",
        "SELECT note AS x FROM t WHERE note IS NOT DISTINCT FROM 'a'",
        "SELECT note AS x FROM t WHERE note IS NOT DISTINCT FROM NULL",
        "SELECT note AS x FROM t WHERE note IS NOT DISTINCT FROM name",
        "SELECT note AS x FROM t WHERE note IS NOT TRUE",
        "SELECT note AS x FROM t WHERE note IS NOT FALSE",
        "SELECT note AS x FROM t WHERE note IS NOT UNKNOWN",
        "SELECT note AS x FROM t WHERE note = ALL (ARRAY['a'])",
        "SELECT note AS x FROM t WHERE note <> ALL (ARRAY[]::text[])",
        "SELECT note AS x FROM t WHERE note < ALL (SELECT name FROM t WHERE false)",
        "SELECT note AS x FROM t WHERE note = ALL (SELECT name FROM t)",
        "SELECT note AS x FROM t WHERE note = ANY (ARRAY['a'])",
        "SELECT note AS x FROM t WHERE note <> ANY (SELECT name FROM t)",
        "SELECT note AS x FROM t WHERE note > SOME (ARRAY['a'])",
        "SELECT note AS x FROM t WHERE note >= SOME (SELECT name FROM t)",
        "SELECT note AS x FROM t WHERE note IS TRUE",
        "SELECT note AS x FROM t WHERE note IS NULL = true",
        "SELECT u.id AS x FROM u RIGHT JOIN t ON u.t_id = t.id WHERE id IS NOT NULL",
        "SELECT note AS x FROM t WHERE qty > 1",
        "SELECT t.note AS x FROM t, missing m WHERE note IS NOT NULL",
        "SELECT note AS x FROM t WHERE note IS NOT NULL GROUP BY ROLLUP (note)",
        "UPDATE t SET qty = 1 WHERE note IS NOT NULL RETURNING note AS x",
        "SELECT note AS x FROM t WHERE note IS NOT NULL UNION SELECT note FROM t"
    })
    void predicateOutsideRuleC_provesNothing(String sql) {
        assertThat(verdict(sql, "x")).isInstanceOf(Nullability.MayBeNull.class);
    }

    /// EXISTS and NOT EXISTS always yield true or false; a scalar subquery that is a lone ungrouped count(..) always
    /// returns one row. Every other scalar subquery stays nullable (an empty one yields NULL).
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "SELECT EXISTS (SELECT 1 FROM u) AS x FROM t",
        "SELECT NOT EXISTS (SELECT 1 FROM u WHERE u.t_id = t.id) AS x FROM t",
        "SELECT exists (SELECT 1 FROM u WHERE u.t_id = t.id) AS x FROM t",
        "SELECT (EXISTS (SELECT 1 FROM u)) AS x FROM t",
        "SELECT (SELECT count(*) FROM u) AS x FROM t",
        "SELECT (SELECT count(*) FROM u WHERE u.t_id = t.id) AS x FROM t",
        "SELECT (SELECT count(label) FROM u WHERE u.t_id = t.id) AS x FROM t",
        "SELECT (SELECT COUNT(DISTINCT label) FROM u) AS x FROM t",
        "SELECT (SELECT count(*) FROM u WHERE false) AS x FROM t",
        "SELECT (SELECT count(*) AS n FROM u) AS x FROM t"
    })
    void alwaysOneValue_isProvenNonNull(String sql) {
        assertThat(verdict(sql, "x")).isInstanceOf(Nullability.NonNull.class);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "SELECT (SELECT count(*) FROM u GROUP BY t_id) AS x FROM t",
        "SELECT (SELECT count(*) FROM u GROUP BY t_id HAVING count(*) > 1) AS x FROM t",
        "SELECT (SELECT count(*) FROM u HAVING count(*) > 1) AS x FROM t",
        "SELECT (SELECT count(*) FROM u LIMIT 0) AS x FROM t",
        "SELECT (SELECT count(*) FROM u OFFSET 1) AS x FROM t",
        "SELECT (SELECT count(*) FROM u UNION SELECT count(*) FROM u) AS x FROM t",
        "SELECT (SELECT count(*) OVER () FROM u) AS x FROM t",
        "SELECT (SELECT max(t_id) FROM u) AS x FROM t",
        "SELECT (SELECT count(*), count(*) FROM u) AS x FROM t",
        "SELECT (SELECT count(*) + 1 FROM u) AS x FROM t",
        "SELECT (SELECT lower(label) FROM u) AS x FROM t",
        "SELECT (SELECT t_id FROM u WHERE id = 1) AS x FROM t",
        "SELECT (SELECT (SELECT count(*) FROM u)) AS x FROM t",
        "SELECT (WITH c AS (SELECT 1) SELECT count(*) FROM u LIMIT 0) AS x FROM t",
        "SELECT NOT note AS x FROM t",
        "SELECT NOT (qty > 1) AS x FROM t",
        "SELECT NOT NOT EXISTS (SELECT 1 FROM u) AS x FROM t"
    })
    void subqueryOrNegationOutsideTheProvenSet_staysNullable(String sql) {
        assertThat(verdict(sql, "x")).isInstanceOf(Nullability.MayBeNull.class);
    }

    @Test
    void leftJoinWherePredicate_provesOnlyThatColumn() {
        var sql = "SELECT u.label AS a, u.t_id AS b, u.id AS c FROM t LEFT JOIN u ON u.t_id = t.id WHERE u.label IS NOT NULL";

        assertThat(verdict(sql, "a")).isInstanceOf(Nullability.NonNull.class);
        assertThat(verdict(sql, "b")).isInstanceOfSatisfying(Nullability.MayBeNull.class,
                                                              n -> assertThat(n.reason()).contains("nullable side of a LEFT JOIN"));
        assertThat(verdict(sql, "c")).isInstanceOf(Nullability.MayBeNull.class);
    }

    @Test
    void wherePredicate_provesOnlyItsOwnColumn() {
        var result = outputs("SELECT * FROM u WHERE label IS NOT NULL");

        assertThat(result.stream().map(o -> o.nullability().isNonNull()).toList()).containsExactly(true, true, true);
        assertThat(outputs("SELECT note AS a, id AS b FROM t WHERE note IS NOT NULL").stream()
                                                                                    .map(o -> o.nullability().isNonNull())
                                                                                    .toList()).containsExactly(true, true);
        assertThat(verdict("SELECT note AS a FROM t WHERE name IS NOT NULL", "a")).isInstanceOf(Nullability.MayBeNull.class);
    }

    @Test
    void wherePredicateOnABranch_doesNotProveTheOtherBranch() {
        assertThat(verdict("SELECT note AS x FROM t WHERE note IS NOT NULL UNION SELECT note FROM t WHERE note IS NOT NULL", "x"))
            .isInstanceOf(Nullability.NonNull.class);
    }

    /// #2962: a proven output names the rule of the proven set that made it so.
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "SELECT qty AS x FROM t | column 't.qty' is a NOT NULL column",
        "SELECT note AS x FROM t WHERE note IS NOT NULL | a top-level WHERE conjunct rejects NULL for column 't.note'",
        "SELECT EXISTS (SELECT 1 FROM u) AS x FROM t | EXISTS (..) always yields true or false",
        "SELECT NOT EXISTS (SELECT 1 FROM u) AS x FROM t | NOT EXISTS (..) always yields true or false",
        "SELECT (SELECT count(*) FROM u) AS x FROM t | ungrouped count(..) scalar subquery",
        "SELECT count(*) AS x FROM t | count(..) never yields NULL",
        "SELECT 1 AS x FROM t | a non-NULL literal",
        "SELECT DATE '2020-01-01' AS x FROM t | a typed literal is never NULL",
        "SELECT COALESCE(note, 'n') AS x FROM t | COALESCE whose last argument is proven non-null (a non-NULL literal)",
        "SELECT CAST(qty AS text) AS x FROM t | column 't.qty' is a NOT NULL column",
        "SELECT name AS x FROM t UNION ALL SELECT name FROM t | every set operation branch is proven non-null",
        "SELECT x FROM (SELECT qty AS x FROM t) d | column 't.qty' is a NOT NULL column",
        "SELECT u.id AS x FROM t LEFT JOIN u ON u.t_id = t.id WHERE u.id > 0 | a top-level WHERE conjunct rejects NULL for column 'u.id'"
    })
    void provenOutput_namesItsProofRule(String sql, String proof) {
        assertThat(verdict(sql, "x")).isInstanceOfSatisfying(Nullability.NonNull.class,
                                                              proven -> assertThat(proven.proof()).contains(proof));
    }

    @Test
    void valuesColumn_namesItsProofRule() {
        assertThat(verdict("VALUES (1), (2)", "column1")).isInstanceOfSatisfying(Nullability.NonNull.class,
                                                                                  proven -> assertThat(proven.proof()).contains("every VALUES row is proven non-null"));
    }
}
