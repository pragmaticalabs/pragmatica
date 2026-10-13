// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// See LICENSE in the repository root for full terms.
package org.pragmatica.postgres;

import org.pragmatica.postgres.net.Connectible;
import org.pragmatica.postgres.net.Connection;
import org.pragmatica.postgres.net.SqlException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.pragmatica.postgres.DatabaseExtension.block;


/// #2181 against a real server: the SQLSTATE a backend sends must reach `ServerResponse.code()`,
/// and the prepared-statement recovery keyed on it (26000/42P05) must actually run.
@Tag("Slow")
class ServerErrorWireTest {
    private static final String SELECT_PARAM = "select $1::int4";

    @RegisterExtension
    static final DatabaseExtension dbr = DatabaseExtension.defaultConfiguration();

    @BeforeAll
    static void create() {
        drop();
        dbr.query("CREATE TABLE SQLSTATE_TEST(ID INT8 PRIMARY KEY)");
        dbr.query("INSERT INTO SQLSTATE_TEST(ID) VALUES (1)");
    }

    @AfterAll
    static void drop() {
        dbr.query("DROP TABLE IF EXISTS SQLSTATE_TEST");
    }

    @Test
    void uniqueViolation_carriesSqlstateAsCodeAndSeverityAsLevel() {
        var exception = assertThrows(SqlException.class, () -> dbr.query("INSERT INTO SQLSTATE_TEST(ID) VALUES (1)"));

        assertThat(exception.error()).isInstanceOf(SqlError.ServerErrorIntegrityConstraintViolation.class);
        assertThat(exception.error().response().code()).isEqualTo("23505");
        assertThat(exception.error().response().level()).isEqualTo("ERROR");
    }

    /// `DEALLOCATE ALL` drops the server side of a statement the client still caches, so the next
    /// execution fails with 26000; the recovery must evict, re-prepare and return the row.
    @Test
    void cachedStatementDeallocatedOnServer_isRecoveredByReprepare() {
        Connectible pool = dbr.builder.maxConnections(1)
                                      .maxStatements(5)
                                      .pool();

        try {
            Connection conn = block(pool.getConnection());

            try {
                assertThat(block(conn.completeQuery(SELECT_PARAM, 7)).index(0).getInt(0)).isEqualTo(7);
                block(conn.completeScript("DEALLOCATE ALL"));
                assertThat(block(conn.completeQuery(SELECT_PARAM, 8)).index(0).getInt(0)).isEqualTo(8);
            } finally {
                block(conn.close());
            }
        } finally {
            block(pool.close());
        }
    }
}
