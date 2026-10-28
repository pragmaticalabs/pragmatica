package org.pragmatica.storage;

import java.security.SecureRandom;
import java.util.Arrays;

import org.pragmatica.lang.Result;


/// A generated storage key: 16 random bytes, written as 26 lowercase base32 characters (RFC 4648 alphabet, no padding).
/// Never derived from a filename or from content.
public record ObjectId(byte[] bytes) {
    public static final int LENGTH = 16;
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    public ObjectId {
        bytes = bytes.clone();
    }

    public static ObjectId objectId() {
        var bytes = new byte[LENGTH];

        RANDOM.nextBytes(bytes);

        return new ObjectId(bytes);
    }

    /// The id written as `text`. Only the canonical spelling parses, so one id has exactly one ref key.
    public static Result<ObjectId> objectId(String text) {
        return decode(text).filter(ContentStoreError.InvalidIdentifier.invalidIdentifier("object id",
                                                                                         "not a canonical base32 id: " + text),
                                   id -> id.asString()
                                           .equals(text));
    }

    private static Result<ObjectId> decode(String text) {
        if (text.length() != 26) {
            return ContentStoreError.InvalidIdentifier.invalidIdentifier("object id", "must be 26 characters").result();
        }

        var bytes = new byte[LENGTH];
        var buffer = 0;
        var bits = 0;
        var out = 0;

        for (var i = 0; i < text.length(); i++) {
            var digit = ALPHABET.indexOf(text.charAt(i));

            if (digit < 0) {
                return ContentStoreError.InvalidIdentifier.invalidIdentifier("object id", "unknown character").result();
            }

            buffer = (buffer << 5) | digit;
            bits += 5;
            if (bits >= 8 && out < LENGTH) {
                bits -= 8;
                bytes[out++] = (byte)(buffer >> bits);
                buffer &= (1 << bits) - 1;
            }
        }

        return Result.success(new ObjectId(bytes));
    }

    public String asString() {
        var builder = new StringBuilder(26);
        var buffer = 0;
        var bits = 0;

        for (var b : bytes) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                builder.append(ALPHABET.charAt((buffer >> bits) & 31));
            }

            buffer &= (1 << bits) - 1;
        }

        if (bits > 0) {
            builder.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        }

        return builder.toString();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ObjectId other && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "ObjectId[" + asString() + "]";
    }
}
