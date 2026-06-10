// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.consumer;

import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Promise;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public final class PgTransactionalCursorCommit implements TransactionalCursorCommit {
    private static final Logger log = LoggerFactory.getLogger(PgTransactionalCursorCommit.class);

    private static final String UPSERT_CURSOR = "INSERT INTO aether_stream_cursors (consumer_group, stream_name, partition_id, committed_offset, updated_at) "
                                              + "VALUES (?, ?, ?, ?, NOW()) "
                                              + "ON CONFLICT (consumer_group, stream_name, partition_id) "
                                              + "DO UPDATE SET committed_offset = EXCLUDED.committed_offset, updated_at = NOW()";

    private final SqlConnector connector;

    private PgTransactionalCursorCommit(SqlConnector connector) {
        this.connector = connector;
    }

    public static PgTransactionalCursorCommit pgTransactionalCursorCommit(SqlConnector connector) {
        return new PgTransactionalCursorCommit(connector);
    }

    @Override
    public <T> Promise<T> commitWithLogic(String groupId,
                                          String streamName,
                                          int partition,
                                          long offset,
                                          SqlConnector.TransactionCallback<T> logic) {
        return connector.transactional(tx -> executeInTransaction(tx, groupId, streamName, partition, offset, logic))
                        .onSuccess(_ -> logCommit(groupId, streamName, partition, offset));
    }

    private static <T> Promise<T> executeInTransaction(SqlConnector tx,
                                                       String groupId,
                                                       String streamName,
                                                       int partition,
                                                       long offset,
                                                       SqlConnector.TransactionCallback<T> logic) {
        return logic.execute(tx)
                    .flatMap(result -> commitCursor(tx, groupId, streamName, partition, offset, result));
    }

    private static <T> Promise<T> commitCursor(SqlConnector tx,
                                               String groupId,
                                               String streamName,
                                               int partition,
                                               long offset,
                                               T result) {
        return tx.update(UPSERT_CURSOR, groupId, streamName, partition, offset)
                 .map(_ -> result);
    }

    private static void logCommit(String groupId, String streamName, int partition, long offset) {
        log.debug("Transactional cursor committed: {}/{}/{} -> {}",
                  groupId,
                  streamName,
                  partition,
                  offset);
    }
}
