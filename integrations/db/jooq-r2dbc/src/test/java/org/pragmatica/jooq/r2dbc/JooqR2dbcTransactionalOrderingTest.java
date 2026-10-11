package org.pragmatica.jooq.r2dbc;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.r2dbc.R2dbcError;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import static org.assertj.core.api.Assertions.assertThat;

/// #1313 — the same defect as `JdbcTransactional`: rollback and close were independent `onFailure` / `onResult`
/// observers, each blocking on `await()`, so nothing ordered the close after the rollback and the returned
/// Promise settled without waiting for either. Every call here is a publisher the test controls, so the order
/// is observable and a step can be held.
class JooqR2dbcTransactionalOrderingTest {
    private static final Cause OPERATION_FAILED = Causes.cause("operation failed");

    private final List<String> events = new CopyOnWriteArrayList<>();
    private final AtomicReference<Held> held = new AtomicReference<>();

    @Test
    void failedOperation_rollsBackStrictlyBeforeClosing_andKeepsThePrimaryFailure() {
        var settled = run(Promise.failure(OPERATION_FAILED), Behaviour.none()).await();

        assertThat(settled.isFailure()).isTrue();
        settled.onFailure(cause -> assertThat(cause).isSameAs(OPERATION_FAILED));
        assertThat(events).containsExactly("begin", "rollback", "close");
    }

    /// While the rollback is held the returned Promise is pending and the close has not started.
    @Test
    void heldRollback_keepsThePromisePending_andTheCloseDoesNotStart() {
        var returned = run(Promise.failure(OPERATION_FAILED), Behaviour.holding("rollback"));

        awaitEvent("rollback");
        assertThat(returned.isResolved()).as("pending while the rollback is held").isFalse();
        assertThat(events).as("the close must wait for the rollback").containsExactly("begin", "rollback");

        held.get().release();

        assertThat(returned.await().isFailure()).isTrue();
        assertThat(events).containsExactly("begin", "rollback", "close");
    }

    @Test
    void heldClose_afterACommit_keepsThePromisePending() {
        var returned = run(Promise.success("done"), Behaviour.holding("close"));

        awaitEvent("close");
        assertThat(returned.isResolved()).as("pending until the connection is closed").isFalse();
        assertThat(events).containsExactly("begin", "commit", "close");

        held.get().release();

        assertThat(returned.await().isSuccess()).isTrue();
    }

    @Test
    void failedRollback_keepsThePrimaryFailure_andStillCloses() {
        var settled = run(Promise.failure(OPERATION_FAILED), Behaviour.failing("rollback")).await();

        settled.onFailure(cause -> assertThat(cause).as("a rollback failure must not replace the primary").isSameAs(OPERATION_FAILED));
        assertThat(settled.isFailure()).isTrue();
        assertThat(events).containsExactly("begin", "rollback", "close");
    }

    @Test
    void failedRollbackAndFailedClose_keepThePrimaryFailure() {
        var settled = run(Promise.failure(OPERATION_FAILED), Behaviour.failing("rollback", "close")).await();

        settled.onFailure(cause -> assertThat(cause).as("neither cleanup failure may replace the primary").isSameAs(OPERATION_FAILED));
        assertThat(settled.isFailure()).isTrue();
        assertThat(events).containsExactly("begin", "rollback", "close");
    }

    /// A driver that refuses the rollback by THROWING (not by a failing publisher) must not strand the caller:
    /// the returned Promise still settles with the primary failure, and the connection is still closed.
    @Test
    void rollbackThatThrows_stillSettlesWithThePrimary_andCloses() {
        var settled = run(Promise.failure(OPERATION_FAILED), Behaviour.throwing("rollback")).await(TimeSpan.timeSpan(5).seconds());

        settled.onFailure(cause -> assertThat(cause).as("settled with the primary, not a timeout").isSameAs(OPERATION_FAILED));
        assertThat(settled.isFailure()).isTrue();
        assertThat(events).containsExactly("begin", "rollback", "close");
    }

    /// The commit path has the same shape as the cleanup path: a driver that THROWS from a transaction call
    /// instead of returning a failing publisher must still end in a settled Promise with the connection
    /// released, never a hang.
    @Test
    void commitThatThrows_settlesAsAFailure_rollsBack_andCloses() {
        var settled = run(Promise.success("done"), Behaviour.throwing("commit")).await(TimeSpan.timeSpan(5).seconds());

        assertThat(settled.isFailure()).as("a throwing commit must settle as a failure, not hang").isTrue();
        assertThat(events).containsExactly("begin", "commit", "rollback", "close");
    }

