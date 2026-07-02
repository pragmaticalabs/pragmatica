// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.testkit;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.db.PgSqlConnector;
import org.pragmatica.aether.testkit.fake.CapturingPublisher;
import org.pragmatica.aether.testkit.fake.FakeHttpClient;
import org.pragmatica.aether.testkit.fake.InMemoryPgSqlConnector;
import org.pragmatica.aether.testkit.fixture.OrderIntake.OrderPlaced;
import org.pragmatica.aether.testkit.fixture.OrderIntakeFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/// Proves the fail-fast diagnostic (spec §7.1 MVP-6): a slice needing a resource with no fake or
/// container registered fails at `build()` with a clear, named error listing the missing coordinate.
class SliceTestKitDiagnosticsTest {
    @Test
    void build_failsFast_whenResourceUnregistered() {
        assertThatThrownBy(() -> SliceTestKit.forSlice(OrderIntakeFactory::orderIntake)
                                             .withHttp("http", FakeHttpClient.scripted())
                                             .withPublisher("order-events", CapturingPublisher.<OrderPlaced>capturing())
                                             .build())
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("No fake or container registered for")
            .hasMessageContaining("PgSqlConnector")
            .hasMessageContaining("database");
    }

    @Test
    void published_failsFast_whenTopicNotCaptured() {
        var store = InMemoryPgSqlConnector.scripted();

        try (var sut = SliceTestKit.forSlice(OrderIntakeFactory::orderIntake)
                                   .withResource(PgSqlConnector.class, "database", store)
                                   .withHttp("http", FakeHttpClient.scripted())
                                   .withPublisher("order-events", CapturingPublisher.<OrderPlaced>capturing())
                                   .build()) {
            assertThat(sut.<OrderPlaced>published("order-events")).isEmpty();

            assertThatThrownBy(() -> sut.published("wrong-topic")).isInstanceOf(AssertionError.class)
                                                                  .hasMessageContaining("wrong-topic");
        }
    }
}
