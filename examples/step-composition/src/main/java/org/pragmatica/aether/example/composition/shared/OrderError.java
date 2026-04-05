package org.pragmatica.aether.example.composition.shared;

import org.pragmatica.lang.Cause;


/// Sealed error hierarchy for the order processing domain.
///
/// All failures are expressed as Cause subtypes — no exceptions in business logic.
/// The sealed interface ensures exhaustive handling and enables the routes.toml
/// error mapping (e.g., `*ValidationFailed*` -> HTTP 400).
///
/// Three categories:
/// - ValidationFailed — input validation errors (HTTP 400)
/// - InvalidAmount — monetary parsing/validation errors (HTTP 400)
/// - PersistenceFailed — database/IO errors (HTTP 500)
public sealed interface OrderError extends Cause {

    record ValidationFailed(String detail) implements OrderError {
        @Override
        public String message() {
            return "Order validation failed: " + detail;
        }
    }

    record InvalidAmount(String detail) implements OrderError {
        @Override
        public String message() {
            return "Invalid amount: " + detail;
        }
    }

    record PersistenceFailed(Throwable cause) implements OrderError {
        @Override
        public String message() {
            return "Persistence failed: " + cause.getMessage();
        }
    }
}