    @Test
    void beginThatThrows_settlesAsAFailure_andCloses() {
        var settled = run(Promise.success("never reached"), Behaviour.throwing("begin")).await(TimeSpan.timeSpan(5).seconds());

        assertThat(settled.isFailure()).as("a throwing begin must settle as a failure, not hang").isTrue();
        assertThat(events).contains("begin", "close");
    }

    @Test
    void operationThatThrowsSynchronously_settlesAsAFailure_rollsBack_andCloses() {
        var settled = JooqR2dbcTransactional.<String> withTransaction(factory(Behaviour.none()),
                                                                      SQLDialect.DEFAULT,
                                                                      R2dbcError::fromException,
                                                                      (_, _) -> {
                                                                          throw new IllegalStateException("boom");
                                                                      }).await(TimeSpan.timeSpan(5).seconds());

        assertThat(settled.isFailure()).as("a throwing operation must settle as a failure, not hang").isTrue();
        assertThat(events).containsExactly("begin", "rollback", "close");
    }

    /// The chain starts on the promise executor, so a held step is awaited before anything is asserted about it.
    private void awaitEvent(String name) {
        var deadline = System.currentTimeMillis() + 5_000;

        while (!events.contains(name) && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }

        assertThat(events).as("the step must have started").contains(name);
    }

    private Promise<String> run(Promise<String> operation, Behaviour behaviour) {
        return JooqR2dbcTransactional.withTransaction(factory(behaviour),
                                                       SQLDialect.DEFAULT,
                                                       R2dbcError::fromException,
                                                       (_, _) -> operation);
    }

    private ConnectionFactory factory(Behaviour behaviour) {
        return (ConnectionFactory) Proxy.newProxyInstance(ConnectionFactory.class.getClassLoader(),
                                                          new Class<?>[]{ConnectionFactory.class},
                                                          (_, method, _) -> {
                                                              if (method.getName().equals("create")) {
                                                                  return single(connection(behaviour));
                                                              }

                                                              throw new UnsupportedOperationException(method.getName());
                                                          });
    }

    private Connection connection(Behaviour behaviour) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                                                   new Class<?>[]{Connection.class},
                                                   (_, method, _) -> switch (method.getName()) {
                                                       case "beginTransaction" -> step("begin", behaviour);
                                                       case "commitTransaction" -> step("commit", behaviour);
                                                       case "rollbackTransaction" -> step("rollback", behaviour);
                                                       case "close" -> step("close", behaviour);
                                                       default -> throw new UnsupportedOperationException(method.getName());
                                                   });
    }

    /// A step the behaviour throws from records itself and throws before any publisher exists.
    private Publisher<Void> step(String name, Behaviour behaviour) {
        if (behaviour.throwsOn(name)) {
            events.add(name);

            throw new IllegalStateException(name + " threw");
        }

        return publisherStep(name, behaviour);
    }

    /// Recorded when subscribed to, completed at once unless the behaviour holds or fails it.
    private Publisher<Void> publisherStep(String name, Behaviour behaviour) {
        return subscriber -> {
            events.add(name);
            subscriber.onSubscribe(new Subscription() {
                @Override
                public void request(long n) {
                    if (behaviour.fails(name)) {
                        subscriber.onError(new IllegalStateException(name + " refused"));
                    } else if (behaviour.holds(name)) {
                        held.set(new Held(subscriber));
                    } else {
                        subscriber.onComplete();
                    }
                }

                @Override
                public void cancel() {}
            });
        };
    }

    private static Publisher<Connection> single(Connection value) {
        return subscriber -> subscriber.onSubscribe(new Subscription() {
            @Override
            public void request(long n) {
                subscriber.onNext(value);
                subscriber.onComplete();
            }

            @Override
            public void cancel() {}
        });
    }

    private record Held(Subscriber<? super Void> subscriber) {
        void release() {
            subscriber.onComplete();
        }
    }

    private record Behaviour(String held, Set<String> failing, String throwing) {
        static Behaviour none() {
            return new Behaviour("", Set.of(), "");
        }

        static Behaviour holding(String step) {
            return new Behaviour(step, Set.of(), "");
        }

        static Behaviour failing(String... steps) {
            return new Behaviour("", Set.of(steps), "");
        }

        static Behaviour throwing(String step) {
            return new Behaviour("", Set.of(), step);
        }

        boolean holds(String step) {
            return held.equals(step);
        }

        boolean fails(String step) {
            return failing.contains(step);
        }

        boolean throwsOn(String step) {
            return throwing.equals(step);
        }
    }
}
