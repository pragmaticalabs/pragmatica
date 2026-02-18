package org.pragmatica.aether.example.banking.shared;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.utility.IdGenerator;

/// Unique identifier for a bank account.
public record AccountId(String value) {
    private static final Fn1<Cause, String> INVALID_ACCOUNT_ID = Causes.forOneValue("Invalid account ID: %s");

    public static Result<AccountId> accountId(String raw) {
        return Verify.ensure(raw, Verify.Is::notBlank, INVALID_ACCOUNT_ID)
                     .map(AccountId::new);
    }

    public static AccountId generate() {
        return new AccountId(IdGenerator.generate("ACC"));
    }
}
