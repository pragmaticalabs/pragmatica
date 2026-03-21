package org.pragmatica.email.http;

import org.pragmatica.lang.Option;

import java.util.List;

import static org.pragmatica.lang.Option.none;

/// Immutable email message with sender, recipients, subject, and body.
public record EmailMessage(
    String from,
    List<String> to,
    String subject,
    EmailBody body,
    List<String> cc,
    List<String> bcc,
    Option<String> replyTo
) {
    /// Creates an email message with required fields and sensible defaults.
    public static EmailMessage emailMessage(String from, List<String> to, String subject, EmailBody body) {
        return new EmailMessage(from, to, subject, body, List.of(), List.of(), none());
    }

    /// Returns a copy with the specified CC recipients.
    public EmailMessage withCc(List<String> cc) {
        return new EmailMessage(from, to, subject, body, cc, bcc, replyTo);
    }

    /// Returns a copy with the specified BCC recipients.
    public EmailMessage withBcc(List<String> bcc) {
        return new EmailMessage(from, to, subject, body, cc, bcc, replyTo);
    }

    /// Returns a copy with the specified reply-to address.
    public EmailMessage withReplyTo(String replyTo) {
        return new EmailMessage(from, to, subject, body, cc, bcc, Option.some(replyTo));
    }
}
