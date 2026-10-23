// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.pg.codegen.processor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.aether.resource.db.JsonText;
import org.pragmatica.aether.resource.db.PgSqlConnector;
import org.pragmatica.aether.resource.db.async.PgSqlConnectorFactory;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// The generated `@Jsonb` factory (`JsonbMappingTest.DOC_REPO`) against a LIVE PostgreSQL 16 through the async
/// connector: a record with a nested record, an `Option` field (present and empty) and a list round-trips through a
/// `jsonb` column and a `json` column of the same row; an empty `Option<T>` component is stored as SQL NULL and read
/// back as empty; a `@Jsonb` param drives a containment query; a corrupt stored document fails the row, naming the
/// column. It also pins the binding premise: a `String` parameter is declared `varchar`, which a `jsonb` column refuses
/// for assignment and a `jsonb` operator refuses as an operand on a freshly prepared statement, while `JsonText` (type
/// unspecified) works for both; and `JsonText` is untyped rather than `jsonb`-typed, which only a `json`-only function
/// can show, since `json` and `jsonb` have assignment casts both ways. Self-skips when no Docker daemon is reachable.
class JsonbPostgresRoundTripTest {
    private static final String DDL = """
        CREATE TABLE documents (
            id BIGINT PRIMARY KEY,
            profile JSONB NOT NULL,
            previous JSONB,
            legacy JSON NOT NULL,
            title TEXT NOT NULL,
            nick TEXT
        )""";

    private static final String SCENARIO = """
        package test;

        import java.util.List;

        import org.pragmatica.aether.resource.db.PgSqlConnector;
        import org.pragmatica.lang.Option;

        public final class Scenario {
            // Non-ASCII on purpose: Cyrillic and an emoji (a UTF-16 surrogate pair) catch any charset other than UTF-8.
            static final Address KYIV = new Address("Київ", "01001");
            static final Profile ANN = new Profile("Іван 😀", Option.some("ваня"), List.of(KYIV, new Address("Львів", "79000")), KYIV);
            static final Profile BOB = new Profile("Bob", Option.none(), List.of(), new Address("Odesa", "65000"));
            static final DocumentRow FIRST = new DocumentRow(1L, ANN, Option.some(BOB), BOB, "first", Option.some("a"));
            static final DocumentRow SECOND = new DocumentRow(2L, BOB, Option.none(), ANN, "second", Option.none());

            public static void insertBoth(PgSqlConnector db) {
                var repo = DocRepoFactory.docRepo(db);
                repo.insert(FIRST).await().unwrap();
                repo.insert(SECOND).await().unwrap();
            }

            public static List<Object> foundById(PgSqlConnector db) {
                var repo = DocRepoFactory.docRepo(db);
                return List.of(Option.some(FIRST), repo.findById(1L).await().unwrap(),
                               Option.some(SECOND), repo.findById(2L).await().unwrap());
            }

            public static List<Object> containingAnn(PgSqlConnector db) {
                return List.of(List.of(FIRST), DocRepoFactory.docRepo(db).containing(new Probe("Іван 😀")).await().unwrap());
            }

            public static List<Object> documentResults(PgSqlConnector db) {
                var repo = DocRepoFactory.docRepo(db);
                return List.of(Option.some(BOB), repo.profileOf(2L).await().unwrap(),
                               Option.none(), repo.profileOf(99L).await().unwrap(),
                               List.of(BOB, ANN), repo.legacies().await().unwrap());
            }

            public static List<Object> replacePrevious(PgSqlConnector db) {
                var repo = DocRepoFactory.docRepo(db);
                repo.replacePrevious(2L, Option.some(ANN)).await().unwrap();
                var present = repo.findById(2L).await().unwrap().map(DocumentRow::previous);
                repo.replacePrevious(2L, Option.none()).await().unwrap();
                var empty = repo.findById(2L).await().unwrap().map(DocumentRow::previous);
                return List.of(Option.some(Option.some(ANN)), present, Option.some(Option.none()), empty);
            }

            public static List<Object> evolvedDocument(PgSqlConnector db) {
                return List.of(Option.some(BOB), DocRepoFactory.docRepo(db).profileOf(7L).await().unwrap());
            }

            public static Object findById(PgSqlConnector db, long id) {
                return DocRepoFactory.docRepo(db).findById(id).await();
            }
        }
        """;

