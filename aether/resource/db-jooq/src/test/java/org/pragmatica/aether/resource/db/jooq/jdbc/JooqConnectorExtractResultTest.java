// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.jooq.jdbc;

import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.aether.resource.db.jooq.JooqConnector;

import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #2408: `extractSingleResult` is shared by every jOOQ connector's transactional `fetchOne`, which jOOQ cannot run over
/// R2DBC (blocking fetch), so the typed outcome is pinned here directly. It used to throw the typed cause through
/// `Result.unwrap()`, which reached the caller as `DatabaseFailure(IllegalStateException)`.
class JooqConnectorExtractResultTest {
    private static final Field<Integer> N = DSL.field("n", Integer.class);

    private static org.jooq.Result<org.jooq.Record1<Integer>> rows(int... values) {
        var context = DSL.using(SQLDialect.DEFAULT);
        var result = context.newResult(N);

        for (var value : values) {
            result.add(context.newRecord(N).values(value));
        }

        return result;
    }

    @Test
    void extractSingleResult_noRow_isTheTypedResultNotFound() {
        JooqConnector.extractSingleResult(rows())
                     .onSuccess(_ -> fail("must fail"))
                     .onFailure(cause -> assertThat(cause).isSameAs(DatabaseConnectorError.ResultNotFound.INSTANCE));
    }

    @Test
    void extractSingleResult_severalRows_isTheTypedMultipleResultsWithTheirCount() {
        JooqConnector.extractSingleResult(rows(1, 2, 3))
                     .onSuccess(_ -> fail("must fail"))
                     .onFailure(cause -> assertThat(cause).isEqualTo(DatabaseConnectorError.multipleResults(3)));
    }

    @Test
    void extractSingleResult_oneRow_isThatRow() {
        JooqConnector.extractSingleResult(rows(7))
                     .onFailure(cause -> fail("must succeed: " + cause.message()))
                     .onSuccess(row -> assertThat(row.value1()).isEqualTo(7));
    }
}
