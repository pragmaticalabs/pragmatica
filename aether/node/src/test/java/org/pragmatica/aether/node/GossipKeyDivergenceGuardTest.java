// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.swim.AesGcmGossipEncryptor;
import org.pragmatica.swim.GossipEncryptor;

import static org.assertj.core.api.Assertions.assertThat;

/// #683 — the boot gate that turns a silent unjoinable node into a refused boot.
///
/// Every arm drives REAL encryptors: a "cluster" encryptor on a rotated key and a "joiner" on the
/// derived key, so the ciphertext really does carry an unheld key id. Nothing is hand-fed.
class GossipKeyDivergenceGuardTest {
    private static final byte[] DERIVED_KEY = filled((byte) 0x11);
    private static final int DERIVED_KEY_ID = 20260914;
    private static final byte[] ROTATED_KEY = filled((byte) 0x22);
    private static final int ROTATED_KEY_ID = 1;
    private static final byte[] PROBE = "swim-ping".getBytes();

    @Test
    void unknownKeyIdDatagrams_pastTheThreshold_refuseTheBoot() {
        var refusals = new AtomicInteger();
        var guard = GossipKeyDivergenceGuard.gossipKeyDivergenceGuard(joinerEncryptor(), refusals::incrementAndGet);
        var fromRotatedCluster = rotatedClusterEncryptor().encrypt(PROBE).unwrap();

        for (var i = 0; i < GossipKeyDivergenceGuard.UNKNOWN_KEY_THRESHOLD - 1; i++) {
            guard.decrypt(fromRotatedCluster);
        }

        assertThat(refusals.get()).as("below the threshold a stray datagram must not kill a healthy node")
                                  .isZero();

        guard.decrypt(fromRotatedCluster);

        assertThat(refusals.get()).as("#683: at the threshold the boot is refused")
                                  .isEqualTo(1);
    }

    /// Fires ONCE. A per-datagram refusal would bury the one line an operator needs under the flood
    /// that is itself the symptom.
    @Test
    void refusal_firesOnce_notPerDatagram() {
        var refusals = new AtomicInteger();
        var guard = GossipKeyDivergenceGuard.gossipKeyDivergenceGuard(joinerEncryptor(), refusals::incrementAndGet);
        var fromRotatedCluster = rotatedClusterEncryptor().encrypt(PROBE).unwrap();

        for (var i = 0; i < GossipKeyDivergenceGuard.UNKNOWN_KEY_THRESHOLD * 3; i++) {
            guard.decrypt(fromRotatedCluster);
        }

        assertThat(refusals.get()).isEqualTo(1);
    }

    /// The disarm is what keeps this a BOOT gate and what makes a live rotation safe: one successful
    /// decrypt proves key agreement, so the guard must never fire afterwards however much
    /// undecryptable traffic follows.
    @Test
    void oneSuccessfulDecrypt_disarmsTheGuardPermanently() {
        var refusals = new AtomicInteger();
        var guard = GossipKeyDivergenceGuard.gossipKeyDivergenceGuard(joinerEncryptor(), refusals::incrementAndGet);
        var ownTraffic = joinerEncryptor().encrypt(PROBE).unwrap();
        var fromRotatedCluster = rotatedClusterEncryptor().encrypt(PROBE).unwrap();

        assertThat(guard.decrypt(ownTraffic).isSuccess()).as("control: agreeing traffic decrypts")
                                                         .isTrue();

        for (var i = 0; i < GossipKeyDivergenceGuard.UNKNOWN_KEY_THRESHOLD * 3; i++) {
            guard.decrypt(fromRotatedCluster);
        }

        assertThat(refusals.get()).as("#683: a node that has ever decrypted has key agreement and must never be refused")
                                  .isZero();
    }

