package org.pragmatica.storage;

/// One version of a durable anchor, as [StorageTier#latestAnchor] returns it.
public record Anchor(long version, byte[] value) {
    public static Anchor anchor(long version, byte[] value) {
        return new Anchor(version, value);
    }
}
