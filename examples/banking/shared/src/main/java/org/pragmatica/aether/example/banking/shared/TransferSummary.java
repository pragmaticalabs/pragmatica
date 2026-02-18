package org.pragmatica.aether.example.banking.shared;

import java.time.Instant;

/// Brief listing record for transfer history.
public record TransferSummary(TransferId transferId,
                              AccountId from,
                              AccountId to,
                              Money amount,
                              TransferStatus status,
                              Instant timestamp) {

    public static TransferSummary transferSummary(TransferId transferId,
                                                  AccountId from,
                                                  AccountId to,
                                                  Money amount,
                                                  TransferStatus status,
                                                  Instant timestamp) {
        return new TransferSummary(transferId, from, to, amount, status, timestamp);
    }
}
