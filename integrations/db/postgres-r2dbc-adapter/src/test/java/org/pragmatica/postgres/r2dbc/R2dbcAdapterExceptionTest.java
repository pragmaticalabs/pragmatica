// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// See LICENSE in the repository root for full terms.
package org.pragmatica.postgres.r2dbc;

import org.pragmatica.postgres.SqlError;
import org.pragmatica.postgres.net.SqlException;

import io.r2dbc.spi.R2dbcDataIntegrityViolationException;
import io.r2dbc.spi.R2dbcRollbackException;
import io.r2dbc.spi.R2dbcTransientException;
import io.r2dbc.spi.R2dbcTransientResourceException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2408: the adapter used to reduce every driver failure to `new R2dbcAdapterException(cause.message())`, so an R2DBC
/// consumer saw no SQLSTATE, could not tell a constraint violation from a lost connection, and the retry classification
/// the driver had made (#2181/#2182) was gone. The bridge now keeps the SQLSTATE, the classification by exception class,
/// and the original cause.
class R2dbcAdapterExceptionTest {
    private static SqlError.ServerResponse response(String code) {
        return new SqlError.ServerResponse(code, "ERROR", "driver message");
    }

    @Test
    void from_integrityViolation_isADataIntegrityViolationWithSqlState() {
        var cause = new SqlError.ServerErrorIntegrityConstraintViolation(response("23505"), "unique_violation");

        var exception = R2dbcAdapterException.from(cause);

        assertThat(exception).isInstanceOf(R2dbcDataIntegrityViolationException.class);
        assertThat(exception.getSqlState()).isEqualTo("23505");
        assertThat(exception).isNotInstanceOf(R2dbcTransientException.class);
    }

    @Test
    void from_transactionRollback_isARollbackWithSqlState() {
        var cause = new SqlError.ServerErrorTransactionRollback(response("40001"), "serialization_failure");

        var exception = R2dbcAdapterException.from(cause);

        assertThat(exception).isInstanceOf(R2dbcRollbackException.class);
        assertThat(exception.getSqlState()).isEqualTo("40001");
    }

    @Test
    void from_transientServerError_isTransientWithSqlState() {
        var cause = new SqlError.ServerErrorOperatorIntervention(response("57P01"), "admin_shutdown");

        var exception = R2dbcAdapterException.from(cause);

        assertThat(exception).isInstanceOf(R2dbcTransientResourceException.class);
        assertThat(exception.getSqlState()).isEqualTo("57P01");
    }

    @Test
    void from_nonTransientServerError_isNotTransientButKeepsSqlState() {
        var cause = new SqlError.ServerConnectionException(response("08007"), "transaction_resolution_unknown");

        var exception = R2dbcAdapterException.from(cause);

        assertThat(exception).isNotInstanceOf(R2dbcTransientException.class);
        assertThat(exception.getSqlState()).isEqualTo("08007");
    }

    @Test
    void from_transientDriverFailureWithoutSqlState_isTransient() {
        var exception = R2dbcAdapterException.from(new SqlError.ChannelClosed("closed"));

        assertThat(exception).isInstanceOf(R2dbcTransientResourceException.class);
        assertThat(exception.getSqlState()).isNull();
    }

    @Test
    void from_keepsTheOriginalCauseAsTheExceptionCause() {
        var cause = new SqlError.ServerErrorIntegrityConstraintViolation(response("23505"), "unique_violation");

        var exception = R2dbcAdapterException.from(cause);

        assertThat(exception.getCause()).isInstanceOfSatisfying(SqlException.class,
                                                                origin -> assertThat(origin.error()).isSameAs(cause));
        assertThat(exception.getMessage()).isEqualTo(cause.message());
    }
}
