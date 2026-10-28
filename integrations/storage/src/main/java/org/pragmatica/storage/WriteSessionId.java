package org.pragmatica.storage;

/// Names a write that can be resumed after a restart. Local mode issues none: [ContentStore#resume] refuses.
public record WriteSessionId(String value) {
    public static WriteSessionId writeSessionId(String value) {
        return new WriteSessionId(value);
    }
}
