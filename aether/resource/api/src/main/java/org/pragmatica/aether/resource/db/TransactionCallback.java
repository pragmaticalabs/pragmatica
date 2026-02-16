package org.pragmatica.aether.resource.db;

import org.pragmatica.lang.Promise;

/// Callback for transactional operations.
///
/// The callback receives a DatabaseConnector that is bound to the current transaction.
/// All operations performed through this connector participate in the transaction.
///
/// The transaction is automatically committed if the callback completes successfully,
/// or rolled back if the callback fails or throws an exception.
///
/// @param <T> Result type
@FunctionalInterface
public interface TransactionCallback<T> {
    /// Executes operations within the transaction.
    ///
    /// @param connector DatabaseConnector bound to the transaction
    /// @return Promise with result
    Promise<T> execute(DatabaseConnector connector);
}
