package org.pragmatica.aether.infra.aspect;
/**
 * Transaction propagation behavior.
 * Defines how transactions should behave when a transactional method
 * is called within the context of an existing transaction.
 */
public enum TransactionPropagation {
    /**
     * Support a current transaction, create a new one if none exists.
     * This is the default behavior.
     */
    REQUIRED,
    /**
     * Create a new transaction, suspending the current transaction if one exists.
     */
    REQUIRES_NEW,
    /**
     * Support a current transaction, execute non-transactionally if none exists.
     */
    SUPPORTS,
    /**
     * Execute non-transactionally, suspend the current transaction if one exists.
     */
    NOT_SUPPORTED,
    /**
     * Support a current transaction, throw an exception if none exists.
     */
    MANDATORY,
    /**
     * Execute non-transactionally, throw an exception if a transaction exists.
     */
    NEVER,
    /**
     * Execute within a nested transaction if a current transaction exists,
     * behave like REQUIRED otherwise.
     */
    NESTED
}