    /// Scan traffic must not kill a booting node. A malformed datagram is NOT rejected as malformed:
    /// anything at least as long as the 16-byte header parses, so arbitrary junk's first 4 bytes are
    /// read as a key id and yield `UnknownKeyId` exactly like a rotated peer. What separates them is
    /// that a rotated cluster repeats ONE id while junk does not — so this arm varies the id.
    ///
    /// This test found the weakness: the first implementation counted bare `UnknownKeyId` and fired
    /// on junk, which would have made a `System.exit` gate remotely trippable.
    @Test
    void variedJunkOnTheSwimPort_doesNotRefuseTheBoot() {
        var refusals = new AtomicInteger();
        var guard = GossipKeyDivergenceGuard.gossipKeyDivergenceGuard(joinerEncryptor(), refusals::incrementAndGet);

        for (var i = 0; i < GossipKeyDivergenceGuard.UNKNOWN_KEY_THRESHOLD * 3; i++) {
            guard.decrypt(junkWithKeyId(i));
        }

        assertThat(refusals.get()).as("#683: junk under varying key ids is not the divergence signature")
                                  .isZero();
    }

    /// The control for the arm above, and the residual it leaves: junk that repeats ONE key id is
    /// indistinguishable from a rotated peer by this signal alone, so it DOES trip the gate. Pinned
    /// deliberately — this is the gate's known false-positive surface, disclosed rather than hidden.
    @Test
    void junkRepeatingOneKeyId_isIndistinguishableFromARotatedPeer_andDoesRefuse() {
        var refusals = new AtomicInteger();
        var guard = GossipKeyDivergenceGuard.gossipKeyDivergenceGuard(joinerEncryptor(), refusals::incrementAndGet);

        for (var i = 0; i < GossipKeyDivergenceGuard.UNKNOWN_KEY_THRESHOLD; i++) {
            guard.decrypt(junkWithKeyId(7));
        }

        assertThat(refusals.get()).as("known residual: one repeated unknown key id reads as divergence")
                                  .isEqualTo(1);
    }

    /// An interleaved differing key id resets the run, so an attacker cannot accumulate the gate
    /// across unrelated traffic.
    @Test
    void aDifferingKeyId_resetsTheRun() {
        var refusals = new AtomicInteger();
        var guard = GossipKeyDivergenceGuard.gossipKeyDivergenceGuard(joinerEncryptor(), refusals::incrementAndGet);

        for (var i = 0; i < GossipKeyDivergenceGuard.UNKNOWN_KEY_THRESHOLD - 1; i++) {
            guard.decrypt(junkWithKeyId(7));
        }

        guard.decrypt(junkWithKeyId(9));

        for (var i = 0; i < GossipKeyDivergenceGuard.UNKNOWN_KEY_THRESHOLD - 1; i++) {
            guard.decrypt(junkWithKeyId(7));
        }

        assertThat(refusals.get()).as("#683: the run must restart when the key id changes")
                                  .isZero();
    }

    @Test
    void encryptIsDelegatedUnchanged() {
        var guard = GossipKeyDivergenceGuard.gossipKeyDivergenceGuard(joinerEncryptor(), () -> {});

        assertThat(joinerEncryptor().decrypt(guard.encrypt(PROBE).unwrap()).unwrap())
                .as("the guard observes; it must not alter the wire")
                .isEqualTo(PROBE);
    }

    /// A datagram long enough to parse, carrying `keyId` in the first 4 bytes big-endian and garbage
    /// after it — the shape unrelated UDP traffic presents to the encryptor.
    private static byte[] junkWithKeyId(int keyId) {
        var datagram = new byte[40];

        java.nio.ByteBuffer.wrap(datagram).putInt(keyId);

        return datagram;
    }

    private static GossipEncryptor joinerEncryptor() {
        return AesGcmGossipEncryptor.aesGcmGossipEncryptor(DERIVED_KEY, DERIVED_KEY_ID).unwrap();
    }

    private static GossipEncryptor rotatedClusterEncryptor() {
        return AesGcmGossipEncryptor.aesGcmGossipEncryptor(ROTATED_KEY, ROTATED_KEY_ID).unwrap();
    }

    private static byte[] filled(byte value) {
        var key = new byte[32];

        java.util.Arrays.fill(key, value);

        return key;
    }
}
