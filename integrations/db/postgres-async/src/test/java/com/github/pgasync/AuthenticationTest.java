package com.github.pgasync;

import com.github.pgasync.net.SqlException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static com.github.pgasync.SqlError.ServerErrorInvalidAuthorizationSpecification;
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
                pool.completeQuery("SELECT 1")
                    .await();
            } catch (Exception ex) {
                DatabaseExtension.ifCause(ex,
                                     sqlException -> {
                                         assertTrue(sqlException.error() instanceof ServerErrorInvalidAuthorizationSpecification);
                                         throw sqlException;
                                     },
                                     () -> {
                                         throw ex;
                                     });
            } finally {
                pool.close()
                    .await();
            }
        });
    }

    @Test
    public void shouldGetResultOnValidCredentials() {
        var pool = dbr.builder
            .password(DatabaseExtension.postgres.getPassword())
            .pool();
        try {
            var rs = pool.completeQuery("SELECT 1").await();
            assertEquals(1L, (long) rs.index(0).getInt(0));
        } finally {
            pool.close()
                .await();
        }
    }

}
