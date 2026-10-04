// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.testkit.container;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// Applying a migration script runs its statements as a LOOP: a statement whose update is already settled (an
/// in-process connector answers synchronously) is consumed in place, so the stack depth at a statement does not
/// grow with the script length. It used to nest 6 frames per statement through `flatMap` continuations (measured),
/// so a script of about 600 statements reached the depth that overflowed CI's 1 MB stack in #1392. The bound is
/// relative, 2,000 statements against 10. `applyFrom` is private, reached by reflection so the loop is measured alone.
class SchemaMigrationsDepthTest {
    private static final int SLACK_FRAMES = 60;

    @Test
    @Timeout(60)
    void stackDepthDoesNotGrowWithTheStatementCount_andEveryStatementRunsInOrder() throws Exception {
        var shallow = new RecordingConnector(false);
        var deep = new RecordingConnector(false);

        apply(shallow, 10).await().onFailure(cause -> fail(cause.message()));
        apply(deep, 2_000).await().onFailure(cause -> fail(cause.message()));

        assertThat(deep.statements).as("all statements, in order").containsExactlyElementsOf(statements(2_000));
        assertThat(deep.maxDepth()).as("max stack depth at a statement: 2,000 vs 10").isLessThanOrEqualTo(shallow.maxDepth() + SLACK_FRAMES);
    }

    /// The pending path: every update settles on another thread, so each statement resumes the loop from `onResult`.
    @Test
    @Timeout(60)
    void updatesThatSettleLater_runEveryStatementInOrder() throws Exception {
        var connector = new RecordingConnector(true);

        apply(connector, 200).await().onFailure(cause -> fail(cause.message()));

        assertThat(connector.statements).containsExactlyElementsOf(statements(200));
    }

    @Test
    @Timeout(30)
    void aFailedStatement_stopsTheScript() throws Exception {
        var connector = new RecordingConnector(false);

        connector.failAt = 5;

        apply(connector, 50).await().onSuccess(_ -> fail("a failed statement must fail the script"));

        assertThat(connector.statements).hasSize(6);
    }

    @Test
    @Timeout(30)
    void aConnectorThatThrows_failsTheScript_ratherThanHanging() throws Exception {
        var connector = new RecordingConnector(false);

        connector.throwAt = 3;

        apply(connector, 10).await().onSuccess(_ -> fail("a throwing connector must fail the script"));
    }

    private static Promise<Unit> apply(RecordingConnector connector, int count) throws Exception {
        Method applyFrom = SchemaMigrations.class.getDeclaredMethod("applyFrom", SqlConnector.class, List.class, int.class);

        applyFrom.setAccessible(true);

        try {
            @SuppressWarnings("unchecked")
            var result = (Promise<Unit>) applyFrom.invoke(null, connector.proxy(), statements(count), 0);

            return result;
        } catch (InvocationTargetException e) {
            throw new AssertionError("applyFrom threw instead of returning a promise", e.getCause());
        }
    }

    private static List<String> statements(int count) {
        var list = new ArrayList<String>();

        for (var i = 0; i < count; i++) {
            list.add("select " + i);
        }

        return list;
    }

    /// A `SqlConnector` that answers `update` and records the statement and the stack depth it ran at.
    private static final class RecordingConnector {
        final List<String> statements = new CopyOnWriteArrayList<>();
        final List<Integer> depths = new CopyOnWriteArrayList<>();
        final boolean settleLater;
        volatile int failAt = -1;
        volatile int throwAt = -1;

        RecordingConnector(boolean settleLater) {
            this.settleLater = settleLater;
        }

        int maxDepth() {
            return depths.stream().mapToInt(Integer::intValue).max().orElse(-1);
        }

        SqlConnector proxy() {
            return (SqlConnector) Proxy.newProxyInstance(SchemaMigrationsDepthTest.class.getClassLoader(),
                                                         new Class<?>[]{SqlConnector.class},
                                                         (_, method, args) -> {
                                                             if (!method.getName().equals("update")) {
                                                                 throw new UnsupportedOperationException(method.getName());
                                                             }

                                                             return update((String) args[0]);
                                                         });
        }

        private Promise<Integer> update(String statement) {
            var index = statements.size();

            statements.add(statement);
            depths.add((int) (long) StackWalker.getInstance().walk(frames -> frames.count()));

            if (index == throwAt) {
                throw new IllegalStateException("connector threw instead of returning a promise");
            }

            if (index == failAt) {
                return Causes.cause("statement " + index + " failed").promise();
            }

            if (!settleLater) {
                return Promise.success(1);
            }

            var promise = Promise.<Integer>promise();

            Thread.ofVirtual().start(() -> promise.succeed(1));

            return promise;
        }
    }
}
