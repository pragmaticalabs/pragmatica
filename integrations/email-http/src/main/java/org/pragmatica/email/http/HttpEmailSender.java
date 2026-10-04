package org.pragmatica.email.http;

import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.JdkHttpOperations;
import org.pragmatica.lang.Promise;


/// HTTP-based email sender with pluggable vendor mappings.
public interface HttpEmailSender {
    /// Sends an email message and returns the response body (typically a message ID).
    Promise<String> send(EmailMessage message);

    /// Creates an HttpEmailSender with default HTTP operations. The sender builds them, so it owns them:
    /// closing the sender (it is [org.pragmatica.lang.io.AsyncCloseable]) closes them (#1097).
    static HttpEmailSender httpEmailSender(HttpEmailConfig config) {
        return HttpEmailSenderCore.create(config, JdkHttpOperations.jdkHttpOperations(), true);
    }

    /// Creates an HttpEmailSender with custom HTTP operations. The caller owns what it passes in: closing
    /// the sender never closes these operations.
    static HttpEmailSender httpEmailSender(HttpEmailConfig config, HttpOperations operations) {
        return HttpEmailSenderCore.create(config, operations, false);
    }
}
