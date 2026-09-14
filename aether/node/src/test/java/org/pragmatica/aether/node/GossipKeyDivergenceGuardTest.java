// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.pragmatica.swim.AesGcmGossipEncryptor;
import org.pragmatica.swim.GossipEncryptor;

import static org.assertj.core.api.Assertions.assertThat;

/// #683 — the boot gate that turns a silent unjoinable node into a refused boot, and the arming
/// window that stops the gate itself being a remote kill switch.
///
/// Every arm drives REAL encryptors: a "cluster" encryptor on a rotated key and a "joiner" on the
/// derived key, so the ciphertext really does carry an unheld key id. Nothing is hand-fed.
///
/// The clock is injected (monotonic nanos), so the 60-second boundary is crossed without sleeping.
class GossipKeyDivergenceGuardTest {
    private static final byte[] DERIVED_KEY = filled((byte) 0x11);
    private static final int DERIVED_KEY_ID = 20260914;
    private static final byte[] ROTATED_KEY = filled((byte) 0x22);
    private static final int ROTATED_KEY_ID = 1;
    private static final byte[] PROBE = "swim-ping".getBytes();
    /// Comfortably inside `[ARMING_DELAY, ARMING_WINDOW_END]`.
    private static final long ARMED = GossipKeyDivergenceGuard.ARMING_DELAY_NANOS + 1;

    private final AtomicLong clock = new AtomicLong();
    private final AtomicInteger refusals = new AtomicInteger();

    // ---- the gate still does its job, inside the window ----

    @Test
    void insideTheWindow_unknownKeyIdPastTheThreshold_refusesTheBoot() {
        var guard = guard();

        clock.set(ARMED);
        feed(guard, fromRotatedCluster(), GossipKeyDivergenceGuard.UNKNOWN_KEY_THRESHOLD - 1);

        assertThat(refusals.get()).as("below the threshold a stray datagram must not kill a healthy node")
                                  .isZero();

        feed(guard, fromRotatedCluster(), 1);

        assertThat(refusals.get()).as("#683: at the threshold, inside the window, the boot is refused")
                                  .isEqualTo(1);
    }

    @Test
    void refusal_firesOnce_notPerDatagram() {
        var guard = guard();

        clock.set(ARMED);
        feed(guard, fromRotatedCluster(), GossipKeyDivergenceGuard.UNKNOWN_KEY_THRESHOLD * 3);

        assertThat(refusals.get()).isEqualTo(1);
    }

    // ---- the arming window: the fix for the 8-packet remote kill ----

    /// BLOCKING, delta review: eight 16-byte junk datagrams carrying one repeated arbitrary key id
    /// ended a booting process — off-path, spoofable, no reply read, and crash-looping under a restart
    /// supervisor. `NettySwimTransport` decrypts from any sender with no source check and the default
    /// firewall preset opens SWIM UDP to `0.0.0.0/0`, so this needed no privileged position.
    @Test
    void beforeTheArmingDelay_aBurstCannotRefuseTheBoot() {
        var guard = guard();

        clock.set(GossipKeyDivergenceGuard.ARMING_DELAY_NANOS - 1);
        feed(guard, fromRotatedCluster(), GossipKeyDivergenceGuard.UNKNOWN_KEY_THRESHOLD * 10);

        assertThat(refusals.get()).as("#683: a burst before the arming delay must not end the process")
                                  .isZero();
    }

    /// The banking attack the reset exists to stop: fill the run just under the threshold while
    /// unarmed, then deliver the last datagram once the window opens.
    @Test
    void datagramsBeforeTheWindow_doNotAccumulateIntoIt() {
        var guard = guard();

        clock.set(GossipKeyDivergenceGuard.ARMING_DELAY_NANOS - 1);
        feed(guard, fromRotatedCluster(), GossipKeyDivergenceGuard.UNKNOWN_KEY_THRESHOLD - 1);

        clock.set(ARMED);
        feed(guard, fromRotatedCluster(), 1);

        assertThat(refusals.get()).as("#683: pre-window datagrams must not be bankable into the window")
                                  .isZero();
    }

