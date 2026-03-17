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
