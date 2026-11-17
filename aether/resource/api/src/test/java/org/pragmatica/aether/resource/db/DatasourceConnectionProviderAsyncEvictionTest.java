// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// #2100 E-B N5a: eviction when the creation fails (or succeeds) LATER, on another thread. The synchronous-failure
/// tests cannot see these: an already-failed promise runs its handlers inline, a pending one does not.
class DatasourceConnectionProviderAsyncEvictionTest {
    private static final int ITERATIONS = 200;
    private static final Cause BOOM = Causes.cause("create boom");
    private static final TimeSpan DEADLINE = TimeSpan.timeSpan(10).seconds();

    /// The first creation returns `pending`, every later one succeeds at once.
    private static void firstPendingThenSucceeding(String name, Promise<SqlConnector> pending, AtomicInteger creations) {
        var first = new AtomicBoolean(true);

        ControlledSqlConnectorFactory.behave(name,
                                             () -> next(first, pending, creations));
    }

    private static Promise<SqlConnector> next(AtomicBoolean first, Promise<SqlConnector> pending, AtomicInteger creations) {
        creations.incrementAndGet();
        return first.getAndSet(false)
               ? pending
               : Promise.success(ControlledSqlConnectorFactory.stubConnector());
    }

    @Test
    void connector_creationFailsAsynchronously_aCallerResumingAfterTheFailureNeverReceivesIt() {
        var replays = new AtomicInteger();

        for (int i = 0; i < ITERATIONS; i++) {
            var name = "resume-" + i;
            var pending = Promise.<SqlConnector> promise();

            firstPendingThenSucceeding(name, pending, new AtomicInteger());

            var provider = ControlledSqlConnectorFactory.provider();
            var failed = provider.connector(name, name);

            pending.fail(BOOM);

            assertThat(failed.await(DEADLINE).isFailure()).as("iteration " + i + ": the failure reaches the caller").isTrue();
            // the caller resumes from the failed await and retries at once, before any asynchronous handler could have run
            if (provider.connector(name, name).await(DEADLINE).isFailure()) {
                replays.incrementAndGet();
            }
        }

        assertThat(replays.get()).as("retries that replayed the failure, of " + ITERATIONS).isZero();
    }

    @Test
    void connector_creationFailsAsynchronously_everyConcurrentCallerSeesTheFailureAndTheEntryIsGone() throws Exception {
        var pending = Promise.<SqlConnector> promise();
        var creations = new AtomicInteger();
        var provider = ControlledSqlConnectorFactory.provider();

        firstPendingThenSucceeding("fanout", pending, creations);

        var a = provider.connector("fanout", "fanout");
        var b = provider.connector("fanout", "fanout");

        assertThat(b).as("callers during the creation share it").isSameAs(a);
        assertThat(creations.get()).isEqualTo(1);

        pending.fail(BOOM);

        assertThat(a.await(DEADLINE).isFailure()).isTrue();
        assertThat(b.await(DEADLINE).isFailure()).isTrue();
        assertThat(ControlledSqlConnectorFactory.cacheOf(provider)).as("failed entry evicted").isEmpty();
    }

    @Test
    void connector_lateFailureOfAReleasedEntry_doesNotEvictTheNewerEntry() throws Exception {
        for (int i = 0; i < ITERATIONS; i++) {
            var name = "late-" + i;
            var pendingA = Promise.<SqlConnector> promise();
            var creations = new AtomicInteger();

            firstPendingThenSucceeding(name, pendingA, creations);

            var provider = ControlledSqlConnectorFactory.provider();
            var a = provider.connector(name, name);

            provider.release(name, name);

            var b = provider.connector(name, name);

            assertThat(b).isNotSameAs(a);

            pendingA.fail(BOOM);
            a.await(DEADLINE);

            assertThat(ControlledSqlConnectorFactory.cacheOf(provider).get(name)).as("iteration " + i + ": the newer entry survives the late failure").isSameAs(b);
            assertThat(creations.get()).isEqualTo(2);
        }
    }

    @Test
    void connector_creationSucceeds_theEntryIsNeverEvicted() throws Exception {
        var pending = Promise.<SqlConnector> promise();
        var creations = new AtomicInteger();
        var provider = ControlledSqlConnectorFactory.provider();

        firstPendingThenSucceeding("kept", pending, creations);

        var first = provider.connector("kept", "kept");

        pending.succeed(ControlledSqlConnectorFactory.stubConnector());

        assertThat(first.await(DEADLINE).isSuccess()).isTrue();
        assertThat(provider.connector("kept", "kept")).as("same cached promise").isSameAs(first);
        assertThat(ControlledSqlConnectorFactory.cacheOf(provider)).containsKey("kept");
        assertThat(creations.get()).isEqualTo(1);
    }
}