    /// SHOULD-FIX 2: without an upper bound a node that never decrypts stays armed for life — and
    /// that is exactly the auto-heal replacement SECURITY.md describes, so the most exposed node
    /// would be the one that stays killable longest.
    @Test
    void afterTheWindowCloses_theGateIsDisarmed() {
        var guard = guard();

        clock.set(GossipKeyDivergenceGuard.ARMING_WINDOW_END_NANOS + 1);
        feed(guard, fromRotatedCluster(), GossipKeyDivergenceGuard.UNKNOWN_KEY_THRESHOLD * 10);

        assertThat(refusals.get()).as("#683 SHOULD-FIX 2: the arming window must close, not last for life")
                                  .isZero();
    }

    // ---- properties that must survive the window change ----

    /// One successful decrypt proves key agreement, so the guard must never fire afterwards however
    /// much undecryptable traffic follows — even squarely inside the window.
    @Test
    void oneSuccessfulDecrypt_disarmsTheGuardPermanently() {
        var guard = guard();

        clock.set(ARMED);

        assertThat(guard.decrypt(joinerEncryptor().encrypt(PROBE).unwrap()).isSuccess())
                .as("control: agreeing traffic decrypts")
                .isTrue();

        feed(guard, fromRotatedCluster(), GossipKeyDivergenceGuard.UNKNOWN_KEY_THRESHOLD * 3);

        assertThat(refusals.get()).as("#683: a node that has ever decrypted has key agreement")
                                  .isZero();
    }

    /// Junk under VARYING key ids is not the divergence signature — a rotated cluster repeats one id.
    @Test
    void variedJunkInsideTheWindow_doesNotRefuseTheBoot() {
        var guard = guard();

        clock.set(ARMED);

        for (var i = 0; i < GossipKeyDivergenceGuard.UNKNOWN_KEY_THRESHOLD * 3; i++) {
            guard.decrypt(junkWithKeyId(i));
        }

        assertThat(refusals.get()).as("#683: junk under varying key ids is not divergence")
                                  .isZero();
    }

    @Test
    void aDifferingKeyId_resetsTheRun() {
        var guard = guard();

        clock.set(ARMED);

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

    /// The residual that remains INSIDE the window, pinned rather than hidden: junk repeating one key
    /// id is indistinguishable from a rotated peer by this signal alone. The arming window is what
    /// bounds it — an attacker must now sustain the no-decrypt condition for a minute rather than
    /// send eight packets at a booting node.
    @Test
    void junkRepeatingOneKeyId_insideTheWindow_isStillIndistinguishableFromARotatedPeer() {
        var guard = guard();

        clock.set(ARMED);

        for (var i = 0; i < GossipKeyDivergenceGuard.UNKNOWN_KEY_THRESHOLD; i++) {
            guard.decrypt(junkWithKeyId(7));
        }

        assertThat(refusals.get()).as("known residual, bounded by the arming window")
                                  .isEqualTo(1);
    }

    @Test
    void encryptIsDelegatedUnchanged() {
        var guard = guard();

        assertThat(joinerEncryptor().decrypt(guard.encrypt(PROBE).unwrap()).unwrap())
                .as("the guard observes; it must not alter the wire")
                .isEqualTo(PROBE);
    }

    private GossipKeyDivergenceGuard guard() {
        return GossipKeyDivergenceGuard.gossipKeyDivergenceGuard(joinerEncryptor(),
                                                                  refusals::incrementAndGet,
                                                                  clock::get);
    }

    private static void feed(GossipKeyDivergenceGuard guard, byte[] datagram, int count) {
        for (var i = 0; i < count; i++) {
            guard.decrypt(datagram);
        }
    }

    private static byte[] fromRotatedCluster() {
        return AesGcmGossipEncryptor.aesGcmGossipEncryptor(ROTATED_KEY, ROTATED_KEY_ID)
                                    .unwrap()
                                    .encrypt(PROBE)
                                    .unwrap();
    }

    private static GossipEncryptor joinerEncryptor() {
        return AesGcmGossipEncryptor.aesGcmGossipEncryptor(DERIVED_KEY, DERIVED_KEY_ID).unwrap();
    }

    /// A datagram long enough to parse, carrying `keyId` in the first 4 bytes big-endian and garbage
    /// after it — the shape unrelated UDP traffic presents to the encryptor.
    private static byte[] junkWithKeyId(int keyId) {
        var datagram = new byte[40];

        java.nio.ByteBuffer.wrap(datagram).putInt(keyId);

        return datagram;
    }

    private static byte[] filled(byte value) {
        var key = new byte[32];

        java.util.Arrays.fill(key, value);

        return key;
    }
}
