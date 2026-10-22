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
/// column. It also pins the binding premise: a `String` parameter is declared `varchar`, which the server accepts for
/// assignment to a `jsonb` column but not as a `jsonb` operator's operand, while `JsonText` (type unspecified) works for
/// both. Self-skips when no Docker daemon is reachable.
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
            static final Address KYIV = new Address("Kyiv", "01001");
            static final Profile ANN = new Profile("Ann", Option.some("an"), List.of(KYIV, new Address("Lviv", "79000")), KYIV);
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
                return List.of(List.of(FIRST), DocRepoFactory.docRepo(db).containing(new Probe("Ann")).await().unwrap());
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

    private static PostgreSQLContainer<?> postgres;
    private static PgSqlConnector connector;
    private static Class<?> scenario;

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
        assertThat(count("SELECT count(*) FROM documents WHERE profile ->> 'name' = 'Ann' AND legacy ->> 'name' = 'Bob'"))
            .as("the documents are stored as JSON the server can query")
            .isEqualTo(1L);
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

    @Test
    void stringParam_isAcceptedForAssignment_butNotAsJsonbOperand_whileJsonTextWorksForBoth() {
        var assigned = connector.update("INSERT INTO documents (id, profile, legacy, title) VALUES ($1, $2, $3, $4)",
                                        5L,
                                        BOB_JSON,
                                        BOB_JSON,
                                        "string")
                                .await();

        assertThat(assigned.fold(Cause::message, _ -> "bound")).as("a varchar parameter assigned to jsonb and json columns")
                                                              .isEqualTo("bound");

        var operand = connector.queryOne("SELECT count(*) FROM documents WHERE profile @> $1", row -> row.getLong("count"), "{}")
                               .await();

        assertThat(operand.isFailure()).as("a varchar parameter is no jsonb operand").isTrue();
        operand.onFailure(cause -> assertThat(cause.message()).contains("operator does not exist"));
        assertThat(connector.queryOne("SELECT count(*) FROM documents WHERE profile @> $1",
                                      row -> row.getLong("count"),
                                      JsonText.jsonText("{}"))
                            .await())
            .as("JsonText as the jsonb operand")
            .isEqualTo(Result.success(1L));

        var asJsonText = connector.update("INSERT INTO documents (id, profile, legacy, title) VALUES ($1, $2, $3, $4)",
                                          6L,
                                          JsonText.jsonText(BOB_JSON),
                                          JsonText.jsonText(BOB_JSON),
                                          "json-text")
                                  .await();

        assertThat(asJsonText.fold(Cause::message, _ -> "bound")).as("JsonText into jsonb and json").isEqualTo("bound");
        assertThat(count("SELECT count(*) FROM documents WHERE id = 6")).isEqualTo(1L);
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
                                                                                                                    SCENARIO)),
                                                                      tempDir);

        assertThat(result.success()).as(result.diagnostics()).isTrue();

        var loader = new URLClassLoader(new URL[]{tempDir.resolve("classes").toUri().toURL()},
                                        JsonbPostgresRoundTripTest.class.getClassLoader());

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
