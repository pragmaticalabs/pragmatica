// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.swim.GossipEncryptor;
import org.pragmatica.swim.GossipEncryptionError;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// #683 — boot gate for gossip-key divergence: refuse loudly instead of sitting in a silent
/// unjoinable state.
///
/// **The state this guards.** A KV gossip-key rotation replaces the live encryptor's accept set with
/// the record's `{currentKeyId, previousKeyId}` ([GossipKeyRotationHandler]), so the
/// `cluster_secret`-derived keyId leaves it. A node booting afterwards derives its key from
/// `cluster_secret` and can neither be understood by the cluster nor understand it. SWIM therefore
/// discovers nothing, the QUIC dial set stays self-only (SWIM is its sole writer besides self), no
/// quorum forms, and the consensus replay that carries the rotation record — the only way to obtain
/// the cluster key — never runs. The node cannot join, and without this gate it never says so.
///
/// **What the signal is.** `UnknownKeyId` means a peer encrypted under a key epoch this node does not
/// hold. It is raised by `AesGcmGossipEncryptor.resolveKey` and, before this class, was consumed
/// nowhere: `NettySwimTransport` logged one indistinguishable WARN per datagram and dropped it.
///
/// **Why a successful decrypt disarms this permanently.** One successful decrypt proves key agreement,
/// so the guard can only ever fire on a node that has never once decrypted a gossip datagram while
/// receiving several it cannot — which is the divergence signature and nothing else. That also makes
/// it a BOOT gate without needing a clock: a joined node has decrypted. A live rotation is applied to
/// the delegate beneath this decorator, so traffic keeps decrypting and the guard stays disarmed.
///
/// **Threshold AND an arming window**, because the threshold alone was not enough: eight spoofable
/// junk datagrams were shown to end a booting process. See [#ARMING_DELAY_NANOS].
///
/// **Coverage — this gate is a determination for some boots and blind for others, by construction.**
/// It fires only if the cluster SENDS to this node. Peers probe their configured seed set, so a
/// RESTARTED member is probed and the divergence is detected precisely. A node the cluster has never
/// heard of — a CTM auto-heal replacement or a scale-up node — is probed by nobody: its own datagrams
/// are dropped undecrypted by the seeds, which therefore never reply, so it receives NOTHING and this
/// guard cannot fire. In that case the only signal is the `Failed to decrypt gossip from ...` WARN on
/// the healthy seeds, not on the stranded node. SECURITY.md states this.
/// **The exit path is MEASURED, not assumed** (#683 round 2). `refuse` runs on a Netty event-loop
/// thread, and #838 proved by probe that `System.exit` from inside a shutdown HOOK parks the JVM
/// forever — so the same call from an IO thread could not be taken on trust. Probed with a real
/// `NettySwimTransport` fed real rotated-key datagrams, in two arms: with no shutdown hook the
/// process terminated in ~1s with exit code 1; with a hook shaped like `Main.shutdownNode` (stop the
/// transport, bounded await) it still terminated with exit code 1, the hook completing cleanly, the
/// port released. The two arms differ only in the hook, which attributes the extra ~10s to Netty's
/// graceful-shutdown quiet period rather than to any deadlock. `System.exit` is therefore kept in
/// preference to `halt`, because it runs the node's own shutdown hooks; and `Main.shutdownNode`
/// bounds those at 30s with `halt(3)`, so even a subsystem that wedges cannot hang the process.
public final class GossipKeyDivergenceGuard implements GossipEncryptor {
    private static final Logger log = LoggerFactory.getLogger(GossipKeyDivergenceGuard.class);
    /// Unknown-keyId datagrams tolerated before the gate fires, given zero successful decrypts.
    static final int UNKNOWN_KEY_THRESHOLD = 8;

    /// The gate is ARMED only inside `[ARMING_DELAY, ARMING_WINDOW_END]` after construction, and both
    /// bounds close a hole that the threshold alone does not.
    ///
    /// **The lower bound** stops 8 spoofable packets from ending a process. `NettySwimTransport`
    /// decrypts every inbound datagram from any sender with no source check, and the default firewall
    /// preset opens SWIM UDP to `0.0.0.0/0` — so without a delay, eight 16-byte junk datagrams
    /// carrying one repeated arbitrary key id kill any booting node, off-path and spoofable, and it
    /// crash-loops under a restart supervisor. Requiring the node to have gone this long without a
    /// SINGLE successful decrypt means an attacker must SUSTAIN the condition rather than send a
    /// burst — and a healthy node in a healthy cluster decrypts within seconds of SWIM starting, so
    /// it passes out of reach long before the gate arms.
    ///
    /// **The upper bound** stops the window from lasting forever. Without it a node that never
    /// decrypts stays armed for life — and that is precisely the auto-heal replacement described in
    /// SECURITY.md, so the most exposed node would be the one that stays killable longest. Closing
    /// the window costs nothing for the defect this gate exists to catch: the case it CAN detect (a
    /// restarted member, probed continuously by peers that still hold it in their seed set)
    /// accumulates its datagrams within seconds of arming, while the case it cannot detect receives
    /// nothing at all and would never have fired however long it stayed armed.
    static final long ARMING_DELAY_NANOS = TimeUnit.SECONDS.toNanos(60);
    static final long ARMING_WINDOW_END_NANOS = TimeUnit.MINUTES.toNanos(10);

