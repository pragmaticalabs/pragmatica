// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// See LICENSE in the repository root for full terms.
package org.pragmatica.postgres;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.pragmatica.lang.utils.SqlStates;
import org.pragmatica.postgres.io.backend.ErrorResponseDecoder;
import org.pragmatica.postgres.message.backend.ErrorResponse;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2408: whether a SQLSTATE is worth a retry is decided by one classifier, `SqlStates`, for every database family.
/// This holds the driver's own `SqlError` to it for the states the PR's other families are checked on, using the errors
/// the protocol stream really produces for each code (its private mapper, reached by reflection, fed a decoded wire
/// ErrorResponse), so a state added to or removed from one side alone turns this red. `08003` is the state that used
/// to differ.
class SqlErrorSqlStatesParityTest {
    static final String[] STATES = {"08001", "08003", "08004", "08006", "08007", "08P01", "40001", "40P01", "53200", "53300", "57P01", "57P02", "57P03", "57P04", "57014", "23505", "42601"};

    @Test
    void sqlErrorTransience_agreesWithSqlStates_forEveryState() throws Exception {
        for (var state : STATES) {
            var error = serverError(state);

            assertThat(error.isTransient()).as("%s via %s", state, error.getClass().getSimpleName()).isEqualTo(SqlStates.isTransient(state));
            assertThat(error.response().code()).isEqualTo(state);
        }
    }

    @Test
    void connectionDoesNotExist_isTransient() throws Exception {
        assertThat(serverError("08003")).isInstanceOf(SqlError.ServerConnectionException.class);
        assertThat(serverError("08003").isTransient()).isTrue();
    }

    static SqlError.ServerError serverError(String sqlState) throws Exception {
        Method mapper = PgProtocolStream.class.getDeclaredMethod("toSqlError", ErrorResponse.class);

        mapper.setAccessible(true);

        return (SqlError.ServerError) mapper.invoke(null, decode(sqlState));
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
