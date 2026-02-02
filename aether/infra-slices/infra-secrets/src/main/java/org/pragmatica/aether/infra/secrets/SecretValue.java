package org.pragmatica.aether.infra.secrets;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A secret value with secure handling.
 * The value is stored as bytes and cleared when no longer needed.
 */
public final class SecretValue implements AutoCloseable {
    private byte[] value;
    private boolean cleared;

    private SecretValue(byte[] value) {
        this.value = value.clone();
        // Defensive copy
        this.cleared = false;
    }

    /**
     * Creates a secret value from bytes.
     */
    public static SecretValue secretValue(byte[] value) {
        return new SecretValue(value);
    }

    /**
     * Creates a secret value from a string.
     */
    public static SecretValue secretValue(String value) {
        return new SecretValue(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns the secret value as bytes.
     * Returns empty array if cleared.
     */
    public byte[] asBytes() {
        if (cleared) {
            return new byte[0];
        }
        return value.clone();
    }

    /**
     * Returns the secret value as a string.
     * Returns empty string if cleared.
     */
    public String asString() {
        if (cleared) {
            return "";
        }
        return new String(value, StandardCharsets.UTF_8);
    }

    /**
     * Returns whether this secret has been cleared.
     */
    public boolean isCleared() {
        return cleared;
    }

    /**
     * Securely clears the secret value from memory.
     */
    public void clear() {
        if (!cleared && value != null) {
            Arrays.fill(value, (byte) 0);
            cleared = true;
        }
    }

    @Override
    public void close() {
        clear();
    }

    @Override
    public String toString() {
        return "SecretValue[REDACTED]";
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        SecretValue that = (SecretValue) obj;
        if (cleared || that.cleared) return false;
        return Arrays.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        if (cleared) return 0;
        return Arrays.hashCode(value);
    }
}
