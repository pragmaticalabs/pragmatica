// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// See LICENSE in the repository root for full terms.
package org.pragmatica.r2dbc;

import io.r2dbc.spi.R2dbcDataIntegrityViolationException;
import io.r2dbc.spi.R2dbcNonTransientResourceException;
import io.r2dbc.spi.R2dbcRollbackException;
import io.r2dbc.spi.R2dbcTransientResourceException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// #2408: the SQLSTATE a driver reports survives the mapping to [R2dbcError], including when a wrapping layer (jOOQ's
/// `DataAccessException`) holds the R2DBC exception as its cause, and the transience stays on the type.
class R2dbcErrorSqlStateTest {
    @Test
    void fromException_integrityViolation_keepsSqlState() {
        var error = R2dbcError.fromException(new R2dbcDataIntegrityViolationException("duplicate key", "23505"), "INSERT");

        assertThat(error).isInstanceOfSatisfying(R2dbcError.ConstraintViolation.class,
                                                 violation -> assertThat(violation.sqlState()).isEqualTo(some("23505")));
        assertThat(error.isTransient()).isFalse();
    }

    @Test
    void fromException_transientResource_isTransientAndKeepsSqlState() {
        var error = R2dbcError.fromException(new R2dbcTransientResourceException("admin shutdown", "57P01"), "SELECT");

        assertThat(error).isInstanceOfSatisfying(R2dbcError.ConnectionFailed.class,
                                                 failed -> assertThat(failed.sqlState()).isEqualTo(some("57P01")));
        assertThat(error.isTransient()).isTrue();
    }

    @Test
    void fromException_rollback_isTransientAndKeepsSqlState() {
        var error = R2dbcError.fromException(new R2dbcRollbackException("serialization failure", "40001"), "UPDATE");

        assertThat(error.isTransient()).isTrue();
        assertThat(error).isInstanceOfSatisfying(R2dbcError.ConnectionFailed.class,
                                                 failed -> assertThat(failed.sqlState()).isEqualTo(some("40001")));
    }

    @Test
    void fromException_nonTransientWithConnectionClass_isConnectionFailed() {
        var error = R2dbcError.fromException(new R2dbcNonTransientResourceException("connection does not exist", "08003"), "SELECT");

        assertThat(error).isInstanceOfSatisfying(R2dbcError.ConnectionFailed.class,
                                                 failed -> assertThat(failed.sqlState()).isEqualTo(some("08003")));
    }

    @Test
    void fromException_nonTransientOtherState_isQueryFailedKeepingSqlState() {
        var error = R2dbcError.fromException(new R2dbcNonTransientResourceException("syntax error", "42601"), "SELEC");

        assertThat(error).isInstanceOfSatisfying(R2dbcError.QueryFailed.class,
                                                 failed -> assertThat(failed.sqlState()).isEqualTo(some("42601")));
        assertThat(error.isTransient()).isFalse();
    }

    @Test
    void fromException_driverExceptionBehindAWrapper_isClassifiedByTheDriverException() {
        var wrapped = new IllegalStateException("jooq wrapper", new R2dbcDataIntegrityViolationException("duplicate key", "23505"));

        assertThat(R2dbcError.fromException(wrapped, "INSERT")).isInstanceOfSatisfying(R2dbcError.ConstraintViolation.class,
                                                                                       violation -> assertThat(violation.sqlState()).isEqualTo(some("23505")));
    }

    @Test
    void fromException_unretryableConnectionStates_areNotTransient() {
        for (var state : new String[]{"08007", "08P01"}) {
            var error = R2dbcError.fromException(new R2dbcNonTransientResourceException("unresolved", state), "COMMIT");

            assertThat(error).as(state).isInstanceOfSatisfying(R2dbcError.QueryFailed.class,
                                                               failed -> assertThat(failed.sqlState()).isEqualTo(some(state)));
            assertThat(error.isTransient()).as(state).isFalse();
        }
    }

    @Test
    void fromException_withoutQueryContext_aNonConnectionDriverFailureIsADatabaseFailureCarryingIt() {
        var failure = new R2dbcNonTransientResourceException("syntax error", "42601");

        assertThat(R2dbcError.fromException(failure)).isInstanceOfSatisfying(R2dbcError.DatabaseFailure.class,
                                                                             f -> assertThat(f.cause()).isSameAs(failure));
    }

    @Test
    void fromException_noSqlState_leavesItEmpty() {
        var error = R2dbcError.fromException(new R2dbcDataIntegrityViolationException("duplicate key"), "INSERT");

        assertThat(error).isInstanceOfSatisfying(R2dbcError.ConstraintViolation.class,
                                                 violation -> assertThat(violation.sqlState()).isEqualTo(none()));
    }

    @Test
    void fromException_notADriverException_isDatabaseFailureCarryingTheThrowable() {
        var failure = new IllegalArgumentException("boom");

        assertThat(R2dbcError.fromException(failure, "SELECT")).isInstanceOfSatisfying(R2dbcError.DatabaseFailure.class,
                                                                                       f -> assertThat(f.cause()).isSameAs(failure));
    }
}
