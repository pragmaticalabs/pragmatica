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

import com.github.pgasync.net.SqlException;
import com.github.pgasync.net.Transaction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;

import static com.github.pgasync.DatabaseExtension.block;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for BEGIN/COMMIT/ROLLBACK.
 *
 * @author Antti Laisi
 */
@Tag("Slow")
public class TransactionTest {

    @RegisterExtension
    static final DatabaseExtension dbr = DatabaseExtension.defaultConfiguration();

    @BeforeAll
    public static void create() {
        drop();
        dbr.query("CREATE TABLE TX_TEST(ID INT8 PRIMARY KEY)");
    }

    @AfterAll
    public static void drop() {
        dbr.query("DROP TABLE IF EXISTS TX_TEST");
    }

    private static <T> Promise<T> withinTransaction(Fn1<Promise<T>, Transaction> fn) {
        return dbr.pool()
                  .getConnection()
                  .flatMap(connection ->
                               connection.begin()
                                         .flatMap(fn)
                                         .fold(result -> connection.close()
                                                                   .fold(_ -> Promise.resolved(result))));
    }

    @Test
    public void shouldCommitSelectInTransaction() {
        block(withinTransaction(transaction ->
                              transaction.completeQuery("SELECT 1")
                                         .withSuccess(rs -> assertEquals(1L, rs.index(0).getLong(0).longValue()))
                                         .flatMap(_ -> transaction.commit())));
    }

    @Test
    public void shouldCommitInsertInTransaction() {
        block(withinTransaction(transaction ->
                              transaction.completeQuery("INSERT INTO TX_TEST(ID) VALUES(10)")
                                         .withSuccess(rs -> assertEquals(1, rs.affectedRows()))
                                         .flatMap(_ -> transaction.commit())));

        assertEquals(10L, dbr.query("SELECT ID FROM TX_TEST WHERE ID = 10").index(0).getLong(0).longValue());
    }

    @Test
    public void shouldCommitParameterizedInsertInTransaction() {
        // Ref: https://github.com/alaisi/postgres-async-driver/issues/34
        long id = block(withinTransaction(transaction ->
                                        transaction.completeQuery("INSERT INTO TX_TEST (ID) VALUES ($1) RETURNING ID", 35)
                                                   .map(rs -> rs.index(0).getLong(0))
                                                   .flatMap(value -> transaction.commit().map(_ -> value))));
        assertEquals(35L, id);
    }

    @Test
    public void shouldRollbackTransaction() {
        block(withinTransaction(transaction ->
                              transaction.completeQuery("INSERT INTO TX_TEST(ID) VALUES(9)")
                                         .withSuccess(rs -> assertEquals(1, rs.affectedRows()))
                                         .flatMap(_ -> transaction.rollback())));

        assertEquals(0L, dbr.query("SELECT ID FROM TX_TEST WHERE ID = 9").size());
    }


    @Test
    public void shouldRollbackTransactionOnBackendError() {
        assertThrows(SqlException.class, () -> {
            try {
                block(withinTransaction(transaction ->
                                      transaction.completeQuery("INSERT INTO TX_TEST(ID) VALUES(11)")
                                                 .withSuccess(rs -> assertEquals(1, rs.affectedRows()))
                                                 .flatMap(_ -> transaction.completeQuery("INSERT INTO TX_TEST(ID) VALUES(11)"))));
            } catch (SqlException ex) {
                assertEquals(0, dbr.query("SELECT ID FROM TX_TEST WHERE ID = 11").size());
                throw ex;
            }
        });
    }

    @Test
    public void shouldRollbackTransactionAfterBackendError() {
        block(withinTransaction(transaction ->
                              transaction.completeQuery("INSERT INTO TX_TEST(ID) VALUES(22)")
                                         .withSuccess(rs -> assertEquals(1, rs.affectedRows()))
                                         .flatMap(_ -> transaction
                                             .completeQuery("INSERT INTO TX_TEST(ID) VALUES(22)")
                                             .fold(_ -> transaction.completeQuery("SELECT 1")))));
        assertEquals(0, dbr.query("SELECT ID FROM TX_TEST WHERE ID = 22").size());
    }

    @Test
    public void shouldSupportNestedTransactions() {
        block(withinTransaction(transaction ->
                              transaction.begin()
                                         .flatMap(nested ->
                                                      nested
                                                          .completeQuery("INSERT INTO TX_TEST(ID) VALUES(19)")
                                                          .withSuccess(rs -> assertEquals(1, rs.affectedRows()))
                                                          .flatMap(_ -> nested.commit())
                                                          .flatMap(_ -> transaction.commit()))));
        assertEquals(1L, dbr.query("SELECT ID FROM TX_TEST WHERE ID = 19").size());
    }

    @Test
    public void shouldRollbackNestedTransaction() {
        block(withinTransaction(transaction ->
                              transaction.completeQuery("INSERT INTO TX_TEST(ID) VALUES(24)")
                                         .withSuccess(rs -> assertEquals(1, rs.affectedRows()))
                                         .flatMap(_ -> transaction.begin()
                                                                  .flatMap(nested ->
                                                                               nested
                                                                                   .completeQuery("INSERT INTO TX_TEST(ID) VALUES(23)")
                                                                                   .withSuccess(rs -> assertEquals(1, rs.affectedRows()))
                                                                                   .flatMap(_ -> nested.rollback()))
                                                                  .flatMap(_ -> transaction.commit()))));
        assertEquals(1L, dbr.query("SELECT ID FROM TX_TEST WHERE ID = 24").size());
        assertEquals(0L, dbr.query("SELECT ID FROM TX_TEST WHERE ID = 23").size());
    }

    @Test
    public void shouldRollbackNestedTransactionOnBackendError() {
        block(withinTransaction(transaction ->
                              transaction.completeQuery("INSERT INTO TX_TEST(ID) VALUES(25)")
                                         .withSuccess(rs -> assertEquals(1, rs.affectedRows()))
                                         .flatMap(_ -> transaction.begin()
                                                                  .flatMap(nested ->
                                                                               nested
                                                                                   .completeQuery("INSERT INTO TX_TEST(ID) VALUES(26)")
                                                                                   .withSuccess(rs -> assertEquals(1, rs.affectedRows()))
                                                                                   .flatMap(_ -> nested.completeQuery(
                                                                                       "INSERT INTO TX_TEST(ID) VALUES(26)"))
                                                                                   .fold(_ -> transaction.commit())))));
        assertEquals(1L, dbr.query("SELECT ID FROM TX_TEST WHERE ID = 25").size());
        assertEquals(0L, dbr.query("SELECT ID FROM TX_TEST WHERE ID = 26").size());
    }
}
