// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.async;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.postgres.Oid;
import org.pragmatica.postgres.PgColumn;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.postgres.net.Connectible;
import org.pragmatica.postgres.net.Connection;
import org.pragmatica.postgres.net.Listening;
import org.pragmatica.postgres.net.PreparedStatement;
import org.pragmatica.postgres.net.Transaction;
import org.pragmatica.testing.PollUntil;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.db.DatabaseConnectorConfig.databaseConnectorConfigBuilder;


/// #3635: the Connectible of a notification listener holds the lease on the builder-owned event-loop group. It has to be
/// closed on a failed connect (nothing else ever will) and with the handle once the connection is closed.
class NotificationListenerGroupReleaseTest {
    private static final TimeSpan GUARD = TimeSpan.fromDuration(Duration.ofSeconds(10));
    private static final long THREADS_GONE_MILLIS = 10_000;

    @Test
    void failedConnect_releasesTheOwnedEventLoopGroup() throws IOException, InterruptedException {
        var before = eventLoopThreads();
        var config = databaseConnectorConfigBuilder().withName("listener-refused")
                                                   .withAsyncUrl("postgresql://127.0.0.1:" + refusedPort() + "/db")
                                                   .withUsername("u")
                                                   .withPassword("p")
                                                   .build()
                                                   .unwrap();

        var outcome = NotificationListenerFactory.provisionWith(config, List.of("c"))
                                                 .await(GUARD);

        assertThat(outcome.isFailure()).as("connect to a refused port fails").isTrue();

        var leaked = awaitNoNewEventLoopThreads(before);

        assertThat(leaked).as("event-loop threads started by the failed provision and still alive").isEmpty();
    }

    @Test
    void handleClose_closesTheConnectibleAfterTheConnection() {
        var events = new CopyOnWriteArrayList<String>();
        var handle = new NotificationListenerHandle(new RecordingConnection(events), new RecordingConnectible(events));

        handle.addListening(() -> {
            events.add("unlisten");
            return Promise.success(Unit.unit());
        });

        assertThat(handle.close().await(GUARD).isSuccess()).isTrue();
        assertThat(events).containsExactly("unlisten", "connection.close", "connectible.close");
    }

    private static int refusedPort() throws IOException {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return server.getLocalPort();
        }
    }

    private static Set<Thread> eventLoopThreads() {
        return Thread.getAllStackTraces()
                     .keySet()
                     .stream()
                     .filter(t -> t.getName()
                                   .toLowerCase()
                                   .contains("eventloopgroup"))
                     .collect(Collectors.toSet());
    }

    private static List<String> awaitNoNewEventLoopThreads(Set<Thread> before) {
        return PollUntil.pollUntil(() -> eventLoopThreads().stream()
                                                           .filter(t -> !before.contains(t) && t.isAlive())
                                                           .map(Thread::getName)
                                                           .toList(),
                                   List::isEmpty,
                                   Duration.ofMillis(THREADS_GONE_MILLIS))
                        .finalValue();
    }

    private record RecordingConnectible(List<String> events) implements Connectible {
        @Override
        public Promise<Connection> getConnection() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Promise<Unit> script(BiConsumer<Map<String, PgColumn>, PgColumn[]> onColumns,
                                    Consumer<PgRow> onRow,
                                    Consumer<Integer> onAffected,
                                    String sql) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Promise<Integer> query(BiConsumer<Map<String, PgColumn>, PgColumn[]> onColumns,
                                      Consumer<PgRow> onRow,
                                      String sql,
                                      Object... params) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Promise<Unit> close() {
            events.add("connectible.close");
            return Promise.success(Unit.unit());
        }
    }

    private record RecordingConnection(List<String> events) implements Connection {
        @Override
        public Promise<? extends PreparedStatement> prepareStatement(String sql, Oid... parametersTypes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Promise<Listening> subscribe(String channel, Consumer<String> onNotification) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Promise<Transaction> begin() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Promise<Unit> close() {
            events.add("connection.close");
            return Promise.success(Unit.unit());
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public Promise<Unit> script(BiConsumer<Map<String, PgColumn>, PgColumn[]> onColumns,
                                    Consumer<PgRow> onRow,
                                    Consumer<Integer> onAffected,
                                    String sql) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Promise<Integer> query(BiConsumer<Map<String, PgColumn>, PgColumn[]> onColumns,
                                      Consumer<PgRow> onRow,
                                      String sql,
                                      Object... params) {
            throw new UnsupportedOperationException();
        }
    }
}
