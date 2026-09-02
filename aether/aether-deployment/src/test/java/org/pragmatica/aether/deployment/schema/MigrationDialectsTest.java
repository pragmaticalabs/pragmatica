// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.schema;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.pg.split.Dialects;
import org.pragmatica.aether.pg.split.Statement;
import org.pragmatica.aether.pg.split.StatementSplitter;
import org.pragmatica.aether.resource.db.DatabaseType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #409 wiring: `H2` resolves to a DDL-transactional [MigrationDialects.ExecutionDialect] backed by
/// the H2 [org.pragmatica.aether.pg.split.DialectSpec], replacing the legacy naive `split(";")`
/// fallback. `SQLITE` intentionally stays unmapped ([org.pragmatica.lang.Option#none()]) because its
/// `CREATE TRIGGER … BEGIN…END;` block needs a `BEGIN…END`-aware terminator the splitter does not yet
/// provide.
class MigrationDialectsTest {
    @Test
    void dialectFor_returnsDdlTransactionalH2Spec_forH2() {
        var dialect = MigrationDialects.dialectFor(DatabaseType.H2)
                                       .onEmpty(() -> fail("H2 should resolve to a dialect-aware execution descriptor"))
                                       .unwrap();

        assertThat(dialect.spec()).isSameAs(Dialects.H2);
        assertThat(dialect.ddlTransactional()).isTrue();
    }

    @Test
    void dialectFor_returnsNone_forSqlite() {
        assertThat(MigrationDialects.dialectFor(DatabaseType.SQLITE).isEmpty()).isTrue();
    }

    @Test
    void h2Spec_ignoresSemicolonInsideStringLiteral_whenSplitting() {
        var dialect = MigrationDialects.dialectFor(DatabaseType.H2)
                                       .onEmpty(() -> fail("H2 should resolve to a dialect-aware execution descriptor"))
                                       .unwrap();

        assertThat(splitTexts(dialect)).containsExactly("INSERT INTO t VALUES ('a;b')", " SELECT 2");
    }

    private static List<String> splitTexts(MigrationDialects.ExecutionDialect dialect) {
        return StatementSplitter.split("INSERT INTO t VALUES ('a;b'); SELECT 2;", dialect.spec())
                                .onFailure(cause -> fail("expected success but got: " + cause.message()))
                                .or(List.<Statement>of())
                                .stream()
                                .map(Statement::text)
                                .toList();
    }
}
