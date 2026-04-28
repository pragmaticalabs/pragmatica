// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.notification;

import org.pragmatica.lang.Option;

import java.util.List;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


public sealed interface Notification {
    record Email(String from,
                 List<String> to,
                 String subject,
                 NotificationBody body,
                 List<String> cc,
                 List<String> bcc,
                 Option<String> replyTo) implements Notification {
        public static Email email(String from, List<String> to, String subject, NotificationBody body) {
            return new Email(from, to, subject, body, List.of(), List.of(), none());
        }

        public Email withCc(List<String> cc) {
            return new Email(from, to, subject, body, List.copyOf(cc), bcc, replyTo);
        }

        public Email withBcc(List<String> bcc) {
            return new Email(from, to, subject, body, cc, List.copyOf(bcc), replyTo);
        }

        public Email withReplyTo(String replyTo) {
            return new Email(from, to, subject, body, cc, bcc, some(replyTo));
        }
    }
}
