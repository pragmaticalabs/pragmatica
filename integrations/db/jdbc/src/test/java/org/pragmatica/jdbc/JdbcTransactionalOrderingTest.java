package org.pragmatica.jdbc;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// #1313 — `withTransaction` attached rollback with `onFailure` and cleanup with `onResult`: independent
/// observers, so cleanup (auto-commit restoration, then close) could run BEFORE the rollback, and the returned
/// Promise settled without waiting for either. A restored auto-commit with a pending transaction commits it.
///
/// The connection here is a recording stand-in, so the ORDER of JDBC calls is observable and each step can be
/// held. Every assertion is about ordering and settlement, not about a particular implementation.
class JdbcTransactionalOrderingTest {
    private static final Cause OPERATION_FAILED = Causes.cause("operation failed");

    private final List<String> events = new CopyOnWriteArrayList<>();

    @Test
    void failedOperation_rollsBackStrictlyBeforeRestoringAutoCommitAndClosing() {
        var settled = run(Promise.failure(OPERATION_FAILED), new Behaviour()).await();

        assertThat(settled.isFailure()).isTrue();
        settled.onFailure(cause -> assertThat(cause).as("the primary failure is what the caller sees").isSameAs(OPERATION_FAILED));
        assertThat(events).containsExactly("setAutoCommit(false)", "rollback", "setAutoCommit(true)", "close");
    }

    /// The returned Promise must wait for the cleanup it depends on: while the rollback is held, it is pending
    /// and nothing after the rollback has run.
    @Test
    void heldRollback_keepsThePromisePending_andNothingFollowsIt() throws Exception {
        var behaviour = new Behaviour().holdingRollback();
        var operation = Promise.<String> promise();
        var returned = run(operation, behaviour);

        failFromAnotherThread(operation);
        assertThat(behaviour.rollbackEntered.await(5, TimeUnit.SECONDS)).as("the rollback must start").isTrue();
        assertThat(returned.isResolved()).as("the promise must stay pending while the rollback is held").isFalse();
        assertThat(events).as("nothing may follow a held rollback").containsExactly("setAutoCommit(false)", "rollback");

        behaviour.releaseRollback.countDown();
        returned.await();

        assertThat(events).containsExactly("setAutoCommit(false)", "rollback", "setAutoCommit(true)", "close");
    }

    @Test
    void heldClose_afterACommit_keepsThePromisePending() throws Exception {
        var behaviour = new Behaviour().holdingClose();
        var operation = Promise.<String> promise();
        var returned = run(operation, behaviour);

        succeedFromAnotherThread(operation);
        assertThat(behaviour.closeEntered.await(5, TimeUnit.SECONDS)).as("the close must start").isTrue();
        assertThat(returned.isResolved()).as("the promise must stay pending until the connection is released").isFalse();
        assertThat(events).containsExactly("setAutoCommit(false)", "commit", "setAutoCommit(true)", "close");

        behaviour.releaseClose.countDown();

        assertThat(returned.await().isSuccess()).isTrue();
    }

    @Test
    void failedCommit_rollsBack_thenReleases_andReportsTheCommitFailure() {
        var behaviour = new Behaviour().failing("commit");
        var settled = run(Promise.success("done"), behaviour).await();

        assertThat(settled.isFailure()).isTrue();
        assertThat(events).containsExactly("setAutoCommit(false)", "commit", "rollback", "setAutoCommit(true)", "close");
    }

    @Test
    void failedRollback_keepsThePrimaryFailure_andStillReleasesTheConnection() {
        var behaviour = new Behaviour().failing("rollback");
        var settled = run(Promise.failure(OPERATION_FAILED), behaviour).await();

        settled.onFailure(cause -> assertThat(cause).as("a rollback failure must not replace the primary").isSameAs(OPERATION_FAILED));
        assertThat(settled.isFailure()).isTrue();
        assertThat(events).containsExactly("setAutoCommit(false)", "rollback", "setAutoCommit(true)", "close");
    }

    @Test
    void failedClose_afterACommit_doesNotTurnCommittedWorkIntoAFailure() {
        var behaviour = new Behaviour().failing("close");
        var settled = run(Promise.success("done"), behaviour).await();

        assertThat(settled.isSuccess()).as("the work was committed").isTrue();
        assertThat(events).containsExactly("setAutoCommit(false)", "commit", "setAutoCommit(true)", "close");
    }

