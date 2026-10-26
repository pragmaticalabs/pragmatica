// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// See LICENSE in the repository root for full terms.
package org.pragmatica.postgres.r2dbc;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.pragmatica.lang.utils.SqlStates;
import org.pragmatica.postgres.PgProtocolStream;
import org.pragmatica.postgres.io.backend.ErrorResponseDecoder;
import org.pragmatica.postgres.message.backend.ErrorResponse;
import org.pragmatica.r2dbc.R2dbcError;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2408: the whole chain, with nothing hand-built. For each SQLSTATE a wire ErrorResponse is decoded and mapped by the
/// driver's own protocol stream into its `SqlError`, bridged to an R2DBC exception by the adapter's real
/// `R2dbcAdapterException.from`, and mapped to `R2dbcError` as the R2DBC connectors do; transience must agree with
/// `SqlStates` at every step and the SQLSTATE must survive.
class R2dbcAdapterTransienceParityTest {
    private static final String[] STATES = {"08001", "08003", "08004", "08006", "08007", "08P01", "40001", "40P01", "53200", "53300", "57P01", "57P02", "57P03", "57P04", "57014", "23505", "42601"};

    @Test
    void driverAdapterAndR2dbcError_agreeWithSqlStates_forEveryState() throws Exception {
        for (var state : STATES) {
            var sqlError = serverError(state);
            var exception = R2dbcAdapterException.from(sqlError);
            var r2dbcError = R2dbcError.fromException(exception, "SQL");
            var expected = SqlStates.isTransient(state);

            assertThat(sqlError.isTransient()).as("driver %s", state).isEqualTo(expected);
            assertThat(r2dbcError.isTransient()).as("R2dbcError %s via %s", state, exception.getClass().getSimpleName()).isEqualTo(expected);
            assertThat(exception.getSqlState()).as("sqlState %s", state).isEqualTo(state);
        }
    }

    private static org.pragmatica.postgres.SqlError serverError(String sqlState) throws Exception {
        Method mapper = PgProtocolStream.class.getDeclaredMethod("toSqlError", ErrorResponse.class);

        mapper.setAccessible(true);

        return (org.pragmatica.postgres.SqlError) mapper.invoke(null, decode(sqlState));
    }

    private static ErrorResponse decode(String sqlState) {
        var body = new ByteArrayOutputStream();

        field(body, 'S', "ERROR");
        field(body, 'V', "ERROR");
        field(body, 'C', sqlState);
        field(body, 'M', "parity probe");
        body.write(0);

        var bytes = body.toByteArray();

        return new ErrorResponseDecoder().read(ByteBuffer.wrap(bytes), bytes.length, StandardCharsets.UTF_8);
    }

    private static void field(ByteArrayOutputStream body, char type, String value) {
        body.write(type);
        body.writeBytes(value.getBytes(StandardCharsets.UTF_8));
        body.write(0);
    }
}
