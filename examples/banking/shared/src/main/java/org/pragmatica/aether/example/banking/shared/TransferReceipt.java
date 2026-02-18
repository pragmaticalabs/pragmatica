package org.pragmatica.aether.example.banking.shared;

import java.time.Instant;

/// Full receipt for a completed transfer.
public record TransferReceipt(TransferId transferId,
                              AccountId sourceAccountId,
                              AccountId destinationAccountId,
                              Money sourceAmount,
                              Money destinationAmount,
                              TransferStatus status,
                              Instant completedAt) {

    public static TransferReceipt transferReceipt(TransferId transferId,
                                                  AccountId source,
                                                  AccountId destination,
                                                  Money sourceAmount,
                                                  Money destinationAmount) {
        return new TransferReceipt(transferId, source, destination,
                                   sourceAmount, destinationAmount,
                                   TransferStatus.COMPLETED, Instant.now());
    }
}
