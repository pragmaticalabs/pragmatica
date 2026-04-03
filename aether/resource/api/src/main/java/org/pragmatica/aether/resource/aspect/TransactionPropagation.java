package org.pragmatica.aether.resource.aspect;

public enum TransactionPropagation {
    REQUIRED,
    REQUIRES_NEW,
    SUPPORTS,
    NOT_SUPPORTED,
    MANDATORY,
    NEVER,
    NESTED
}
