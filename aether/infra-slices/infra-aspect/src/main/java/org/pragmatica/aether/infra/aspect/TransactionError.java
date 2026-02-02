package org.pragmatica.aether.infra.aspect;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;

/**
 * Error types for transaction operations.
 */
public sealed interface TransactionError extends Cause {
    /**
     * Transaction not active when operation required one.
     */
    record NoActiveTransaction(String operation) implements TransactionError {
        @Override
        public String message() {
            return "No active transaction for operation: " + operation;
        }
    }

    /**
     * Transaction already active when operation required none.
     */
    record TransactionAlreadyActive(String operation) implements TransactionError {
        @Override
        public String message() {
            return "Transaction already active for operation: " + operation;
        }
    }

    /**
     * Transaction timed out.
     */
    record TransactionTimedOut(String transactionId) implements TransactionError {
        @Override
        public String message() {
            return "Transaction timed out: " + transactionId;
        }
    }

    /**
     * Transaction rollback occurred.
     */
    record TransactionRolledBack(String transactionId, Option<Throwable> cause) implements TransactionError {
        @Override
        public String message() {
            return "Transaction rolled back: " + transactionId + cause.fold(() -> "", c -> " - " + c.getMessage());
        }
    }

    /**
     * Invalid transaction configuration.
     */
    record InvalidConfig(String reason) implements TransactionError {
        @Override
        public String message() {
            return "Invalid transaction configuration: " + reason;
        }
    }

    /**
     * Transaction operation failed.
     */
    record OperationFailed(String operation, Option<Throwable> cause) implements TransactionError {
        @Override
        public String message() {
            return "Transaction operation failed: " + operation + cause.fold(() -> "", c -> " - " + c.getMessage());
        }
    }

    // Factory methods
    static NoActiveTransaction noActiveTransaction(String operation) {
        return new NoActiveTransaction(operation);
    }

    static TransactionAlreadyActive transactionAlreadyActive(String operation) {
        return new TransactionAlreadyActive(operation);
    }

    static TransactionTimedOut transactionTimedOut(String transactionId) {
        return new TransactionTimedOut(transactionId);
    }

    static TransactionRolledBack transactionRolledBack(String transactionId) {
        return new TransactionRolledBack(transactionId, Option.none());
    }

    static TransactionRolledBack transactionRolledBack(String transactionId, Throwable cause) {
        return new TransactionRolledBack(transactionId, Option.option(cause));
    }

    static InvalidConfig invalidConfig(String reason) {
        return new InvalidConfig(reason);
    }

    static OperationFailed operationFailed(String operation) {
        return new OperationFailed(operation, Option.none());
    }

    static OperationFailed operationFailed(String operation, Throwable cause) {
        return new OperationFailed(operation, Option.option(cause));
    }
}
