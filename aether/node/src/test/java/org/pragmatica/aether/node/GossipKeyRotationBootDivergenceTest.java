// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.time.Instant;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey.GossipKeyRotationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.GossipKeyRotationValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.net.tcp.security.CertificateBundle;
import org.pragmatica.net.tcp.security.CertificateProvider;
import org.pragmatica.net.tcp.security.GossipKey;

import static org.assertj.core.api.Assertions.assertThat;

/// #683 TRIPWIRE — pins the boot-path key divergence a KV gossip-key rotation opens.
///
/// [SwimGossipEncryptors]' class doc states the invariant: the running node's SWIM transport and
/// the boot-time self-address reflection probe ([org.pragmatica.aether.SelfAddressResolver]) MUST
/// encrypt gossip with IDENTICAL key material. Both production construction sites
/// (`Main.createGossipEncryptor`, `AetherNode.createGossipEncryptor`) build from the
/// `cluster_secret`-derived keys, but only the second is reachable by
/// [org.pragmatica.swim.RotatingGossipEncryptor#rotate], and `rotate` REPLACES the delegate — the
/// replacement's accept set is exactly the KV record's `{currentKeyId, previousKeyId}`, so the
/// derived keyId leaves it.
///
/// These tests assert the CURRENT (defective) behaviour, deliberately: they are a tripwire, not a
/// specification. They RED the moment a joiner-key-delivery mechanism lands, and the failure
/// message says what to do. A `@Disabled` test would sit forgotten instead.
///
/// Both sides are built independently through the real production factories — never hand-fed an
/// expected outcome — so the assertions can falsify the premise rather than restate it.
class GossipKeyRotationBootDivergenceTest {
    private static final byte[] DERIVED_KEY = filled((byte) 0x11);
    private static final int DERIVED_KEY_ID = 20260914;
    private static final byte[] ROTATED_KEY = filled((byte) 0x22);
    private static final int ROTATED_KEY_ID = 1;
    private static final byte[] PROBE = "swim-whoami".getBytes();

    /// The cluster has rotated; a node booting afterwards still derives its key from
    /// `cluster_secret`. Its datagram carries the derived keyId, which the rotated accept set does
    /// not contain, so every peer drops it (`NettySwimTransport` drops on decrypt failure).
    @Test
    void afterRotation_clusterCannotDecryptALaterBootingNode() {
        var cluster = rotatedClusterEncryptor();
        var joiner = bootEncryptor();

        var datagram = joiner.encrypt(PROBE).unwrap();

        assertThat(cluster.decrypt(datagram).isSuccess())
                .as("""
                    #683 TRIPWIRE: a rotated cluster cannot decrypt a later-booting node's gossip, so \
                    SWIM never discovers it. If this is now TRUE the joiner-key-delivery mechanism has \
                    landed — delete this test and pin the new behaviour instead.""")
                .isFalse();
    }

    /// The reverse direction, which is what makes the divergence unrecoverable: the joiner cannot
    /// decrypt the cluster's replies either. Carrying the derived key as `previousKey` on the first
    /// rotation would open the joiner->cluster direction only; this assertion is the one that shows
    /// why that alone cannot heal a joiner.
    @Test
    void afterRotation_aLaterBootingNodeCannotDecryptTheCluster() {
        var cluster = rotatedClusterEncryptor();
        var joiner = bootEncryptor();

        var reply = cluster.encrypt(PROBE).unwrap();

        assertThat(joiner.decrypt(reply).isSuccess())
                .as("""
                    #683 TRIPWIRE: a later-booting node cannot decrypt the rotated cluster's replies, \
                    so it receives no SWIM discovery and never reaches the consensus sync that would \
                    deliver the KV record. If this is now TRUE the delivery mechanism has landed.""")
                .isFalse();
    }

    /// Positive control: WITHOUT a rotation the two sites agree, which is the invariant
    /// [SwimGossipEncryptors] exists to hold. Without this, the two assertions above would pass
    /// just as well against a broken encryptor that never decrypts anything.
    @Test
    void withoutRotation_bothSitesAgree() {
        var cluster = bootEncryptor();
        var joiner = bootEncryptor();

        assertThat(cluster.decrypt(joiner.encrypt(PROBE).unwrap()).unwrap())
                .as("control: the two boot-path construction sites encrypt with identical material")
                .isEqualTo(PROBE);
        assertThat(joiner.decrypt(cluster.encrypt(PROBE).unwrap()).unwrap())
                .as("control: and in both directions")
                .isEqualTo(PROBE);
    }

    /// The node's live encryptor after one `POST /cluster/gossip-key/rotate` has been applied by
    /// the real consumer.
    private static org.pragmatica.swim.GossipEncryptor rotatedClusterEncryptor() {
        var encryptor = bootEncryptor();

        GossipKeyRotationHandler.gossipKeyRotationHandler(encryptor)
                                .onGossipKeyRotationPut(rotationPut());

        return encryptor;
    }

    /// Exactly what `Main.createGossipEncryptor` and `AetherNode.createGossipEncryptor` build.
    private static org.pragmatica.swim.RotatingGossipEncryptor bootEncryptor() {
        return SwimGossipEncryptors.fromCertificateProvider(Option.some(derivedKeyProvider()));
    }

    private static ValuePut<GossipKeyRotationKey, GossipKeyRotationValue> rotationPut() {
        var value = GossipKeyRotationValue.gossipKeyRotationValue(ROTATED_KEY_ID,
                                                                  Base64.getEncoder().encodeToString(ROTATED_KEY));

        return new ValuePut<>(new KVCommand.Put<>(GossipKeyRotationKey.gossipKeyRotationKey(), value),
                              Option.none());
    }

    /// A `cluster_secret`-derived provider: a single current key, no previous/next, which is what a
    /// node booting on one UTC day holds.
    private static CertificateProvider derivedKeyProvider() {
        return new CertificateProvider() {
            @Override
            public Result<CertificateBundle> issueCertificate(String nodeId, String hostname) {
                return Result.success(null);
            }

            @Override
            public Result<CertificateBundle> caCertificate() {
                return Result.success(null);
            }

            @Override
            public Result<GossipKey> currentGossipKey() {
                return Result.success(GossipKey.gossipKey(DERIVED_KEY, DERIVED_KEY_ID, Instant.EPOCH));
            }

            @Override
            public Option<GossipKey> previousGossipKey() {
                return Option.none();
            }
        };
    }

    private static byte[] filled(byte value) {
        var key = new byte[32];

        java.util.Arrays.fill(key, value);

        return key;
    }
}
