/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.pgasync;

import com.github.pgasync.net.Connectible;
import com.github.pgasync.net.Connection;
import com.github.pgasync.net.SqlException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for validated connections.
 *
 * @author Marat Gainullin
 */
@Tag("Slow")
public class ValidatedConnectionTest {

    @RegisterExtension
    final DatabaseExtension dbr = DatabaseExtension.defaultConfiguration();

    private void withSource(Connectible source, Consumer<Connectible> action) throws Exception {
        try {
            action.accept(source);
        } catch (Exception ex) {
            DatabaseExtension.ifCause(ex,
                    sqlException -> {
                        throw sqlException;
                    },
                                 () -> {
                        throw ex;
                    });
        } finally {
            source.close().await();
        }
    }

    private void withPlain(String clause, Consumer<Connectible> action) throws Exception {
        withSource(dbr.builder
                        .validationQuery(clause)
                        .plain(),
                action
        );
    }

    private void withPool(String clause, Consumer<Connectible> action) throws Exception {
        withSource(dbr.builder
                        .validationQuery(clause)
                        .pool(),
                action
        );
    }

    @Test
    public void shouldReturnValidPlainConnection() throws Exception {
        withPlain("Select 89", plain -> {
            Connection conn = plain.getConnection().await();
            conn.close().await();
        });
    }

    @Test
    public void shouldNotReturnInvalidPlainConnection() {
        assertThrows(SqlException.class, () -> {
            withPlain("Selec t 89", plain -> plain.getConnection().await());
        });
    }

    @Test
    public void shouldReturnValidPooledConnection() throws Exception {
        withPool("Select 89", source -> {
            Connection conn = source.getConnection().await();
            conn.close().await();
        });
    }

    @Test
    public void shouldNotReturnInvalidPooledConnection() {
        assertThrows(SqlException.class, () -> {
            withPool("Selec t 89", source -> source.getConnection().await());
        });
    }
}
