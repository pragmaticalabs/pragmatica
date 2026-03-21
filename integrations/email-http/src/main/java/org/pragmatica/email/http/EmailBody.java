package org.pragmatica.email.http;

import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Email body content — either plain text or HTML with optional fallback.
public sealed interface EmailBody {
    record Text(String content) implements EmailBody {
        public static Text text(String content) {
            return new Text(content);
        }
    }

    record Html(String content, Option<String> fallback) implements EmailBody {
        public static Html html(String content) {
            return new Html(content, none());
        }

        public static Html html(String content, String fallback) {
            return new Html(content, some(fallback));
        }
    }
}
