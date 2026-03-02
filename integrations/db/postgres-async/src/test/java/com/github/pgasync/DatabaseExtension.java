package com.github.pgasync;

import com.github.pgasync.async.ThrowingPromise;
import com.github.pgasync.net.*;
import com.github.pgasync.net.netty.NettyConnectibleBuilder;
import org.junit.jupiter.api.extension.*;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * JUnit 5 extension for PostgreSQL integration tests.
 * <p>
 * Supports both static and instance-level {@code @RegisterExtension}:
 * <ul>
 *   <li>Static: container/pool created once via {@code beforeAll}, cleaned up via {@code afterAll}</li>
 *   <li>Instance: container/pool created per-test via {@code beforeEach}, cleaned up via {@code afterEach}</li>
 * </ul>
 *
 * @author Antti Laisi
 */
public class DatabaseExtension implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback {
    private static final int MAX_CAUSE_DEPTH = 100;
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
        "postgres:15-alpine"
    );

    final ConnectibleBuilder builder;
    Connectible pool;
    private boolean registeredAsStatic;

    private static ConnectibleBuilder defaultBuilder() {
        return new NettyConnectibleBuilder().ssl(false).encoding("utf-8");
    }

    private DatabaseExtension(final ConnectibleBuilder builder) {
        this.builder = builder;
    }

    public static DatabaseExtension defaultConfiguration() {
        return withMaxConnections(1);
    }

    public static DatabaseExtension withMaxConnections(int maxConnections) {
        return new DatabaseExtension(defaultBuilder().maxConnections(maxConnections));
    }

    public static <T> DatabaseExtension withConverter(Converter<T> converter) {
        return new DatabaseExtension(defaultBuilder().maxConnections(1).converters(converter));
    }

    public static void ifCause(Throwable th, Consumer<SqlException> action, CheckedRunnable others) throws Exception {
        int depth = 1;
        while (depth++ < MAX_CAUSE_DEPTH && th != null && !(th instanceof SqlException)) {
            th = th.getCause();
        }
        if (th instanceof SqlException sqlException) {
            action.accept(sqlException);
        } else {
            others.run();
        }
    }

    private void init() {
        if (pool == null) {
            postgres.start();
            pool = builder
                .port(postgres.getMappedPort(5432))
                .hostname(postgres.getHost())
                .password(postgres.getPassword())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .pool()
            ;
        }
    }

    private void cleanup() {
        if (pool != null) {
            try {
                pool.close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                pool = null;
                postgres.stop();
            }
        }
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        registeredAsStatic = true;
        init();
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        if (!registeredAsStatic) {
            init();
        }
    }

    @Override
    public void afterEach(ExtensionContext context) {
        if (!registeredAsStatic) {
            cleanup();
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        cleanup();
    }

    ResultSet query(String sql) {
        return block(pool().completeQuery(sql));
    }

    ResultSet query(String sql, List<?> params) {
        return block(pool().completeQuery(sql, params.toArray()));
    }

    Collection<PgResultSet> script(String sql) {
        return block(pool().completeScript(sql));
    }

    private <T> T block(ThrowingPromise<T> promise) {
        try {
            return promise.await();
        } catch (Throwable th) {
            throw new RuntimeException(th);
        }
    }

    Connectible pool() {
        init();
        return pool;
    }

    @FunctionalInterface
    public interface CheckedRunnable {

        void run() throws Exception;
    }
}
