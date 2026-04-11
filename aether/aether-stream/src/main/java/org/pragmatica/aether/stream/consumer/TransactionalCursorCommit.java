package org.pragmatica.aether.stream.consumer;

import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Promise;


/// Commits consumer cursor position within a transaction that also executes business logic.
/// Enables exactly-once semantics: cursor advance and side effects are atomic.
///
/// The callback receives a SqlConnector that participates in the same transaction,
/// so cursor UPSERT and business writes either both commit or both rollback.
public interface TransactionalCursorCommit {
    <T> Promise<T> commitWithLogic(String groupId,
                                   String streamName,
                                   int partition,
                                   long offset,
                                   SqlConnector.TransactionCallback<T> logic);
}
