package org.pragmatica.postgres;

import org.pragmatica.postgres.net.ResultSet;
import org.pragmatica.postgres.net.Transaction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

import static org.pragmatica.postgres.DatabaseExtension.block;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("Slow")
public class TransactionModePoolingTest {

    @RegisterExtension
    static final DatabaseExtension dbr = DatabaseExtension.transactionMode(5);

    @BeforeAll
    public static void create() {
        drop();
        dbr.script(
            "CREATE TABLE TXP_TEST (ID INT8 PRIMARY KEY);" +
            "CREATE TABLE TXP_MULTI (ID INT8 PRIMARY KEY, VALUE TEXT)"
        );
    }

    @AfterAll
    public static void drop() {
        dbr.script("DROP TABLE IF EXISTS TXP_TEST; DROP TABLE IF EXISTS TXP_MULTI");
    }

    @Test
    public void singleQuery_borrowsAndReturnsPhysical() {
        var rs = dbr.query("SELECT 42 AS answer");
        assertEquals(42L, rs.index(0).getLong(0).longValue());
    }

    @Test
    public void transaction_holdsPhysicalThroughCommit() {
        block(withinTransaction(tx ->
                              tx.completeQuery("INSERT INTO TXP_TEST(ID) VALUES(100)")
                                .flatMap(_ -> tx.completeQuery("INSERT INTO TXP_TEST(ID) VALUES(101)"))
                                .flatMap(_ -> tx.commit())));

        assertEquals(100L, dbr.query("SELECT ID FROM TXP_TEST WHERE ID = 100").index(0).getLong(0).longValue());
        assertEquals(101L, dbr.query("SELECT ID FROM TXP_TEST WHERE ID = 101").index(0).getLong(0).longValue());
    }

    @Test
    public void transaction_rollbackDiscardsChanges() {
        block(withinTransaction(tx ->
                              tx.completeQuery("INSERT INTO TXP_TEST(ID) VALUES(200)")
                                .flatMap(_ -> tx.rollback())));

        assertEquals(0L, dbr.query("SELECT ID FROM TXP_TEST WHERE ID = 200").size());
    }

    @Test
    public void nestedTransaction_savepointCommitAndRollback() {
        block(withinTransaction(tx ->
                              tx.completeQuery("INSERT INTO TXP_TEST(ID) VALUES(300)")
                                .flatMap(_ -> tx.begin()
                                                .flatMap(nested ->
                                                             nested.completeQuery("INSERT INTO TXP_TEST(ID) VALUES(301)")
                                                                   .flatMap(_ -> nested.commit()))
                                                .flatMap(_ -> tx.begin()
                                                                .flatMap(nested2 ->
                                                                             nested2.completeQuery("INSERT INTO TXP_TEST(ID) VALUES(302)")
                                                                                    .flatMap(_ -> nested2.rollback())))
                                                .flatMap(_ -> tx.commit()))));

        assertEquals(1L, dbr.query("SELECT ID FROM TXP_TEST WHERE ID = 300").size());
        assertEquals(1L, dbr.query("SELECT ID FROM TXP_TEST WHERE ID = 301").size());
        assertEquals(0L, dbr.query("SELECT ID FROM TXP_TEST WHERE ID = 302").size());
    }

    @Test
    public void multiplexing_100logicalOn5physical_allComplete() throws Exception {
        dbr.script("DELETE FROM TXP_MULTI");

        final int count = 100;
        IntFunction<Callable<ResultSet>> insert = value -> () ->
            dbr.query("INSERT INTO TXP_MULTI(ID, VALUE) VALUES($1, $2)", List.of(value, "val-" + value));

        List<Callable<ResultSet>> tasks = IntStream.range(0, count).mapToObj(insert).toList();

        try (ExecutorService executor = Executors.newFixedThreadPool(20)) {
            executor.invokeAll(tasks).forEach(TransactionModePoolingTest::await);
        }

        assertEquals(count, dbr.query("SELECT COUNT(*) FROM TXP_MULTI")
                                .index(0)
                                .getLong(0)
                                .longValue());
    }

    @Test
    public void poolExhaustion_pendingResolvedOnReturn() {
        dbr.script("DELETE FROM TXP_MULTI");

        var results = IntStream.range(0, 20)
                               .mapToObj(i -> dbr.pool().completeQuery("INSERT INTO TXP_MULTI(ID, VALUE) VALUES($1, $2)", i, "pending-" + i))
                               .toList();

        for (var p : results) {
            block(p);
        }

        assertEquals(20L, dbr.query("SELECT COUNT(*) FROM TXP_MULTI").index(0).getLong(0).longValue());
    }

    @Test
    public void concurrentQueries_separateLogicalConnections() {
        var p1 = dbr.pool().completeQuery("SELECT 1");
        var p2 = dbr.pool().completeQuery("SELECT 2");

        assertEquals(1L, block(p1).index(0).getLong(0).longValue());
        assertEquals(2L, block(p2).index(0).getLong(0).longValue());
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

    private static <T> void await(Future<T> future) {
        try {
            future.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
