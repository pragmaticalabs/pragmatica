// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
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
