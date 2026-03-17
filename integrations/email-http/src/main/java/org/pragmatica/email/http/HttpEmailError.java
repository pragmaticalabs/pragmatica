package org.pragmatica.email.http;

import org.pragmatica.lang.Cause;

/// Error causes for HTTP email operations.
public sealed interface HttpEmailError extends Cause {
    /// No vendor mapping found for the configured provider hint.
    record VendorNotFound(String vendorId) implements HttpEmailError {
        @Override
        public String message() {
            return "No vendor mapping found for: " + vendorId;
        }
    }

    /// HTTP request to the email vendor API failed.
    record RequestFailed(int statusCode, String body) implements HttpEmailError {
        @Override
        public String message() {
            return "Email API request failed with HTTP " + statusCode + ": " + body;
        }
    }

    /// Authentication with the email vendor API failed.
    record AuthError(String detail) implements HttpEmailError {
        @Override
        public String message() {
            return "Email API authentication failed: " + detail;
        }
    }
}
