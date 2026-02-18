package org.pragmatica.aether.example.banking.shared;

/// Status of a transfer operation.
public enum TransferStatus {
    PENDING,
    COMPLETED,
    FAILED,
    COMPENSATED
}
