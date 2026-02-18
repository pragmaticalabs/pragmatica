package org.pragmatica.aether.example.banking.shared;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.utility.IdGenerator;

/// Unique identifier for a transfer.
public record TransferId(String value) {
    private static final Fn1<Cause, String> INVALID_TRANSFER_ID = Causes.forOneValue("Invalid transfer ID: %s");

    public static Result<TransferId> transferId(String raw) {
        return Verify.ensure(raw, Verify.Is::notBlank, INVALID_TRANSFER_ID)
                     .map(TransferId::new);
    }

    public static TransferId generate() {
        return new TransferId(IdGenerator.generate("TXF"));
    }
}
