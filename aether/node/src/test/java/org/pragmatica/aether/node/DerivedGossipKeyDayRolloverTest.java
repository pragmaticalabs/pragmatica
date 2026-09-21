// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.aether.slice.kvstore.AetherKey.GossipKeyRotationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.GossipKeyRotationValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.net.tcp.security.CertificateBundle;
import org.pragmatica.net.tcp.security.CertificateProvider;
import org.pragmatica.net.tcp.security.GossipKey;
import org.pragmatica.net.tcp.security.SelfSignedCertificateProvider;
import org.pragmatica.swim.GossipEncryptionError;
import org.pragmatica.swim.GossipEncryptor;
import org.pragmatica.swim.RotatingGossipEncryptor;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// #1164 — the derived gossip key must follow the CURRENT day for the life of the process, not the
/// boot day. The accept window is previous/current/next day, so a node up for two days that still
/// encrypts under its boot-day key and a node booted today hold no key in common: neither can
/// decrypt the other, and SWIM silently partitions the two.
///
/// Guarantee pinned here: two nodes whose clocks agree to within one day can always decrypt each
/// other's gossip regardless of uptime, because each encrypts under its own current day's key and
/// accepts the previous and next day's, and the two current days differ by at most one. The
/// clock-skew bound is unchanged: two days apart is still rejected ([RealProvider#twoDayClockSkew_isStillRejected]).
///
/// Both sides are built through the production factory ([SwimGossipEncryptors]) — never hand-fed an
/// expected outcome — so the assertions can falsify the premise rather than restate it.
class DerivedGossipKeyDayRolloverTest {
    private static final byte[] SECRET = "day-rollover-cluster-secret".getBytes(StandardCharsets.UTF_8);
    private static final Instant DAY_D = Instant.parse("2026-09-21T12:00:00Z");
    private static final byte[] PROBE = "swim-ping".getBytes(StandardCharsets.UTF_8);

    /// The ticket's scenario against the real `cluster_secret` derivation.
    @Nested
    class RealProvider {
        @Test
        void nodeUpTwoDays_andNodeBootedTwoDaysLater_decryptEachOther() {
            var clockA = new MutableClock(DAY_D);
            var nodeA = encryptor(clockA);

            clockA.advance(Duration.ofDays(2));
            var nodeB = encryptor(new MutableClock(DAY_D.plus(Duration.ofDays(2))));

            assertDecrypts(nodeA, nodeB, "#1164: a node up for two days must decrypt a node booted today");
            assertDecrypts(nodeB, nodeA, "#1164: and the node booted today must decrypt the long-running one");
        }

        /// The bound the fix must NOT move: two nodes whose clocks genuinely differ by two days
        /// share no key. Reds if the accept window is widened.
        @Test
        void twoDayClockSkew_isStillRejected() {
            var nodeA = encryptor(new MutableClock(DAY_D));
            var nodeB = encryptor(new MutableClock(DAY_D.plus(Duration.ofDays(2))));

            assertRejectsAsUnknownKey(nodeA, nodeB);
            assertRejectsAsUnknownKey(nodeB, nodeA);
        }

        /// Positive control for the window: one day apart decrypts on the unmodified base too.
        @Test
        void oneDayApart_decryptEachOther() {
            var nodeA = encryptor(new MutableClock(DAY_D));
            var nodeB = encryptor(new MutableClock(DAY_D.plus(Duration.ofDays(1))));

            assertDecrypts(nodeA, nodeB, "control: the next-day key is accepted (#256)");
            assertDecrypts(nodeB, nodeA, "control: the previous-day key is accepted");
        }

        /// A long-running node's SENDING key rolls too, not only its accept set: on day D+2 it must
        /// encrypt under the D+2 key, which a node booted on D+3 accepts and a node booted on D
        /// would not. Distinguishes "re-derives the accept window" from "re-derives everything".
        @Test
        void longRunningNode_encryptsUnderTodaysKey() {
            var clockA = new MutableClock(DAY_D);
            var nodeA = encryptor(clockA);

            clockA.advance(Duration.ofDays(2));
            var bootedOnDayThree = encryptor(new MutableClock(DAY_D.plus(Duration.ofDays(3))));

            assertDecrypts(bootedOnDayThree,
                           nodeA,
                           "#1164: D+2 is within a D+3 node's window only if A now encrypts under D+2");
        }

        /// #683 precedence: a KV rotation replaces the derived scheme, and the day rollover must not
        /// undo it. Two rotated nodes still agree after the day changes.
        @Test
        void kvRotation_isNotUndoneByDayRollover() {
            var clockA = new MutableClock(DAY_D);
            var nodeA = encryptor(clockA);
            var nodeB = encryptor(new MutableClock(DAY_D));
            var rotation = rotationPut();

            GossipKeyRotationHandler.gossipKeyRotationHandler(nodeA).onGossipKeyRotationPut(rotation);
            GossipKeyRotationHandler.gossipKeyRotationHandler(nodeB).onGossipKeyRotationPut(rotation);
            clockA.advance(Duration.ofDays(2));
            assertDecrypts(nodeB, nodeA, "a rotated node keeps the KV key across a day change");
            assertRejectsAsUnknownKey(encryptor(new MutableClock(DAY_D.plus(Duration.ofDays(2)))),
                                      nodeA);
        }

