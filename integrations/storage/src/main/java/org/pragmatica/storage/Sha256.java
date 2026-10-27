package org.pragmatica.storage;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// A SHA-256 digest of a whole object, 32 bytes. Immutable and suitable as a map key.
public record Sha256(byte[] bytes) {
    public static final int LENGTH = 32;
    private static final HexFormat HEX = HexFormat.of();

    public Sha256 {
        bytes = bytes.clone();
    }

    /// The digest of `content`.
    public static Result<Sha256> sha256(byte[] content) {
        return Result.lift(() -> MessageDigest.getInstance("SHA-256")).map(digest -> digest.digest(content))
                     .map(Sha256::new);
    }

    /// A digest from its 64 hex characters.
    public static Result<Sha256> fromHex(String hex) {
        return Result.lift(() -> HEX.parseHex(hex))
                     .filter(Causes.cause("A SHA-256 is 64 hex characters"), bytes -> bytes.length == LENGTH)
                     .map(Sha256::new);
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    public String hexString() {
        return HEX.formatHex(bytes);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Sha256 other && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "Sha256[" + hexString().substring(0, 12) + "...]";
    }
}
