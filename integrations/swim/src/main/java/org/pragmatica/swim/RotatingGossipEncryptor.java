package org.pragmatica.swim;

import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.lang.Result;

/// Delegating gossip encryptor that supports hot-swap for key rotation.
///
/// Wraps an inner [GossipEncryptor] in an [AtomicReference], allowing the
/// encryption keys to be rotated at runtime without restarting SWIM transport.
/// The delegate is swapped atomically — in-flight encrypt/decrypt operations
/// see either the old or new encryptor, never a torn state.
public final class RotatingGossipEncryptor implements GossipEncryptor {
    private final AtomicReference<GossipEncryptor> delegate;

    private RotatingGossipEncryptor(GossipEncryptor initial) {
        this.delegate = new AtomicReference<>(initial);
    }

    /// Create a rotating encryptor with an initial delegate.
    public static RotatingGossipEncryptor rotatingGossipEncryptor(GossipEncryptor initial) {
        return new RotatingGossipEncryptor(initial);
    }

    /// Atomically replace the inner encryptor with a new one.
    /// During rotation the new encryptor should accept both old and new key IDs
    /// (use [AesGcmGossipEncryptor] dual-key factory).
    public void rotate(GossipEncryptor newEncryptor) {
        delegate.set(newEncryptor);
    }

    @Override
    public Result<byte[]> encrypt(byte[] plaintext) {
        return delegate.get().encrypt(plaintext);
    }

    @Override
    public Result<byte[]> decrypt(byte[] ciphertext) {
        return delegate.get().decrypt(ciphertext);
    }
}
