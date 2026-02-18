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

    public static Account account(AccountId id,
                                  String holderName,
                                  String email,
                                  Currency currency) {
        return new Account(id, holderName, email, currency, AccountStatus.ACTIVE, Instant.now());
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }
}
