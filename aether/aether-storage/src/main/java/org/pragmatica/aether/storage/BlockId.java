package org.pragmatica.aether.storage;

import org.pragmatica.lang.Result;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

/// Content-addressed block identifier. The ID is the SHA-256 hash of the block content.
/// Immutable and suitable as a map key.
public record BlockId(byte[] hash) {
    private static final HexFormat HEX = HexFormat.of();

    /// Defensive copy of mutable byte array.
    public BlockId {
        hash = hash.clone();
    }

    /// Create a BlockId from raw content by computing SHA-256.
    public static Result<BlockId> blockId(byte[] content) {
        return Result.lift(() -> MessageDigest.getInstance("SHA-256"))
                     .map(digest -> digest.digest(content))
                     .map(BlockId::new);
    }

    /// Create a BlockId from a pre-computed hex string.
    public static Result<BlockId> fromHex(String hex) {
        return Result.lift(() -> HEX.parseHex(hex))
                     .map(BlockId::new);
    }

    /// Hex string representation.
    public String hexString() {
        return HEX.formatHex(hash);
    }

    /// First 4 hex chars for two-level directory sharding.
    public String shardPrefix() {
        var hex = hexString();
        return hex.substring(0, 2) + "/" + hex.substring(2, 4);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BlockId other && Arrays.equals(hash, other.hash);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(hash);
    }

    @Override
    public String toString() {
        return "BlockId[" + hexString().substring(0, 12) + "...]";
    }
}
