package org.pragmatica.email.http;

import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.JdkHttpOperations;
import org.pragmatica.lang.Promise;

/// HTTP-based email sender with pluggable vendor mappings.
public interface HttpEmailSender {
    /// Sends an email message and returns the response body (typically a message ID).
    Promise<String> send(EmailMessage message);

    /// Creates an HttpEmailSender with default HTTP operations.
    static HttpEmailSender httpEmailSender(HttpEmailConfig config) {
        return httpEmailSender(config, JdkHttpOperations.jdkHttpOperations());
    }

    /// Creates an HttpEmailSender with custom HTTP operations.
    static HttpEmailSender httpEmailSender(HttpEmailConfig config, HttpOperations operations) {
        return HttpEmailSenderCore.create(config, operations);
    }
}
