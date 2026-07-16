// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.stream.Stream;

import org.pragmatica.lang.Option;
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
public sealed interface SwimGossipEncryptors {
    /// Build the cluster gossip encryptor from a certificate provider.
    ///
    /// Provider present → [RotatingGossipEncryptor] wrapping an [AesGcmGossipEncryptor]
    /// keyed on the provider's current/previous/next gossip keys. Provider absent (or no
    /// current key) → [RotatingGossipEncryptor] wrapping [GossipEncryptor#none] — the
    /// insecure dev-mode pass-through.
    static RotatingGossipEncryptor fromCertificateProvider(Option<CertificateProvider> certificateProvider) {
        return RotatingGossipEncryptor.rotatingGossipEncryptor(certificateProvider.flatMap(SwimGossipEncryptors::buildDualKeyEncryptor)
                                                                                  .or(GossipEncryptor.none()));
    }

    private static Option<GossipEncryptor> buildDualKeyEncryptor(CertificateProvider provider) {
        return provider.currentGossipKey()
                       .option()
                       .flatMap(current -> buildEncryptorFromKeys(current,
                                                                  provider.previousGossipKey(),
                                                                  provider.nextGossipKey()));
    }

    /// Encrypts under `current`, accepts `current` plus the previous-day and next-day keys
    /// when present. The next-day key widens the accept window across the UTC-midnight
    /// boundary so a peer already on the next day's key is decryptable here (#256).
    private static Option<GossipEncryptor> buildEncryptorFromKeys(GossipKey current,
                                                                  Option<GossipKey> previous,
                                                                  Option<GossipKey> next) {
        var additional = Stream.of(previous, next)
                               .flatMap(Option::stream)
                               .map(SwimGossipEncryptors::acceptedKey)
                               .toList();

        return AesGcmGossipEncryptor.aesGcmGossipEncryptor(current.key(),
                                                           current.keyId(),
                                                           additional)
                                    .option();
    }

    private static AesGcmGossipEncryptor.AcceptedKey acceptedKey(GossipKey key) {
        return new AesGcmGossipEncryptor.AcceptedKey(key.keyId(), key.key());
    }

    record unused() implements SwimGossipEncryptors {}
}
