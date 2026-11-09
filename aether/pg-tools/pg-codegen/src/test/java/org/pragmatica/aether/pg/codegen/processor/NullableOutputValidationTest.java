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

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// #2889: a query output NOT PROVEN non-null, read into a non-`Option` field or a scalar return, fails the build;
/// the diagnostic names the method, the output, why it may be NULL and the fix. A provably non-null output is
/// accepted, and `Option<T>` on one draws no warning.
///
/// Schema (`src/test/resources/schema`): `orders(id, user_id, status, total NOT NULL; correlation_id nullable)`,
/// `users(id, name, email NOT NULL)`, `events(id, name, type NOT NULL; event_date nullable)`.
class NullableOutputValidationTest {
    private static final String MAY_BE_NULL = "may be NULL";

    @TempDir
    Path tempDir;

    private static Arguments nullable(String name, String fields, String sql, String reason) {
        return Arguments.of(name, fields, sql, reason);
    }

    static Stream<Arguments> nullableForms() {
        return Stream.of(
            nullable("nullable column", "long id, UUID correlationId", "SELECT id, correlation_id FROM orders", "column 'orders.correlation_id' is nullable"),
            nullable("nullable column via star", "long id, UUID correlationId", "SELECT * FROM orders", "column 'orders.correlation_id' is nullable"),
            nullable("left join right side", "long id, String name", "SELECT o.id AS id, u.name AS name FROM orders o LEFT JOIN users u ON u.id = o.user_id", "nullable side of a LEFT JOIN"),
            nullable("right join left side", "long id, String name", "SELECT o.id AS id, u.name AS name FROM users u RIGHT JOIN orders o ON u.id = o.user_id", "nullable side of a RIGHT JOIN"),
            nullable("full join", "String status, String name", "SELECT o.status AS status, u.name AS name FROM orders o FULL JOIN users u ON u.id = o.user_id", "nullable side of a FULL JOIN"),
            nullable("max", "BigDecimal total", "SELECT max(total) AS total FROM orders", "max() yields NULL"),
            nullable("min", "BigDecimal total", "SELECT min(total) AS total FROM orders", "min() yields NULL"),
            nullable("sum", "BigDecimal total", "SELECT sum(total) AS total FROM orders", "sum() yields NULL"),
            nullable("case without else", "String status", "SELECT CASE WHEN total > 1 THEN 'big' END AS status FROM orders", "CASE without ELSE"),
            nullable("nullif", "String status", "SELECT NULLIF(status, 'x') AS status FROM orders", "NULLIF"),
            nullable("scalar subquery", "String name", "SELECT (SELECT u.name FROM users u WHERE u.id = o.user_id) AS name FROM orders o", "scalar subquery"),
            nullable("null literal", "String name", "SELECT NULL AS name FROM orders", "NULL literal"),
            nullable("coalesce with nullable last", "String status", "SELECT COALESCE('a', CAST(correlation_id AS text)) AS status FROM orders", "COALESCE whose last argument may be NULL"),
            nullable("unproven function", "String status", "SELECT lower(status) AS status FROM orders", "lower() is not proven"),
            nullable("set operation nullable branch", "String name", "SELECT name AS name FROM users UNION SELECT CAST(correlation_id AS text) FROM orders", "set operation branch 2"),
            nullable("unaliased max", "BigDecimal max", "SELECT max(total) FROM orders", "max() yields NULL"),
            nullable("unaliased sum", "BigDecimal sum", "SELECT sum(total) FROM orders", "sum() yields NULL"),
            nullable("unaliased cast of nullable column", "String correlationId", "SELECT correlation_id::text FROM orders", "column 'orders.correlation_id' is nullable"),
            nullable("unaliased CAST of nullable column", "String correlationId", "SELECT CAST(correlation_id AS text) FROM orders", "column 'orders.correlation_id' is nullable"),
            nullable("unaliased lower on left join", "String lower", "SELECT lower(u.name) FROM orders o LEFT JOIN users u ON u.id = o.user_id", "lower() is not proven"),
            nullable("unaliased coalesce nullable last", "String coalesce", "SELECT COALESCE('a', correlation_id::text) FROM orders", "COALESCE whose last argument may be NULL"),
            nullable("unaliased nullif", "String nullif", "SELECT NULLIF(status, 'x') FROM orders", "NULLIF"),
            nullable("unnamed nullable output, unmatched field", "String name", "SELECT status || 'x' FROM orders", "|| expression is not proven"),
            nullable("star over derived table", "long id, UUID correlationId", "SELECT * FROM (SELECT id, correlation_id FROM orders) d", "column 'orders.correlation_id' is nullable"),
            nullable("star over cte", "long id, UUID correlationId", "WITH c AS (SELECT id, correlation_id FROM orders) SELECT * FROM c", "column 'orders.correlation_id' is nullable"),
            nullable("star over unresolvable derived table", "long a, UUID b", "SELECT * FROM (SELECT id, correlation_id FROM orders) d(a, b)", "cannot be listed"),
            nullable("union with values null", "String name", "SELECT name FROM users UNION VALUES (NULL)", "set operation branch 2"),
            nullable("union with null literal", "String name", "SELECT name FROM users UNION ALL SELECT NULL", "set operation branch 2"),
            nullable("returning nullable column", "UUID correlationId", "UPDATE orders SET status = 'x' WHERE id = 1 RETURNING correlation_id", "column 'orders.correlation_id' is nullable"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nullableForms")
    void nullableOutput_onNonOptionField_failsTheBuild(String name, String fields, String sql, String reason) throws Exception {
        var result = compile(repo("NullableRepo", "record Row(" + fields + ") {}", sql, "Promise<List<Row>> run()"));

        assertThat(result.success()).isFalse();
        assertThat(result.diagnostics())
            .contains("ERROR")
            .contains(MAY_BE_NULL)
            .contains("Method 'run'")
            .contains(reason)
            .contains("COALESCE")
            .contains("Option<T>");
    }

    static Stream<Arguments> provenForms() {
        return Stream.of(
            Arguments.of("not null columns", "long id, String status, BigDecimal total", "SELECT id, status, total FROM orders"),
            Arguments.of("star over not null table", "long id, String name", "SELECT * FROM users"),
            Arguments.of("inner join", "long id, String name", "SELECT o.id AS id, u.name AS name FROM orders o JOIN users u ON u.id = o.user_id"),
            Arguments.of("left join root side", "long id, String name", "SELECT o.id AS id, o.status AS name FROM orders o LEFT JOIN users u ON u.id = o.user_id"),
            Arguments.of("count star", "long n", "SELECT count(*) AS n FROM orders"),
            Arguments.of("literal", "long one, String s", "SELECT 1 AS one, 'x' AS s FROM orders"),
            Arguments.of("coalesce with literal last", "BigDecimal total", "SELECT COALESCE(max(total), 0) AS total FROM orders"),
            Arguments.of("cast of not null", "String status", "SELECT CAST(user_id AS text) AS status FROM orders"),
            Arguments.of("returning not null", "long id", "UPDATE orders SET status = 'x' WHERE id = 1 RETURNING id"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("provenForms")
    void provenNonNullOutput_onNonOptionField_isAccepted(String name, String fields, String sql) throws Exception {
        var result = compile(repo("ProvenRepo", "record Row(" + fields + ") {}", sql, "Promise<List<Row>> run()"));

        assertThat(result.diagnostics()).doesNotContain(MAY_BE_NULL);
        assertThat(result.success()).as(result.diagnostics()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nullableForms")
    void nullableOutput_onOptionField_isAccepted(String name, String fields, String sql, String reason) throws Exception {
        var optionFields = String.join(", ", java.util.Arrays.stream(fields.split(", "))
                                                             .map(NullableOutputValidationTest::optionIfNullable)
                                                             .toList());
        var result = compile(repo("OptionRepo", "record Row(" + optionFields + ") {}", sql, "Promise<List<Row>> run()"));

        assertThat(result.diagnostics()).doesNotContain(MAY_BE_NULL);
        assertThat(result.success()).as(result.diagnostics()).isTrue();
    }

    /// Every field is wrapped except the `id` column, which each nullable form leaves proven or does not select.
    private static String optionIfNullable(String field) {
        var parts = field.split(" ");

        return parts[1].equals("id")
               ? field
               : "Option<" + box(parts[0]) + "> " + parts[1];
    }

    private static String box(String type) {
        return type.equals("long")
               ? "Long"
               : type;
    }

    @Test
    void optionField_onProvenNonNullOutput_drawsNoWarning() throws Exception {
        var result = compile(repo("OptionOnProvenRepo",
                                  "record Row(long id, Option<String> status) {}",
                                  "SELECT id, status FROM orders",
                                  "Promise<List<Row>> run()"));

        assertThat(result.success()).as(result.diagnostics()).isTrue();
        assertThat(result.diagnostics()).doesNotContain("Method 'run'").doesNotContain(MAY_BE_NULL);
    }

    /// The same name twice in one select list is ambiguous for a name-based mapper, whatever the field type.
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"String", "Option<String>"})
    void duplicateOutputName_mappedToRecordField_failsTheBuild(String fieldType) throws Exception {
        var result = compile(repo("DuplicateRepo",
                                  "record Row(" + fieldType + " name) {}",
                                  "SELECT CAST(o.correlation_id AS text) AS name, u.name FROM orders o JOIN users u ON u.id = o.user_id",
                                  "Promise<List<Row>> run()"));

        assertThat(result.success()).isFalse();
        assertThat(result.diagnostics()).contains("Method 'run'").contains("output name 'name' appears 2 times").contains("field 'name'")
                                        .doesNotContain(MAY_BE_NULL);
    }

    @Test
    void duplicateOutputName_notReadByAnyField_isAccepted() throws Exception {
        var result = compile(repo("UnreadDuplicateRepo",
                                  "record Row(long id)  {}",
                                  "SELECT o.id AS id, o.status AS name, u.name FROM orders o JOIN users u ON u.id = o.user_id",
                                  "Promise<List<Row>> run()"));

        assertThat(result.success()).as(result.diagnostics()).isTrue();
    }

    @Test
    void scalarMax_failsTheBuild_withCoalesceFix() throws Exception {
        var result = compile(repo("ScalarMaxRepo", "", "SELECT max(id) AS m FROM orders", "Promise<Long> run()"));

        assertThat(result.success()).isFalse();
        assertThat(result.diagnostics()).contains(MAY_BE_NULL).contains("Method 'run'").contains("max() yields NULL")
                                        .contains("COALESCE").doesNotContain("Option<T>");
    }

    @Test
    void scalarMaxUnderOptionRow_stillFailsTheBuild() throws Exception {
        var result = compile(repo("ScalarOptionRepo", "", "SELECT max(id) AS m FROM orders", "Promise<Option<Long>> run()"));

        assertThat(result.success()).isFalse();
        assertThat(result.diagnostics()).contains(MAY_BE_NULL).contains("COALESCE");
    }

    @Test
    void scalarCoalescedAndCounted_areAccepted() throws Exception {
        var coalesced = compile(repo("ScalarCoalesceRepo", "", "SELECT COALESCE(max(id), 0) AS m FROM orders", "Promise<Long> run()"));
        var counted = compile(repo("ScalarCountRepo", "", "SELECT count(*) AS n FROM orders", "Promise<Long> run()"));

        assertThat(coalesced.success()).as(coalesced.diagnostics()).isTrue();
        assertThat(counted.success()).as(counted.diagnostics()).isTrue();
    }

    /// The processor boundary (#2894 verifier): any failure inside one method's analysis becomes a build error
    /// that names the method and its SQL, not an uncaught crash naming a grammar rule.
    @Test
    void failureInsideMethodAnalysis_becomesBuildErrorNamingMethodAndSql() throws Exception {
        var failing = new QueryAnnotationProcessor() {
            @Override
            public java.util.Set<String> getSupportedAnnotationTypes() {
                return java.util.Set.of("*");
            }

            @Override
            public javax.lang.model.SourceVersion getSupportedSourceVersion() {
                return javax.lang.model.SourceVersion.latestSupported();
            }

            @Override
            FactoryGenerator.MethodInfo analyzeMethod(ExecutableElement execElement,
                                                      org.pragmatica.lang.Option<org.pragmatica.aether.pg.schema.model.Schema> schemaOpt,
                                                      TypeElement interfaceElement) {
                throw new UnsupportedOperationException("label has no lexeme at rule ColLabel");
            }
        };
        var source = repo("ForcedFailureRepo", "record Row(long id) {}", "SELECT id FROM orders", "Promise<List<Row>> run()");

        var result = TestCompilationHelper.compileWithProcessor(failing, source, "test/ForcedFailureRepo.java", tempDir);

        assertThat(result.success()).as(result.diagnostics()).isFalse();
        assertThat(result.diagnostics())
            .contains("ERROR")
            .contains("Method 'run' could not be analysed")
            .contains("label has no lexeme at rule ColLabel")
            .contains("SQL: SELECT id FROM orders");
    }

    private static String repo(String name, String recordDecl, String sql, String method) {
        return """
            package test;

            import org.pragmatica.aether.pg.codegen.annotation.Query;
            import org.pragmatica.aether.resource.db.PgSql;
            import org.pragmatica.lang.Option;
            import org.pragmatica.lang.Promise;
            import java.math.BigDecimal;
            import java.util.List;
            import java.util.UUID;

            @PgSql
            public interface %s {
                %s

                @Query("%s")
                %s;
            }
            """.formatted(name, recordDecl, sql, method);
    }

    private TestCompilationHelper.CompilationResult compile(String source) throws Exception {
        var name = source.substring(source.indexOf("interface ") + 10, source.indexOf(" {"));

        return TestCompilationHelper.compileWithProcessor(source, "test/" + name + ".java", tempDir);
    }
}
