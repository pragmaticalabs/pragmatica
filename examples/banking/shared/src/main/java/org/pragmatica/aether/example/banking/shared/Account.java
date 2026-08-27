package org.pragmatica.aether.example.banking.shared;

import java.time.Instant;


/// Bank account value object.
public record Account(AccountId id,
                      String holderName,
                      String email,
                      Currency currency,
                      AccountStatus status,
                      Instant createdAt) {
    public enum AccountStatus {
        ACTIVE,
        CLOSED,
        FROZEN
    }

    public static Account account(AccountId id, String holderName, String email, Currency currency) {
        return new Account(id, holderName, email, currency, AccountStatus.ACTIVE, Instant.now());
    }

    /// Rehydrate an account that already exists in storage, keeping the status and creation instant
    /// that were persisted rather than minting new ones.
    public static Account account(AccountId id,
                                  String holderName,
                                  String email,
                                  Currency currency,
                                  AccountStatus status,
                                  Instant createdAt) {
        return new Account(id, holderName, email, currency, status, createdAt);
    }

    public Account withStatus(AccountStatus newStatus) {
        return new Account(id, holderName, email, currency, newStatus, createdAt);
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }
}