    private final GossipEncryptor delegate;
    private final Runnable onDivergence;
    private final LongSupplier nanoClock;
    private final long startedAt;
    private final AtomicInteger consecutive = new AtomicInteger();
    private final AtomicInteger lastUnknownKeyId = new AtomicInteger();
    private final AtomicBoolean everDecrypted = new AtomicBoolean();
    private final AtomicBoolean fired = new AtomicBoolean();

    private GossipKeyDivergenceGuard(GossipEncryptor delegate, Runnable onDivergence, LongSupplier nanoClock) {
        this.delegate = delegate;
        this.onDivergence = onDivergence;
        this.nanoClock = nanoClock;
        this.startedAt = nanoClock.getAsLong();
    }

    /// `onDivergence` is injected so the gate can be pinned by a test without exiting the JVM —
    /// the same reason `Main`'s boot gates are package-private rather than inlined.
    public static GossipKeyDivergenceGuard gossipKeyDivergenceGuard(GossipEncryptor delegate, Runnable onDivergence) {
        return new GossipKeyDivergenceGuard(delegate, onDivergence, System::nanoTime);
    }

    /// Clock seam: monotonic nanos, so the arming window cannot be moved by a wall-clock adjustment,
    /// and so tests can cross a 60-second boundary without sleeping through it.
    static GossipKeyDivergenceGuard gossipKeyDivergenceGuard(GossipEncryptor delegate,
                                                              Runnable onDivergence,
                                                              LongSupplier nanoClock) {
        return new GossipKeyDivergenceGuard(delegate, onDivergence, nanoClock);
    }

    @Override
    public Result<byte[]> encrypt(byte[] plaintext) {
        return delegate.encrypt(plaintext);
    }

    @Override
    public Result<byte[]> decrypt(byte[] ciphertext) {
        return delegate.decrypt(ciphertext)
                       .onSuccess(_ -> everDecrypted.set(true))
                       .onFailure(this::observeFailure);
    }

    /// Counts datagrams that are unknown-keyId AND carry the SAME key id CONSECUTIVELY, and only
    /// while the gate is armed.
    ///
    /// The same-id requirement is load-bearing, and a test found out why: a malformed datagram is not
    /// rejected as malformed. Anything at least as long as the 16-byte header parses, so the first 4
    /// bytes of arbitrary junk are READ AS A KEY ID and produce `UnknownKeyId` exactly like a rotated
    /// peer's traffic. A rotated cluster encrypts under ONE current key, so its datagrams repeat a
    /// single id; unrelated junk does not. Any differing id resets the run.
    ///
    /// Out-of-window datagrams do not merely fail to count, they RESET the run — otherwise an
    /// attacker could bank `THRESHOLD - 1` packets before the window opens and finish with one after.
    private void observeFailure(Cause cause) {
        if (everDecrypted.get() || !(cause instanceof GossipEncryptionError.UnknownKeyId unknown)) {
            return;
        }

        if (!armed()) {
            consecutive.set(0);
            lastUnknownKeyId.set(0);

            return;
        }

        if (lastUnknownKeyId.getAndSet(unknown.keyId()) != unknown.keyId()) {
            consecutive.set(1);

            return;
        }

        if (consecutive.incrementAndGet() >= UNKNOWN_KEY_THRESHOLD && fired.compareAndSet(false, true)) {
            refuse(unknown.keyId());
        }
    }

    private boolean armed() {
        var elapsed = nanoClock.getAsLong() - startedAt;

        return elapsed >= ARMING_DELAY_NANOS && elapsed <= ARMING_WINDOW_END_NANOS;
    }

    /// Names the cause, the observed key epoch and the remedy. Fires once — a per-datagram message
    /// would bury the one line an operator needs under the flood that IS the symptom.
    private void refuse(int observedKeyId) {
        log.error("FATAL: {}",
                  new GossipKeyDivergence(observedKeyId, consecutive.get()).message());
        onDivergence.run();
    }

    /// #683. Carries the peers' observed key id — never key material.
    public record GossipKeyDivergence(int observedKeyId, int undecryptableDatagrams) implements Cause {
        @Override
        public String message() {
            return "Gossip-key divergence: " + undecryptableDatagrams
                 + " gossip datagram(s) arrived under key id " + observedKeyId
                 + " which this node does not hold, and none has ever decrypted. The cluster has almost"
                 + " certainly had its gossip key rotated (POST /api/v1/cluster/gossip-key/rotate) since"
                 + " this node's cluster_secret-derived key was issued. This node CANNOT join: without"
                 + " SWIM there is no quorum, and without quorum the rotation record that carries the"
                 + " cluster key is never replayed to it. Refusing to boot rather than sit unjoinable."
                 + " Remedy: re-provision this node with the rotated cluster's key material, or restore"
                 + " the cluster to the derived key scheme. See #683.";
        }
    }
}
