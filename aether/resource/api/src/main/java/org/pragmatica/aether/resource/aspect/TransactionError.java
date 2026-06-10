// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.aspect;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


public sealed interface TransactionError extends Cause {
    record NoActiveTransaction(String operation) implements TransactionError {
        public static Result<NoActiveTransaction> noActiveTransaction(String operation) {
            return success(new NoActiveTransaction(operation));
        }

        @Override
        public String message() {
            return "No active transaction for operation: " + operation;
        }
    }

    record TransactionAlreadyActive(String operation) implements TransactionError {
        public static Result<TransactionAlreadyActive> transactionAlreadyActive(String operation) {
            return success(new TransactionAlreadyActive(operation));
        }

        @Override
        public String message() {
            return "Transaction already active for operation: " + operation;
        }
    }

    record TransactionTimedOut(String transactionId) implements TransactionError {
        public static Result<TransactionTimedOut> transactionTimedOut(String transactionId) {
            return success(new TransactionTimedOut(transactionId));
        }

        @Override
        public String message() {
            return "Transaction timed out: " + transactionId;
        }
    }

    record TransactionRolledBack(String transactionId, Option<Throwable> cause) implements TransactionError {
        public static Result<TransactionRolledBack> transactionRolledBack(String transactionId,
                                                                          Option<Throwable> cause) {
            return success(new TransactionRolledBack(transactionId, cause));
        }

        @Override
        public String message() {
            return "Transaction rolled back: " + transactionId + cause.fold(() -> "", TransactionRolledBack::formatCause);
        }

        private static String formatCause(Throwable c) {
            return " - " + c.getMessage();
        }
    }

    record InvalidConfig(String reason) implements TransactionError {
        public static Result<InvalidConfig> invalidConfig(String reason) {
            return success(new InvalidConfig(reason));
        }

        @Override
        public String message() {
            return "Invalid transaction configuration: " + reason;
        }
    }

    record OperationFailed(String operation, Option<Throwable> cause) implements TransactionError {
        public static Result<OperationFailed> operationFailed(String operation, Option<Throwable> cause) {
            return success(new OperationFailed(operation, cause));
        }

        @Override
        public String message() {
            return "Transaction operation failed: " + operation + cause.fold(() -> "", OperationFailed::formatCause);
        }

        private static String formatCause(Throwable c) {
            return " - " + c.getMessage();
        }
    }

    static NoActiveTransaction noActiveTransaction(String operation) {
        return NoActiveTransaction.noActiveTransaction(operation).unwrap();
    }

    static TransactionAlreadyActive transactionAlreadyActive(String operation) {
        return TransactionAlreadyActive.transactionAlreadyActive(operation).unwrap();
    }

    static TransactionTimedOut transactionTimedOut(String transactionId) {
        return TransactionTimedOut.transactionTimedOut(transactionId).unwrap();
    }

    static TransactionRolledBack transactionRolledBack(String transactionId) {
        return TransactionRolledBack.transactionRolledBack(transactionId, none()).unwrap();
    }

    static TransactionRolledBack transactionRolledBack(String transactionId, Throwable cause) {
        return TransactionRolledBack.transactionRolledBack(transactionId, option(cause)).unwrap();
    }

    static InvalidConfig invalidConfig(String reason) {
        return InvalidConfig.invalidConfig(reason).unwrap();
    }

    static OperationFailed operationFailed(String operation) {
        return OperationFailed.operationFailed(operation, none()).unwrap();
    }

    static OperationFailed operationFailed(String operation, Throwable cause) {
        return OperationFailed.operationFailed(operation, option(cause)).unwrap();
    }

    record unused() implements TransactionError {
        public static Result<unused > unused() {
            return success(new unused());
        }

        @Override
        public String message() {
            return "";
        }
    }
}
