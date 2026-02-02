package org.pragmatica.aether.infra.aspect;
/**
 * Transaction isolation levels.
 * Defines the degree to which operations in one transaction are isolated
 * from concurrent transactions.
 */
public enum IsolationLevel {
    /**
     * Use the default isolation level of the underlying data source.
     */
    DEFAULT,
    /**
     * Lowest isolation level. Allows dirty reads, non-repeatable reads, and phantom reads.
     */
    READ_UNCOMMITTED,
    /**
     * Prevents dirty reads. Allows non-repeatable reads and phantom reads.
     */
    READ_COMMITTED,
    /**
     * Prevents dirty reads and non-repeatable reads. Allows phantom reads.
     */
    REPEATABLE_READ,
    /**
     * Highest isolation level. Prevents dirty reads, non-repeatable reads, and phantom reads.
     */
    SERIALIZABLE
}
