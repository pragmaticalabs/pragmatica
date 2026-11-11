// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import java.lang.reflect.InvocationHandler;
import java.util.Arrays;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// #3014: inside `PgSqlConnector.transactional` the callback receives a `PgSqlConnector`, so a generated `@PgSql`
/// adapter composes with the transaction without a user wrapper; commit and rollback stay the base connector's.
class PgSqlConnectorTransactionalTest {
    private final List<String> events = new ArrayList<>();

    @Test
    void callbackReceivesPgSqlConnector_boundToTheTransaction_andCommits() {
        var result = pool().transactional(connector -> usePgConnector(connector)).await();

        assertThat(result.isSuccess()).isTrue();
        assertThat(events).containsExactly("tx-update", "commit");
    }

    @Test
    void failingCallback_rollsBack() {
        var result = pool().transactional(connector -> usePgConnector(connector).flatMap(_ -> Causes.cause("boom").<Integer>promise()))
                           .await();

        assertThat(result.isFailure()).isTrue();
        assertThat(events).containsExactly("tx-update", "rollback");
    }

    @Test
    void plainTransactionalOverload_isUnchanged() {
        var result = ((SqlConnector) pool()).transactional(connector -> connector.update("UPDATE t SET a = 1")).await();

        assertThat(result.isSuccess()).isTrue();
        assertThat(events).containsExactly("tx-update", "commit");
    }

    @Test
    void scope_forwardsEveryMethodOfTheBaseConnectorToTheTransaction() {
        var seen = new ArrayList<String>();
        var plain = (SqlConnector) Proxy.newProxyInstance(getClass().getClassLoader(),
                                                          new Class<?>[]{SqlConnector.class},
                                                          (_, method, _) -> record(seen, method.getName()));
        var scope = PgSqlConnectorScope.pgSqlConnectorScope(plain);
        var expected = Arrays.stream(SqlConnector.class.getMethods())
                             .filter(method -> !java.lang.reflect.Modifier.isStatic(method.getModifiers()))
                             .toList();

        expected.forEach(method -> invokeWithDefaults(scope, method));

        assertThat(seen).containsAll(expected.stream().map(java.lang.reflect.Method::getName).toList());
    }

    @Test
    void scope_ofAPgSqlConnector_isThatConnector() {
        var pg = pool();

        assertThat(PgSqlConnectorScope.pgSqlConnectorScope(pg)).isSameAs(pg);
    }

    private static Object record(List<String> seen, String name) {
        seen.add(name);
        return null;
    }

    private static void invokeWithDefaults(Object target, java.lang.reflect.Method method) {
        try {
            method.invoke(target, Arrays.stream(method.getParameterTypes()).map(PgSqlConnectorTransactionalTest::defaultFor).toArray());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(method.getName(), e);
        }
    }

    private static Object defaultFor(Class<?> type) {
        return type == Object[].class
               ? new Object[0]
               : null;
    }

    /// Only compiles when `connector` is a `PgSqlConnector`: the static type is the proof of the overload.
    private static Promise<Integer> usePgConnector(PgSqlConnector connector) {
        return connector.update("UPDATE t SET a = 1");
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

    private PgSqlConnector pool() {
        InvocationHandler handler = (proxy, method, args) -> poolCall(proxy, method, args);

        return (PgSqlConnector) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PgSqlConnector.class}, handler);
    }

    private Object poolCall(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
        if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, args);
        }
        if ("transactional".equals(method.getName())) {
            return runInTransaction((SqlConnector.TransactionCallback<?>) args[0]);
        }
        return null;
    }

    private Promise<?> runInTransaction(SqlConnector.TransactionCallback<?> callback) {
        return callback.execute(transactionConnector())
                       .onSuccess(_ -> events.add("commit"))
                       .onFailure(_ -> events.add("rollback"));
    }
}
