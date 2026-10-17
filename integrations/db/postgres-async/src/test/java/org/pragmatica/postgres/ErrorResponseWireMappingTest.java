// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// See LICENSE in the repository root for full terms.
package org.pragmatica.postgres;

import org.pragmatica.postgres.io.backend.ErrorResponseDecoder;
import org.pragmatica.postgres.message.backend.ErrorResponse;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// #2181: `ErrorResponse.asServerResponse()` swapped SQLSTATE and severity, so every consumer of
/// `ServerResponse.code()` read `ERROR`/`FATAL`. `TransientClassificationTest` builds `ServerResponse`
/// directly and could not see it; these tests start from wire bytes decoded by the real
/// `ErrorResponseDecoder`, so the field order of the whole mapping is what they pin.
class ErrorResponseWireMappingTest {
    @Test
    void asServerResponse_carriesSqlstateAsCodeAndSeverityAsLevel_fromWire() {
        var response = decode("ERROR", "23505", "duplicate key value").asServerResponse();

        assertThat(response.code()).isEqualTo("23505");
        assertThat(response.level()).isEqualTo("ERROR");
        assertThat(response.message()).isEqualTo("duplicate key value");
    }

    @Test
    void serverErrorMessage_rendersSeverityThenSqlstate_fromWire() {
        var error = new SqlError.ServerErrorIntegrityConstraintViolation(decode("ERROR", "23505", "duplicate key value").asServerResponse(),
                                                                         "unique_violation");

        assertThat(error.message()).isEqualTo("ERROR: SQLSTATE=23505, MESSAGE=duplicate key value");
    }

    @Test
    void connectionException_isTransient_forTransientStateFromWire() {
        var error = new SqlError.ServerConnectionException(decode("FATAL", "08006", "connection failure").asServerResponse(),
                                                           "connection_failure");

        assertThat(error.isTransient()).isTrue();
    }

    @Test
    void operatorIntervention_isTransient_forTransientStateFromWire() {
        var error = new SqlError.ServerErrorOperatorIntervention(decode("FATAL", "57P01", "terminating connection due to administrator command").asServerResponse(),
                                                                 "admin_shutdown");

        assertThat(error.isTransient()).isTrue();
    }

    @Test
    void staleStatementPredicate_matchesStaleStatesFromWire_only() {
        var duplicate = new SqlError.ServerSyntaxErrorOrAccessRuleViolation(decode("ERROR", "42P05", "prepared statement \"s1\" already exists").asServerResponse(),
                                                                              "duplicate_prepared_statement");
        var missing = new SqlError.ServerErrorInvalidSQLStatementName(decode("ERROR", "26000", "prepared statement \"s1\" does not exist").asServerResponse(),
                                                                      "invalid_sql_statement_name");
        var unrelated = new SqlError.ServerErrorIntegrityConstraintViolation(decode("ERROR", "23505", "duplicate key value").asServerResponse(),
                                                                             "unique_violation");

        assertThat(PgConnection.isStaleStatementError(duplicate)).isTrue();
        assertThat(PgConnection.isStaleStatementError(missing)).isTrue();
        assertThat(PgConnection.isStaleStatementError(unrelated)).isFalse();
    }

    /// Encodes an ErrorResponse body as the backend sends it — `S` severity, `V` non-localized
    /// severity, `C` SQLSTATE, `M` message, then the terminating zero — and decodes it.
    private static ErrorResponse decode(String severity, String sqlstate, String message) {
        var body = new ByteArrayOutputStream();

        field(body, 'S', severity);
        field(body, 'V', severity);
        field(body, 'C', sqlstate);
        field(body, 'M', message);
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
