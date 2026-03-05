/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.pragmatica.postgres.r2dbc;

import io.r2dbc.spi.Batch;
import io.r2dbc.spi.ConnectionMetadata;
import io.r2dbc.spi.IsolationLevel;
import io.r2dbc.spi.Statement;
import io.r2dbc.spi.TransactionDefinition;
import io.r2dbc.spi.ValidationDepth;

import java.time.Duration;

import org.pragmatica.postgres.net.Connection;
import org.pragmatica.postgres.net.Transaction;
import org.reactivestreams.Publisher;

import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

/// R2DBC [io.r2dbc.spi.Connection] backed by a postgres-async [Connection].
///
/// Delegates all operations to the underlying postgres-async connection,
/// bridging promise-based results to reactive [Publisher] instances.
public final class PgAsyncConnection implements io.r2dbc.spi.Connection {
    private final Connection connection;
    private Transaction currentTransaction;

    PgAsyncConnection(Connection connection) {
        this.connection = connection;
    }

    /// Returns the underlying postgres-async connection for use by statements.
    Connection pgConnection() {
        return connection;
    }

    @Override
    public Publisher<Void> beginTransaction() {
        return Mono.create(sink ->
            connection.begin()
                      .onSuccess(tx -> storeTransactionAndComplete(tx, sink))
                      .onFailure(cause -> sink.error(new R2dbcAdapterException(cause.message()))));
    }

    @Override
    public Publisher<Void> commitTransaction() {
        if (currentTransaction == null) {
            return Mono.empty();
        }
        return Mono.create(sink ->
            currentTransaction.commit()
                              .onSuccess(_ -> clearTransactionAndComplete(sink))
                              .onFailure(cause -> sink.error(new R2dbcAdapterException(cause.message()))));
    }

    @Override
    public Publisher<Void> rollbackTransaction() {
        if (currentTransaction == null) {
            return Mono.empty();
        }
        return Mono.create(sink ->
            currentTransaction.rollback()
                              .onSuccess(_ -> clearTransactionAndComplete(sink))
                              .onFailure(cause -> sink.error(new R2dbcAdapterException(cause.message()))));
    }

    @Override
    public Publisher<Void> close() {
        return Mono.create(sink ->
            connection.close()
                      .onSuccess(_ -> sink.success())
                      .onFailure(cause -> sink.error(new R2dbcAdapterException(cause.message()))));
    }

    private void storeTransactionAndComplete(Transaction tx, MonoSink<Void> sink) {
        currentTransaction = tx;
        sink.success();
    }

    private void clearTransactionAndComplete(MonoSink<Void> sink) {
        currentTransaction = null;
        sink.success();
    }

    @Override
    public Statement createStatement(String sql) {
        return new PgAsyncStatement(this, sql);
    }

    @Override
    public Batch createBatch() {
        throw new UnsupportedOperationException("Batch not supported via R2DBC adapter; use Statement with bind/add");
    }

    @Override
    public Publisher<Void> beginTransaction(TransactionDefinition definition) {
        return beginTransaction();
    }

    @Override
    public Publisher<Void> setTransactionIsolationLevel(IsolationLevel isolationLevel) {
        return Mono.empty();
    }

    @Override
    public boolean isAutoCommit() {
        return currentTransaction == null;
    }

    @Override
    public Publisher<Void> setAutoCommit(boolean autoCommit) {
        return Mono.empty();
    }

    @Override
    public ConnectionMetadata getMetadata() {
        return PgAsyncConnectionMetadata.INSTANCE;
    }

    @Override
    public IsolationLevel getTransactionIsolationLevel() {
        return IsolationLevel.READ_COMMITTED;
    }

    @Override
    public Publisher<Void> createSavepoint(String name) {
        return Mono.empty();
    }

    @Override
    public Publisher<Void> releaseSavepoint(String name) {
        return Mono.empty();
    }

    @Override
    public Publisher<Void> rollbackTransactionToSavepoint(String name) {
        return Mono.empty();
    }

    @Override
    public Publisher<Void> setStatementTimeout(Duration timeout) {
        return Mono.empty();
    }

    @Override
    public Publisher<Void> setLockWaitTimeout(Duration timeout) {
        return Mono.empty();
    }

    @Override
    public Publisher<Boolean> validate(ValidationDepth depth) {
        return Mono.just(connection.isConnected());
    }
}
