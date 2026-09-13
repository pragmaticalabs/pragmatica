package org.pragmatica.email.http;

import org.pragmatica.lang.Cause;


/// Error causes for HTTP email operations.
public sealed interface HttpEmailError extends Cause {
    /// No vendor mapping found for the configured provider hint. Configuration, so permanent.
    record VendorNotFound(String vendorId) implements HttpEmailError, Cause.Terminal {
        @Override
        public String message() {
            return "No vendor mapping found for: " + vendorId;
        }
    }

    /// HTTP request to the email vendor API failed.
    ///
    /// A 4xx is the vendor's verdict on THIS request and will be repeated for the same request, so
    /// it is terminal — except 408 (the request never arrived) and 429 (a rate limit that clears),
    /// which are the two client-error codes that describe the moment rather than the message. A
    /// 5xx describes the vendor's side and may clear, so it stays retryable (#271).
    record RequestFailed(int statusCode, String body) implements HttpEmailError {
        @Override
        public String message() {
            return "Email API request failed with HTTP " + statusCode + ": " + body;
        }

        @Override
        public boolean isTerminal() {
            return statusCode >= 400
                   && statusCode < 500
                   && statusCode != 408
                   && statusCode != 429;
        }
    }

    /// Authentication with the email vendor API failed. Same credentials, same answer: permanent.
    record AuthError(String detail) implements HttpEmailError, Cause.Terminal {
        @Override
        public String message() {
            return "Email API authentication failed: " + detail;
        }
    }
}