    @Test
    void synchronousThrowFromTheOperation_isAFailure_withRollbackThenRelease() {
        var settled = JdbcTransactional.<String> withTransaction(dataSource(new Behaviour()),
                                                                 _ -> {
                                                                     throw new IllegalStateException("boom");
                                                                 }).await();

        assertThat(settled.isFailure()).isTrue();
        assertThat(events).containsExactly("setAutoCommit(false)", "rollback", "setAutoCommit(true)", "close");
    }

    /// A connection that cannot be configured is released, with no rollback: nothing ran on it.
    @Test
    void failedConfiguration_closesTheConnection_withoutRollback() {
        var behaviour = new Behaviour().failing("setAutoCommit(false)");
        var settled = run(Promise.success("never reached"), behaviour).await();

        assertThat(settled.isFailure()).isTrue();
        assertThat(events).doesNotContain("rollback", "commit").contains("close");
    }

    /// The consequence the ordering exists for, against a real transactional database (H2, a database of its own
    /// so nothing else touches it): write, then fail; a SEPARATE connection must see no committed row.
    @Test
    void writeThenFail_againstARealDatabase_commitsNothing() throws SQLException {
        var ds = new org.h2.jdbcx.JdbcDataSource();

        ds.setURL("jdbc:h2:mem:jdbc-transactional-1313;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        try (var conn = ds.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS audit (id INT PRIMARY KEY)");
            stmt.execute("DELETE FROM audit");
        }

        var settled = JdbcTransactional.<String> withTransaction(ds,
                                                                 conn -> Promise.lift(JdbcError::fromException,
                                                                                      () -> {
                                                                                          conn.createStatement().execute("INSERT INTO audit VALUES (1)");

                                                                                          return "written";
                                                                                      })
                                                                               .flatMap(_ -> Promise.<String> failure(OPERATION_FAILED))).await();

        assertThat(settled.isFailure()).isTrue();
        try (var other = ds.getConnection(); var rows = other.createStatement().executeQuery("SELECT COUNT(*) FROM audit")) {
            rows.next();
            assertThat(rows.getInt(1)).as("a separate connection must observe no committed row").isZero();
        }
    }

    private Promise<String> run(Promise<String> operation, Behaviour behaviour) {
        return JdbcTransactional.withTransaction(dataSource(behaviour), _ -> operation);
    }

    private static void failFromAnotherThread(Promise<String> operation) {
        Thread.ofVirtual().start(() -> operation.fail(OPERATION_FAILED));
    }

    private static void succeedFromAnotherThread(Promise<String> operation) {
        Thread.ofVirtual().start(() -> operation.succeed("done"));
    }

    private DataSource dataSource(Behaviour behaviour) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                                                   new Class<?>[]{DataSource.class},
                                                   (_, method, _) -> {
                                                       if (method.getName().equals("getConnection")) {
                                                           return connection(behaviour);
                                                       }

                                                       throw new UnsupportedOperationException(method.getName());
                                                   });
    }

    private Connection connection(Behaviour behaviour) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                                                   new Class<?>[]{Connection.class},
                                                   (_, method, args) -> {
                                                       var name = method.getName().equals("setAutoCommit")
                                                                  ? "setAutoCommit(" + args[0] + ")"
                                                                  : method.getName();

                                                       events.add(name);
                                                       behaviour.holdIfArmed(name);

                                                       if (behaviour.failing.contains(name)) {
                                                           throw new SQLException(name + " refused");
                                                       }

                                                       return null;
                                                   });
    }

    /// What the stand-in connection does: which calls fail, and which are held until the test releases them.
    private static final class Behaviour {
        final CountDownLatch rollbackEntered = new CountDownLatch(1);
        final CountDownLatch releaseRollback = new CountDownLatch(1);
        final CountDownLatch closeEntered = new CountDownLatch(1);
        final CountDownLatch releaseClose = new CountDownLatch(1);
        final List<String> failing = new CopyOnWriteArrayList<>();
        private boolean holdRollback;
        private boolean holdClose;

        Behaviour failing(String call) {
            failing.add(call);

            return this;
        }

        Behaviour holdingRollback() {
            holdRollback = true;

            return this;
        }

        Behaviour holdingClose() {
            holdClose = true;

            return this;
        }

        /// Called after the call has been recorded, so a held call is visible in the event list while it waits.
        void holdIfArmed(String name) throws InterruptedException {
            if (name.equals("rollback")) {
                rollbackEntered.countDown();
                if (holdRollback) {
                    releaseRollback.await(10, TimeUnit.SECONDS);
                }
            }

            if (name.equals("close")) {
                closeEntered.countDown();
                if (holdClose) {
                    releaseClose.await(10, TimeUnit.SECONDS);
                }
            }
        }
    }
}
