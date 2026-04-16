// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.notification;

import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// Notification body content — either plain text or HTML with optional text fallback.
public sealed interface NotificationBody {
    record Text(String content) implements NotificationBody {
        public static Text text(String content) {
            return new Text(content);
        }
    }

    record Html(String content, Option<String> fallback) implements NotificationBody {
        public static Html html(String content) {
            return new Html(content, none());
        }

        public static Html html(String content, String fallback) {
            return new Html(content, some(fallback));
        }
    }
}