    private static final String INFERRED_SCENARIO = """
        package inferred;

        import java.util.List;

        import org.pragmatica.aether.resource.db.PgSqlConnector;
        import org.pragmatica.lang.Option;

        public final class InferredScenario {
            static final Address HOME = new Address("Київ", "01001");
            static final Profile EVE = new Profile("Єва 😀", Option.some("єва"), List.of(HOME), HOME);
            static final InferredRow ROW = new InferredRow(11L, EVE, Option.none(), EVE, "inferred", Option.none());

            public static List<Object> roundTrip(PgSqlConnector db) {
                var repo = InferredRepoFactory.inferredRepo(db);
                repo.insert(ROW).await().unwrap();
                return List.of(Option.some(ROW), repo.findById(11L).await().unwrap(),
                               List.of(ROW), repo.findByProfile(EVE).await().unwrap());
            }
        }
        """;

    private static PostgreSQLContainer<?> postgres;
    private static PgSqlConnector connector;
    private static Class<?> scenario;
    private static Class<?> inferredScenario;

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void startPostgres() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the live-PostgreSQL round trip");
        scenario = compileScenario();
        postgres = new PostgreSQLContainer<>("postgres:16");
        postgres.start();
        connector = new PgSqlConnectorFactory().provision(connectorConfig()).await().unwrap();
    }

    @AfterAll
    static void stopPostgres() {
        if (connector != null) {
            connector.stop().await();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void freshTable() {
        connector.update("DROP TABLE IF EXISTS documents").await().unwrap();
        connector.update(DDL).await().unwrap();
    }

    @Test
    void record_roundTripsThroughJsonbAndJsonColumns_withNestedRecordOptionAndList() throws Exception {
        invoke("insertBoth");

        assertPairs(invoke("foundById"));
        assertThat(count("SELECT count(*) FROM documents WHERE previous IS NULL")).as("an empty Option is stored as SQL NULL, not JSON null")
                                                                                .isEqualTo(1L);
        assertThat(count("SELECT count(*) FROM documents WHERE nick IS NULL")).isEqualTo(1L);
        assertThat(count("SELECT count(*) FROM documents WHERE profile ->> 'name' = 'Іван 😀' AND legacy ->> 'name' = 'Bob'"))
            .as("the documents are stored as JSON the server can query")
            .isEqualTo(1L);
    }

    /// #2401: the same documents with no `@Jsonb` anywhere: the CRUD table says the columns are json/jsonb, so the
    /// record components, the `Option` component and the `findByProfile` condition param are documents.
    @Test
    void inferredDocuments_roundTripWithoutMarkers() throws Exception {
        assertPairs(List.of(inferredScenario.getMethods())
                        .stream()
                        .filter(method -> method.getName().equals("roundTrip"))
                        .findFirst()
                        .orElseThrow()
                        .invoke(null, connector));
    }

    @Test
    void jsonbParam_drivesContainmentQuery() throws Exception {
        invoke("insertBoth");

        assertPairs(invoke("containingAnn"));
    }

    @Test
    void markedMethod_decodesSingleColumnResults() throws Exception {
        invoke("insertBoth");

        assertPairs(invoke("documentResults"));
    }

    @Test
    void optionParam_writesPresentAsDocument_andEmptyAsNull() throws Exception {
        invoke("insertBoth");

        assertPairs(invoke("replacePrevious"));
        assertThat(count("SELECT count(*) FROM documents WHERE previous IS NULL")).isEqualTo(1L);
    }

    /// The stored document has no `nick` field, as one written before `Profile` gained its `Option` component would:
    /// it decodes with the component empty, which is why documents evolve by adding `Option` components only.
    @Test
    void nullJsonbColumn_readsAsEmpty_andDocumentWithoutOptionFieldDecodes() throws Exception {
        connector.update("INSERT INTO documents (id, profile, legacy, title) VALUES ($1, $2, $3, $4)",
                         7L,
                         JsonText.jsonText(BOB_JSON),
                         JsonText.jsonText(BOB_JSON),
                         "raw")
                 .await()
                 .unwrap();

        var found = invokeWith("findById", 7L).toString();

        assertThat(found).contains("previous=None()").contains("title=raw, nick=None()");
        assertPairs(invoke("evolvedDocument"));
    }

    /// The generated mapper fails with a typed `RowDecode` (see `JsonbMappingTest`); the async connector reports every
    /// mapper failure that is not a connector error as `DatabaseFailure` carrying its message, which names the column.
    @Test
    void corruptStoredDocument_failsTheRow_namingTheColumn() throws Exception {
        connector.update("INSERT INTO documents (id, profile, legacy, title) VALUES ($1, $2, $3, $4)",
                         8L,
                         JsonText.jsonText("{\"name\": 42}"),
                         JsonText.jsonText(BOB_JSON),
                         "corrupt")
                 .await()
                 .unwrap();

        var result = (Result<?>) invokeWith("findById", 8L);

        assertThat(result.isFailure()).as("a document the codec refuses fails the row").isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("Failed to decode column 'profile'")
                                                             .contains("Malformed JSON"));
    }

    /// Each probe uses SQL text no other call uses: the driver caches a prepared statement by SQL text alone, so a
    /// `varchar` bind reusing a statement first prepared with an unspecified (`JsonText`) parameter would be accepted.
    @Test
    void stringParam_isRefusedByJsonbColumn_whileJsonTextBindsToJsonAndJsonb() {
        var asString = connector.update("INSERT INTO documents (id, title, profile, legacy) VALUES ($1, $2, $3, $4)",
                                        5L,
                                        "string",
                                        BOB_JSON,
                                        JsonText.jsonText(BOB_JSON))
                                .await();

        assertThat(asString.isFailure()).as("a String parameter is declared varchar, which a jsonb column refuses").isTrue();
        asString.onFailure(cause -> assertThat(cause.message()).contains("is of type jsonb but expression is of type character varying"));

        var asJsonText = connector.update("INSERT INTO documents (title, id, profile, legacy) VALUES ($1, $2, $3, $4)",
                                          "json-text",
                                          6L,
                                          JsonText.jsonText(BOB_JSON),
                                          JsonText.jsonText(BOB_JSON))
                                  .await();

        assertThat(asJsonText.fold(Cause::message, _ -> "bound")).as("JsonText into jsonb and json").isEqualTo("bound");
        assertThat(count("SELECT count(*) FROM documents WHERE id = 6")).isEqualTo(1L);
    }

    @Test
    void stringParam_isNoJsonbOperand_whileJsonTextIs() throws Exception {
        invoke("insertBoth");

        var asString = connector.queryOne("SELECT count(*) AS count FROM documents WHERE profile @> $1 AND id > 0",
                                          row -> row.getLong("count"),
                                          "{}")
                                .await();

        assertThat(asString.isFailure()).as("a varchar parameter is no jsonb operand").isTrue();
        asString.onFailure(cause -> assertThat(cause.message()).contains("operator does not exist"));
        assertThat(connector.queryOne("SELECT count(*) AS count FROM documents WHERE profile @> $1 AND id >= 0",
                                      row -> row.getLong("count"),
                                      JsonText.jsonText("{}"))
                            .await())
            .as("JsonText as the jsonb operand")
            .isEqualTo(Result.success(2L));
    }

    /// A nullable read surfaces a failed read as a failure, never as an empty option: an unknown column and a value of
    /// the wrong type both fail.
    @Test
    void getOptional_unknownColumnOrWrongType_fails_neverReadsAsEmpty() {
        var unknown = connector.queryOne("SELECT 1::bigint AS l", row -> row.getOptional("nosuchcolumn", Long.class)).await();
        var wrongType = connector.queryOne("SELECT 1::bigint AS l", row -> row.getOptional("l", java.util.UUID.class)).await();

        assertThat(unknown.fold(Cause::message, value -> "read " + value)).contains("nosuchcolumn");
        assertThat(wrongType.isFailure()).as("a bigint read as UUID: " + wrongType).isTrue();
    }

    /// `JsonText` is sent with no declared type, so the server types it from its use. `json_typeof` takes `json` only
    /// and there is no implicit cast from `jsonb` (or `varchar`) to `json`, so only an untyped parameter resolves it;
    /// a `jsonb`-typed or `varchar`-typed parameter does not. Assignment cannot tell these apart: `json` and `jsonb`
    /// have assignment casts both ways.
    @Test
    void jsonTextParameter_isUntyped_soAJsonOnlyFunctionAcceptsIt() {
        assertThat(connector.queryOne("SELECT json_typeof($1) AS t", row -> row.getString("t"), JsonText.jsonText("{}"))
                            .await()).isEqualTo(Result.success("object"));

        var asString = connector.queryOne("SELECT json_typeof($1) AS t /* varchar */", row -> row.getString("t"), "{}").await();

        assertThat(asString.isFailure()).as("control: a typed (varchar) parameter does not resolve json_typeof").isTrue();
    }

    private static final String BOB_JSON = "{\"name\":\"Bob\",\"addresses\":[],\"home\":{\"city\":\"Odesa\",\"zip\":\"65000\"}}";

    private static void assertPairs(Object pairs) {
        var list = (List<?>) pairs;

        for (var i = 0; i < list.size(); i += 2) {
            assertThat(list.get(i + 1)).as("pair %d", i / 2).isEqualTo(list.get(i));
        }
    }

    private static long count(String sql) {
        return connector.queryOne(sql, row -> row.getLong("count")).await().unwrap();
    }

    private static Object invoke(String name) throws Exception {
        return method(name, 1).invoke(null, connector);
    }

    private static Object invokeWith(String name, long id) throws Exception {
        return method(name, 2).invoke(null, connector, id);
    }

    private static Method method(String name, int arity) {
        return List.of(scenario.getMethods())
                   .stream()
                   .filter(method -> method.getName().equals(name) && method.getParameterCount() == arity)
                   .findFirst()
                   .orElseThrow();
    }

    private static Class<?> compileScenario() throws Exception {
        var result = TestCompilationHelper.compileAndCompileGenerated(List.of(new TestCompilationHelper.SourceEntry("test/DocRepo.java",
                                                                                                                    JsonbMappingTest.DOC_REPO),
                                                                              new TestCompilationHelper.SourceEntry("test/Scenario.java",
                                                                                                                    SCENARIO),
                                                                              new TestCompilationHelper.SourceEntry("inferred/InferredRepo.java",
                                                                                                                    JsonbMappingTest.INFERRED_REPO.replace("package test;",
                                                                                                                                                           "package inferred;")),
                                                                              new TestCompilationHelper.SourceEntry("inferred/InferredScenario.java",
                                                                                                                    INFERRED_SCENARIO)),
                                                                      tempDir);

        assertThat(result.success()).as(result.diagnostics()).isTrue();

        var loader = new URLClassLoader(new URL[]{tempDir.resolve("classes").toUri().toURL()},
                                        JsonbPostgresRoundTripTest.class.getClassLoader());

        inferredScenario = loader.loadClass("inferred.InferredScenario");

        return loader.loadClass("test.Scenario");
    }

    private static DatabaseConnectorConfig connectorConfig() {
        var host = postgres.getHost();
        var port = postgres.getMappedPort(5432);
        var database = postgres.getDatabaseName();

        return DatabaseConnectorConfig.databaseConnectorConfigBuilder()
                                      .withName("jsonb-round-trip")
                                      .withType(DatabaseType.POSTGRESQL)
                                      .withHost(host)
                                      .withPort(port)
                                      .withDatabase(database)
                                      .withUsername(postgres.getUsername())
                                      .withPassword(postgres.getPassword())
                                      .withAsyncUrl("postgresql://" + host + ":" + port + "/" + database)
                                      .build()
                                      .unwrap();
    }
}
