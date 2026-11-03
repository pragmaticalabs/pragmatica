package org.pragmatica.postgres;

import org.pragmatica.postgres.net.Connection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FDB-1 (#2591): {@link Connection#abort()} destroys the physical connection instead of returning it to the pool.
 * The pool does no session reset (no DISCARD, no pg_advisory_unlock_all), so a connection holding session state, such
 * as an advisory lock after a failed unlock, must never be released: it is closed, the backend ends and takes the
 * state with it, and the pool forgets the slot and serves a waiting borrower with a fresh connection.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class ConnectionAbortTest {
    private static final String SELECT_PID = "SELECT pg_backend_pid()";

    @RegisterExtension
    static final DatabaseExtension dbr = DatabaseExtension.defaultConfiguration();

    private static int pid(Connection connection) {
        return connection.completeQuery(SELECT_PID).await().unwrap().index(0).getInt(0);
    }

    private static long backendsWithPid(Connection connection, int pid) {
        return connection.completeQuery("SELECT count(*) FROM pg_stat_activity WHERE pid = " + pid)
                         .await()
                         .unwrap()
                         .index(0)
                         .getLong(0);
    }

    @Test
    public void abort_poolOfOne_waitingBorrowerGetsAFreshBackend_andTheAbortedBackendIsGone() throws Exception {
        var first = dbr.pool().getConnection().await().unwrap();
        var abortedPid = pid(first);
        var waiting = dbr.pool().getConnection();

        assertFalse(waiting.isResolved(), "control: the single slot is taken, the borrower waits");

        first.abort().await().unwrap();
        var second = waiting.await().unwrap();

        assertNotEquals(abortedPid, pid(second), "the waiter was served by a replacement, not by the aborted connection");
        awaitGone(second, abortedPid);
        second.close().await().unwrap();
    }

    @Test
    public void abort_theConnectionIsNotReturnedToThePool_evenWhenClosedAfterwards() {
        var first = dbr.pool().getConnection().await().unwrap();
        var abortedPid = pid(first);

        first.abort().await().unwrap();
        first.close().await();
        var next = dbr.pool().getConnection().await().unwrap();

        assertNotEquals(abortedPid, pid(next), "a closed-after-abort connection must not re-enter the pool");
        assertTrue(next.isConnected());
        next.close().await().unwrap();
    }

    @Test
    public void close_control_returnsTheSameBackendToThePool() {
        var first = dbr.pool().getConnection().await().unwrap();
        var pid = pid(first);

        first.close().await().unwrap();
        var next = dbr.pool().getConnection().await().unwrap();

        assertEquals(pid, pid(next), "control: close() recycles the backend, abort() does not");
        next.close().await().unwrap();
    }

    private static void awaitGone(Connection observer, int pid) throws Exception {
        var deadline = System.nanoTime() + 20_000_000_000L;

        while (backendsWithPid(observer, pid) != 0 && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }

        assertEquals(0, backendsWithPid(observer, pid), "the aborted backend must end");
    }
}
