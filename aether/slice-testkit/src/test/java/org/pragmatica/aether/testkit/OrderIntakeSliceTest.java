// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.testkit;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.db.PgSqlConnector;
import org.pragmatica.aether.testkit.container.Containers;
import org.pragmatica.aether.testkit.fake.CapturingPublisher;
import org.pragmatica.aether.testkit.fake.FakeHttpClient;
import org.pragmatica.aether.testkit.fake.HttpResults;
import org.pragmatica.aether.testkit.fake.InMemoryPgSqlConnector;
import org.pragmatica.aether.testkit.fixture.OrderIntake;
import org.pragmatica.aether.testkit.fixture.OrderIntake.OrderPlaced;
import org.pragmatica.aether.testkit.fixture.OrderIntake.PlaceRequest;
import org.pragmatica.aether.testkit.fixture.OrderIntakeFactory;
import org.pragmatica.lang.io.TimeSpan;
import org.testcontainers.DockerClientFactory;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/// End-to-end acceptance for the kit: spin the [OrderIntake] slice through its REAL generated
/// `OrderIntakeFactory`, drive it via the typed client, and assert on a captured fact, an outbound
/// HTTP call, and DB statements/rows — with no forge archive and no cluster.
///
///   - `place_persistsOrder_reservesStock_andEmitsFact` — all fakes (hard gate; Docker-free).
///   - `place_persistsOrder_throughRealPostgres` — `@PgSql` via testcontainer Postgres + real
///     migrations (guarded on Docker availability; skipped, not failed, when absent).
class OrderIntakeSliceTest {
    private static final TimeSpan TIMEOUT = TimeSpan.timeSpan(30).seconds();

    @Test
    void place_persistsOrder_reservesStock_andEmitsFact() {
        var inventory = FakeHttpClient.scripted()
                                      .onGet("/stock/ABC", HttpResults.ok("{\"available\":5}"));
        var events = CapturingPublisher.<OrderPlaced>capturing();
        var store = InMemoryPgSqlConnector.scripted()
                                          .onRow("INSERT INTO orders", Map.of("id", 42L));

        try (var sut = SliceTestKit.forSlice(OrderIntakeFactory::orderIntake)
                                   .withResource(PgSqlConnector.class, "database", store)
                                   .withHttp("http", inventory)
                                   .withPublisher("order-events", events)
                                   .build()) {
            var response = sut.client()
                              .place(new PlaceRequest("ABC", 2))
                              .await(TIMEOUT)
                              .unwrap();

            assertThat(response.status()).isEqualTo("ACCEPTED");
            assertThat(response.orderId()).isEqualTo(42L);

            assertThat(sut.<OrderPlaced>published("order-events")).singleElement()
                                                                  .satisfies(event -> assertThat(event.sku()).isEqualTo("ABC"));

            assertThat(sut.httpCalls("http")).anyMatch(call -> call.path().equals("/stock/ABC"));

            assertThat(store.statements()).anyMatch(statement -> statement.sql().contains("INSERT INTO orders"));
        }
    }

    @Test
    void place_persistsOrder_throughRealPostgres() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker/Colima not available");

        var inventory = FakeHttpClient.scripted()
                                      .onGet("/stock/XYZ", HttpResults.ok("{\"available\":9}"));
        var events = CapturingPublisher.<OrderPlaced>capturing();

        try (var sut = SliceTestKit.forSlice(OrderIntakeFactory::orderIntake)
                                   .withContainer(PgSqlConnector.class, "database", Containers.postgres()
                                                                                             .withSchemaFrom("schema"))
                                   .withHttp("http", inventory)
                                   .withPublisher("order-events", events)
                                   .build()) {
            var response = sut.client()
                              .place(new PlaceRequest("XYZ", 3))
                              .await(TIMEOUT)
                              .unwrap();

            assertThat(response.status()).isEqualTo("ACCEPTED");

            assertThat(sut.<OrderPlaced>published("order-events")).singleElement()
                                                                  .satisfies(event -> assertThat(event.qty()).isEqualTo(3));

            assertThat(sut.httpCalls("http")).anyMatch(call -> call.path().equals("/stock/XYZ"));

            assertThat(sut.db("database")
                          .query("SELECT sku, qty FROM orders")
                          .map(row -> row.string("sku"))).containsExactly("XYZ");

            assertThat(sut.db("database")
                          .query("SELECT sku, qty FROM orders")
                          .anyMatch(row -> row.string("sku").equals("XYZ") && row.integer("qty") == 3)).isTrue();
        }
    }
}
