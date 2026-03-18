package org.pragmatica.aether.resource.notification;

import org.pragmatica.lang.Option;

import java.util.List;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Notification message to send. Sealed hierarchy — Phase 1 supports only email.
public sealed interface Notification {
    record Email(String from,
                 List<String> to,
                 String subject,
                 NotificationBody body,
                 List<String> cc,
                 List<String> bcc,
                 Option<String> replyTo) implements Notification {
        /// Create an email notification with required fields and sensible defaults.
        public static Email email(String from, List<String> to, String subject, NotificationBody body) {
            return new Email(from, to, subject, body, List.of(), List.of(), none());
        }

        /// Return a copy with the given CC recipients.
        public Email withCc(List<String> cc) {
            return new Email(from, to, subject, body, List.copyOf(cc), bcc, replyTo);
        }

        /// Return a copy with the given BCC recipients.
        public Email withBcc(List<String> bcc) {
            return new Email(from, to, subject, body, cc, List.copyOf(bcc), replyTo);
        }

        /// Return a copy with the given reply-to address.
        public Email withReplyTo(String replyTo) {
            return new Email(from, to, subject, body, cc, bcc, some(replyTo));
        }
    }
}
