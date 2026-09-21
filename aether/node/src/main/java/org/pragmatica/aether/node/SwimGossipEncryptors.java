// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.net.tcp.security.CertificateProvider;
import org.pragmatica.net.tcp.security.GossipKey;
import org.pragmatica.swim.AesGcmGossipEncryptor;
import org.pragmatica.swim.GossipEncryptor;
import org.pragmatica.swim.RotatingGossipEncryptor;


/// Single source of truth for SWIM gossip-encryptor construction.
///
/// Both the running node's SWIM transport ([AetherNode] boot path) and the boot-time
/// self-address reflection probe ([org.pragmatica.aether.SelfAddressResolver]) MUST encrypt
/// gossip with the IDENTICAL key material, or a seed answering a `WhoAmI` reflection would
/// silently fail to decrypt it. The encryptor recipe — AES-256-GCM under the cluster's
/// current gossip key, accepting the previous/next-day keys for the UTC-midnight rotation
/// overlap (#256), or the no-op pass-through when no certificate provider is configured
/// (insecure dev-mode) — is therefore factored out here so the two call sites can never drift.
///
/// #1164: the derived-key encryptor FOLLOWS the provider rather than snapshotting it. The
/// provider's current key is the day's key ([org.pragmatica.net.tcp.security.SelfSignedCertificateProvider]
/// re-derives it on a day change), and [ProviderKeyedGossipEncryptor] rebuilds its AES-GCM
/// delegate whenever the provider's current key id differs from the one it was built from. So two
/// nodes whose clocks agree to within one day always share a key, regardless of uptime: each
/// encrypts under its own current day and accepts the previous and next, and their days differ by
/// at most one. The accept window itself is unchanged — a two-day skew is still rejected.
public sealed interface SwimGossipEncryptors {
    /// Build the cluster gossip encryptor from a certificate provider.
    ///
    /// Provider present → [RotatingGossipEncryptor] wrapping a [ProviderKeyedGossipEncryptor]
    /// over the provider's current/previous/next gossip keys. Provider absent (or no
    /// current key) → [RotatingGossipEncryptor] wrapping [GossipEncryptor#none] — the
    /// insecure dev-mode pass-through.
    static RotatingGossipEncryptor fromCertificateProvider(Option<CertificateProvider> certificateProvider) {
        return RotatingGossipEncryptor.rotatingGossipEncryptor(certificateProvider.flatMap(SwimGossipEncryptors::buildProviderKeyedEncryptor)
                                                                                  .or(GossipEncryptor.none()));
    }

    private static Option<GossipEncryptor> buildProviderKeyedEncryptor(CertificateProvider provider) {
        return buildDualKeyEncryptor(provider).map(initial -> new ProviderKeyedGossipEncryptor(provider, initial));
    }

    private static Option<KeyedEncryptor> buildDualKeyEncryptor(CertificateProvider provider) {
        return provider.currentGossipKey()
                       .option()
                       .flatMap(current -> buildEncryptorFromKeys(current,
                                                                  provider.previousGossipKey(),
                                                                  provider.nextGossipKey()));
    }

    /// Encrypts under `current`, accepts `current` plus the previous-day and next-day keys
    /// when present. The next-day key widens the accept window across the UTC-midnight
    /// boundary so a peer already on the next day's key is decryptable here (#256).
    private static Option<KeyedEncryptor> buildEncryptorFromKeys(GossipKey current,
                                                                 Option<GossipKey> previous,
                                                                 Option<GossipKey> next) {
        var additional = Stream.of(previous, next)
                               .flatMap(Option::stream)
                               .map(SwimGossipEncryptors::acceptedKey)
                               .toList();

        return AesGcmGossipEncryptor.aesGcmGossipEncryptor(current.key(),
                                                           current.keyId(),
                                                           additional)
                                    .map(encryptor -> new KeyedEncryptor(current.keyId(),
                                                                         encryptor))
                                    .option();
    }

    private static AesGcmGossipEncryptor.AcceptedKey acceptedKey(GossipKey key) {
        return new AesGcmGossipEncryptor.AcceptedKey(key.keyId(), key.key());
    }

    /// An AES-GCM encryptor tagged with the key id it encrypts under, so a later provider read can
    /// be compared against it without touching key material.
    record KeyedEncryptor(int keyId, GossipEncryptor encryptor) {}

    /// #1164: derived-key encryptor that tracks the provider's CURRENT key. Every operation reads
    /// the provider's current key id; while it matches the delegate's the delegate is reused, and
    /// when it differs (the provider's day has rolled over) the delegate is rebuilt from the
    /// provider's current/previous/next keys and swapped in. A rebuild failure keeps the previous
    /// delegate, so a transient derivation error degrades to the old accept window rather than to
    /// no encryption. The three provider reads are not one atomic snapshot: a day change landing
    /// between them yields a delegate whose window misses one day for the datagrams handled before
    /// the next read, which then rebuilds it — SWIM tolerates a dropped datagram, so this is
    /// accepted rather than locked around.
    ///
    /// This is the delegate INSIDE the node's [RotatingGossipEncryptor], so a KV gossip-key rotation
    /// (#683, [GossipKeyRotationHandler]) replaces it wholesale and the derived scheme stops
    /// following the day — exactly the "rotation supersedes the derived key" contract SECURITY.md
    /// states.
    final class ProviderKeyedGossipEncryptor implements GossipEncryptor {
        private final CertificateProvider provider;
        private final AtomicReference<KeyedEncryptor> delegate;

        private ProviderKeyedGossipEncryptor(CertificateProvider provider, KeyedEncryptor initial) {
            this.provider = provider;
            this.delegate = new AtomicReference<>(initial);
        }

        @Override
        public Result<byte[]> encrypt(byte[] plaintext) {
            return current().encrypt(plaintext);
        }

        @Override
        public Result<byte[]> decrypt(byte[] ciphertext) {
            return current().decrypt(ciphertext);
        }

        private GossipEncryptor current() {
            var cached = delegate.get();

            return provider.currentGossipKey()
                           .option()
                           .filter(key -> key.keyId() != cached.keyId())
                           .flatMap(_ -> buildDualKeyEncryptor(provider))
                           .onPresent(fresh -> delegate.compareAndSet(cached, fresh))
                           .or(cached)
                           .encryptor();
        }
    }

    record unused() implements SwimGossipEncryptors {}
}
