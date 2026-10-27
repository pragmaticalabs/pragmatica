package org.pragmatica.storage;


/// Where a listing resumes: opaque to callers, handed back from one page to ask for the next.
public record Cursor(String value) {
    public static Cursor cursor(String value) {
        return new Cursor(value);
    }
}