        private static RotatingGossipEncryptor encryptor(Clock clock) {
            var provider = SelfSignedCertificateProvider.selfSignedCertificateProvider(SECRET, clock).or((CertificateProvider) null);

            return SwimGossipEncryptors.fromCertificateProvider(Option.some(provider));
        }
    }

    /// The same contract against a provider whose keys are a pure function of a test-owned day
    /// counter. Pins [SwimGossipEncryptors] on its own: it must FOLLOW the provider, not snapshot
    /// it at construction. Compiles and runs against the unmodified base.
    @Nested
    class StubProvider {
        @Test
        void encryptorFollowsTheProvidersCurrentKey() {
            var dayA = new AtomicLong(100);
            var nodeA = SwimGossipEncryptors.fromCertificateProvider(Option.some(dayKeyedProvider(dayA)));

            dayA.set(102);
            var nodeB = SwimGossipEncryptors.fromCertificateProvider(Option.some(dayKeyedProvider(new AtomicLong(102))));

            assertDecrypts(nodeA, nodeB, "#1164: the encryptor re-reads the provider once its current key changes");
            assertDecrypts(nodeB, nodeA, "#1164: and encrypts under the provider's current key");
        }

        @Test
        void unchangedProvider_keepsTheSameDelegate() {
            var day = new AtomicLong(100);
            var provider = countingProvider(dayKeyedProvider(day));
            var node = SwimGossipEncryptors.fromCertificateProvider(Option.some(provider));

            node.encrypt(PROBE).unwrap();
            node.encrypt(PROBE).unwrap();
            assertThat(provider.previousReads.get()).as("same key id → no rebuild → previous/next never re-read after construction")
                      .isEqualTo(1);
        }
    }

    private static void assertDecrypts(GossipEncryptor receiver, GossipEncryptor sender, String because) {
        var datagram = sender.encrypt(PROBE).unwrap();
        var decrypted = receiver.decrypt(datagram);

        assertThat(decrypted.isSuccess()).as(because
                                            + " — decrypt returned: " + decrypted.fold(cause -> cause.message(),
                                                                                       _ -> "success"))
                  .isTrue();
        assertThat(decrypted.unwrap()).isEqualTo(PROBE);
    }

    private static void assertRejectsAsUnknownKey(GossipEncryptor receiver, GossipEncryptor sender) {
        var datagram = sender.encrypt(PROBE).unwrap();

        receiver.decrypt(datagram)
                .onSuccess(_ -> assertThat(false).as("expected UnknownKeyId, decrypt succeeded")
                                          .isTrue())
                .onFailure(cause -> assertThat(cause).isInstanceOf(GossipEncryptionError.UnknownKeyId.class));
    }

    private static ValuePut<GossipKeyRotationKey, GossipKeyRotationValue> rotationPut() {
        var key = new byte[32];

        java.util.Arrays.fill(key, (byte) 0x22);
        var value = GossipKeyRotationValue.gossipKeyRotationValue(1,
                                                                  Base64.getEncoder().encodeToString(key));

        return new ValuePut<>(new KVCommand.Put<>(GossipKeyRotationKey.gossipKeyRotationKey(), value), Option.none());
    }

    /// Keys are a pure function of the day: keyId = day, key = SHA-256(day).
    private static CertificateProvider dayKeyedProvider(AtomicLong day) {
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
                return Result.success(keyFor(day.get()));
            }

            @Override
            public Option<GossipKey> previousGossipKey() {
                return Option.some(keyFor(day.get() - 1));
            }

            @Override
            public Option<GossipKey> nextGossipKey() {
                return Option.some(keyFor(day.get() + 1));
            }
        };
    }

    private static GossipKey keyFor(long day) {
        return Result.lift(() -> MessageDigest.getInstance("SHA-256").digest(Long.toString(day).getBytes(StandardCharsets.UTF_8)))
                     .map(key -> GossipKey.gossipKey(key,
                                                     (int) day,
                                                     Instant.EPOCH))
                     .unwrap();
    }

    private static CountingProvider countingProvider(CertificateProvider delegate) {
        return new CountingProvider(delegate);
    }

    private static final class CountingProvider implements CertificateProvider {
        private final CertificateProvider delegate;
        final AtomicLong previousReads = new AtomicLong();

        private CountingProvider(CertificateProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public Result<CertificateBundle> issueCertificate(String nodeId, String hostname) {
            return delegate.issueCertificate(nodeId, hostname);
        }

        @Override
        public Result<CertificateBundle> caCertificate() {
            return delegate.caCertificate();
        }

        @Override
        public Result<GossipKey> currentGossipKey() {
            return delegate.currentGossipKey();
        }

        @Override
        public Option<GossipKey> previousGossipKey() {
            previousReads.incrementAndGet();

            return delegate.previousGossipKey();
        }

        @Override
        public Option<GossipKey> nextGossipKey() {
            return delegate.nextGossipKey();
        }
    }

    /// A UTC clock the test moves by hand.
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
