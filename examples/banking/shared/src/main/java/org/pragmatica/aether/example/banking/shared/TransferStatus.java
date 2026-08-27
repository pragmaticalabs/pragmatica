package org.pragmatica.aether.example.banking.shared;

/// Status of a transfer operation.
public enum TransferStatus {
    PENDING,
    COMPLETED,
    FAILED,
    /// The credit leg failed and the compensating credit put the money back.
    COMPENSATED,
    /// The credit leg failed AND the compensating credit also failed: the source account is still
    /// debited and nothing has put the money back. Requires operator attention.
    COMPENSATION_FAILED
}
