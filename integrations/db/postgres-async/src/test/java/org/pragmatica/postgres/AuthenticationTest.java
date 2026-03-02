package org.pragmatica.postgres;

import org.pragmatica.postgres.net.SqlException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.pragmatica.postgres.DatabaseExtension.block;
import static org.pragmatica.postgres.SqlError.ServerErrorInvalidAuthorizationSpecification;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("Slow")
public class AuthenticationTest {

    @RegisterExtension
    static final DatabaseExtension dbr = DatabaseExtension.defaultConfiguration();

    @Test
    public void shouldThrowExceptionOnInvalidCredentials() {
        assertThrows(SqlException.class, () -> {
            var pool = dbr.builder
                .password("_invalid_")
                .pool();
            try {
                block(pool.completeQuery("SELECT 1"));
            } catch (SqlException ex) {
                assertTrue(ex.error() instanceof ServerErrorInvalidAuthorizationSpecification);
                throw ex;
            } finally {
                block(pool.close());
            }
        });
    }

    @Test
    public void shouldGetResultOnValidCredentials() {
        var pool = dbr.builder
            .password(DatabaseExtension.postgres.getPassword())
            .pool();
        try {
            var rs = block(pool.completeQuery("SELECT 1"));
            assertEquals(1L, (long) rs.index(0).getInt(0));
        } finally {
            block(pool.close());
        }
    }

}
