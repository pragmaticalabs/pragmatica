package org.pragmatica.aether.example.banking.shared;

import java.time.Instant;

import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;


/// Brief listing record for transfer history.
///
/// `failureDetail` carries the diagnosis for statuses that need one -- notably
/// [TransferStatus#COMPENSATION_FAILED], where both the credit and the compensating credit failed
/// and the recorded status alone does not say why. It is empty for every ordinary outcome.
public record TransferSummary(TransferId transferId,
                              AccountId from,
                              AccountId to,
                              Money amount,
                              TransferStatus status,
                              Instant timestamp,
                              Option<String> failureDetail) {
    public static TransferSummary transferSummary(TransferId transferId,
                                                  AccountId from,
                                                  AccountId to,
                                                  Money amount,
                                                  TransferStatus status,
                                                  Instant timestamp) {
        return transferSummary(transferId, from, to, amount, status, timestamp, none());
    }

    public static TransferSummary transferSummary(TransferId transferId,
                                                  AccountId from,
                                                  AccountId to,
                                                  Money amount,
                                                  TransferStatus status,
                                                  Instant timestamp,
                                                  Option<String> failureDetail) {
        return new TransferSummary(transferId, from, to, amount, status, timestamp, failureDetail);
    }
}
