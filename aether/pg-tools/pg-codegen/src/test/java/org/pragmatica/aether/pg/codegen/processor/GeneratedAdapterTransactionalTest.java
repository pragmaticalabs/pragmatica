// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.pg.codegen.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.resource.db.PgSqlConnector;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// #3014 acceptance: a generated `@PgSql` adapter built over the connector a `PgSqlConnector.transactional` callback
/// receives runs its statements on the transaction connector, and the transaction commits or rolls back with the callback.
class GeneratedAdapterTransactionalTest {
    private static final String SOURCE = """
        package test;

        import org.pragmatica.aether.pg.codegen.annotation.Query;
        import org.pragmatica.aether.resource.db.PgSql;
        import org.pragmatica.lang.Promise;
        import org.pragmatica.lang.Unit;

        @PgSql
        public interface TxRepo {
            @Query("UPDATE orders SET status = :status WHERE id = :id")
            Promise<Unit> rename(long id, String status);
        }
        """;

    @TempDir
    Path tempDir;

    private final List<String> events = new ArrayList<>();

    @Test
    void generatedAdapter_runsOnTheTransactionConnector_andCommits() throws Exception {
        var factory = adapterFactory();
        var result = pool().transactional(tx -> rename(factory, tx)).await();

        assertThat(result.isSuccess()).isTrue();
        assertThat(events).containsExactly("tx-update", "commit");
    }

    @Test
    void generatedAdapter_failureAfterwards_rollsBack() throws Exception {
        var factory = adapterFactory();
        var result = pool().transactional(tx -> rename(factory, tx).flatMap(_ -> Causes.cause("boom").<Object>promise()))
                           .await();

        assertThat(result.isFailure()).isTrue();
        assertThat(events).containsExactly("tx-update", "rollback");
    }

    /// `tx` is statically a `PgSqlConnector`, and the generated factory method only accepts one.
    private static Promise<Object> rename(Class<?> factory, PgSqlConnector tx) {
        try {
            var repo = factory.getMethod("txRepo", PgSqlConnector.class).invoke(null, tx);
            var rename = repo.getClass().getMethod("rename", long.class, String.class);
            rename.setAccessible(true);

            return ((Promise<?>) rename.invoke(repo, 1L, "SHIPPED")).map(unit -> (Object) unit);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private Class<?> adapterFactory() throws Exception {
        var result = TestCompilationHelper.compileAndCompileGenerated(SOURCE, "test/TxRepo.java", tempDir);

        assertThat(result.success()).as(result.diagnostics()).isTrue();

        var loader = new URLClassLoader(new URL[]{tempDir.resolve("classes").toUri().toURL()}, getClass().getClassLoader());

        return loader.loadClass("test.TxRepoFactory");
    }

    private PgSqlConnector pool() {
        InvocationHandler handler = (proxy, method, args) -> poolCall(proxy, method, args);

        return (PgSqlConnector) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PgSqlConnector.class}, handler);
    }

    private Object poolCall(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, args);
        }
        if ("transactional".equals(method.getName())) {
            return ((SqlConnector.TransactionCallback<?>) args[0]).execute(transactionConnector())
                                                                  .onSuccess(_ -> events.add("commit"))
                                                                  .onFailure(_ -> events.add("rollback"));
        }
        return null;
    }

    private SqlConnector transactionConnector() {
        return (SqlConnector) Proxy.newProxyInstance(getClass().getClassLoader(),
                                                     new Class<?>[]{SqlConnector.class},
                                                     (_, method, _) -> recordUpdate(method.getName()));
    }

    private Object recordUpdate(String name) {
        if (!"update".equals(name)) {
            return null;
        }
        events.add("tx-update");
        return Promise.success(1);
    }
}
