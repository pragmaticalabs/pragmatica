package org.pragmatica.storage;

/// How far a resumable write has got: the byte offset and the session version it was recorded under.
public record WriteCheckpoint(long offset, long sessionVersion) {
    public static WriteCheckpoint writeCheckpoint(long offset, long sessionVersion) {
        return new WriteCheckpoint(offset, sessionVersion);
    }
}
