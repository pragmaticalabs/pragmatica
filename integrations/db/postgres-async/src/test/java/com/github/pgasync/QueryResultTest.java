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

import com.github.pgasync.net.ResultSet;
import com.github.pgasync.net.SqlException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for result set row counts.
 *
 * @author Antti Laisi
 */
@Tag("Slow")
public class QueryResultTest {

    @RegisterExtension
    static final DatabaseExtension dbr = DatabaseExtension.defaultConfiguration();

    @BeforeAll
    public static void create() {
        drop();
        dbr.query("CREATE TABLE CONN_TEST(ID INT8)");
    }

    @AfterAll
    public static void drop() {
        dbr.query("DROP TABLE IF EXISTS CONN_TEST");
    }

    @Test
    public void shouldReturnResultSetSize() {
        assertEquals(2, dbr.query("INSERT INTO CONN_TEST (ID) VALUES (1),(2)").affectedRows());
        ResultSet result = dbr.query("SELECT * FROM CONN_TEST WHERE ID <= 2 ORDER BY ID");
        assertEquals(2, result.size());
        assertEquals("ID", result.orderedColumns().getFirst().name().toUpperCase());

        Iterator<PgRow> i = result.iterator();
        assertEquals(1L, i.next().getLong(0).longValue());
        assertEquals(2L, i.next().getLong(0).longValue());
    }

    @Test
    public void shouldReturnDeletedRowsCount() {
        assertEquals(1, dbr.query("INSERT INTO CONN_TEST (ID) VALUES (3)").affectedRows());
        assertEquals(1, dbr.query("DELETE FROM CONN_TEST WHERE ID = 3").affectedRows());
    }

    @Test
    public void shouldReturnUpdatedRowsCount() {
        assertEquals(3, dbr.query("INSERT INTO CONN_TEST (ID) VALUES (9),(10),(11)").affectedRows());
        assertEquals(3, dbr.query("UPDATE CONN_TEST SET ID = NULL WHERE ID IN (9,10,11)").affectedRows());
    }

    @Test
    public void shouldParseDoStatement() {
        dbr.script(
            """
            create table locks
            (
                id bigint not null,
                name character varying(255),
                constraint locks_pkey primary key (id)
            );
            create type item as
            (
            \tid bigint,
            \tname character varying(255)
            );
            create or replace procedure updateItems(vs item[])
            language plpgsql
            as $$
            declare
              r item;
            begin \s
              foreach r in array vs loop\s
                if r.id is not null then
                  raise notice 'id: %; name: %;', r.id, r.name;
                  update locks l set name = r.name where l.id = r.id;
                end if;
              end loop;
            end $$;
            """);
        dbr.query("call updateItems(array[(1, $1::character varying(255)), (2, $2::character varying(255)), (null, $3::character varying(255))]::item[]);",
                Arrays.asList("fn***1", "fn***2", null)
        );
    }

    @Test
    public void shouldInvokeErrorHandlerOnError() {
        assertThrows(SqlException.class, () -> {
            try {
                dbr.query("SELECT * FROM not_there");
            } catch (Exception ex) {
                DatabaseExtension.ifCause(ex, sqlException -> {
                    throw sqlException;
                }, () -> {
                    throw ex;
                });
            }
        });
    }

    @Test
    public void shouldStreamResultRows() {
        List<Integer> series = dbr.pool().completeQuery("select generate_series(1, 5)")
                .map(rs -> StreamSupport.stream(rs.spliterator(), false)
                                        .map(r -> r.getInt(0))
                                        .collect(Collectors.toList()))
                .await();

        assertEquals(List.of(1, 2, 3, 4, 5), series);
    }

}
