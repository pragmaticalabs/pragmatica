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

package org.pragmatica.postgres;

import org.pragmatica.postgres.net.Connectible;
import org.pragmatica.postgres.net.Connection;
import org.pragmatica.postgres.net.SqlException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.function.Consumer;

import static org.pragmatica.postgres.DatabaseExtension.block;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for validated connections.
 *
 * @author Marat Gainullin
 */
@Tag("Slow")
public class ValidatedConnectionTest {

    @RegisterExtension
    static final DatabaseExtension dbr = DatabaseExtension.defaultConfiguration();

    private void withSource(Connectible source, Consumer<Connectible> action) {
        try {
            action.accept(source);
        } finally {
            block(source.close());
        }
    }

    private void withPlain(String clause, Consumer<Connectible> action) {
        withSource(dbr.builder
                        .validationQuery(clause)
                        .plain(),
                action
        );
    }

    private void withPool(String clause, Consumer<Connectible> action) {
        withSource(dbr.builder
                        .validationQuery(clause)
                        .pool(),
                action
        );
    }

    @Test
    public void shouldReturnValidPlainConnection() {
        withPlain("Select 89", plain -> {
            Connection conn = block(plain.getConnection());
            block(conn.close());
        });
    }

    @Test
    public void shouldNotReturnInvalidPlainConnection() {
        assertThrows(SqlException.class, () -> withPlain("Selec t 89", plain -> block(plain.getConnection())));
    }

    @Test
    public void shouldReturnValidPooledConnection() {
        withPool("Select 89", source -> {
            Connection conn = block(source.getConnection());
            block(conn.close());
        });
    }

    @Test
    public void shouldNotReturnInvalidPooledConnection() {
        assertThrows(SqlException.class, () -> withPool("Selec t 89", source -> block(source.getConnection())));
    }
}
