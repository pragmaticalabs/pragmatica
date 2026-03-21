/*
 *  Copyright (c) 2022-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.net.smtp;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/// An email message to be sent via SMTP.
public record SmtpMessage(String from,
                          List<String> to,
                          List<String> cc,
                          List<String> bcc,
                          String subject,
                          String body,
                          Map<String, String> headers) {

    /// Create a simple message with required fields only.
    public static SmtpMessage smtpMessage(String from, List<String> to, String subject, String body) {
        return new SmtpMessage(from, List.copyOf(to), List.of(), List.of(), subject, body, Map.of());
    }

    /// Return a copy with the given CC recipients.
    public SmtpMessage withCc(List<String> cc) {
        return new SmtpMessage(from, to, List.copyOf(cc), bcc, subject, body, headers);
    }

    /// Return a copy with the given BCC recipients.
    public SmtpMessage withBcc(List<String> bcc) {
        return new SmtpMessage(from, to, cc, List.copyOf(bcc), subject, body, headers);
    }

    /// Return a copy with the given additional headers.
    public SmtpMessage withHeaders(Map<String, String> headers) {
        return new SmtpMessage(from, to, cc, bcc, subject, body, Map.copyOf(headers));
    }

    /// All envelope recipients (to + cc + bcc).
    public List<String> allRecipients() {
        return List.of(to, cc, bcc)
                   .stream()
                   .flatMap(List::stream)
                   .toList();
    }

    /// Format the message as an RFC 5322 string for the DATA command.
    public String toRfc5322() {
        var sb = new StringBuilder();
        sb.append("Date: ").append(DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now())).append("\r\n");
        sb.append("From: ").append(from).append("\r\n");
        sb.append("To: ").append(String.join(", ", to)).append("\r\n");
        appendCcHeaders(sb);
        sb.append("Subject: ").append(subject).append("\r\n");
        appendCustomHeaders(sb);
        sb.append("MIME-Version: 1.0\r\n");
        sb.append("Content-Type: text/plain; charset=UTF-8\r\n");
        sb.append("\r\n");
        sb.append(body);
        return sb.toString();
    }

    private void appendCcHeaders(StringBuilder sb) {
        if (!cc.isEmpty()) {
            sb.append("Cc: ").append(String.join(", ", cc)).append("\r\n");
        }
    }

    private void appendCustomHeaders(StringBuilder sb) {
        headers.forEach((key, value) -> sb.append(key).append(": ").append(value).append("\r\n"));
    }
}
