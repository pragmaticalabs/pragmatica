// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GossipKeyRotationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GossipKeyRotationValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.swim.AesGcmGossipEncryptor;
import org.pragmatica.swim.RotatingGossipEncryptor;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.serialization.FrameworkCodecs.frameworkCodecs;

/// Late-joiner gossip-key delivery (§5.8 AMENDED 2026-06-11): the dedicated `replayFromStore`
/// re-read is SUBSUMED by the engine-level notification replay. A node that synced KV state AFTER
/// the `GossipKeyRotationKey` PUT receives the rotation as a replayed `ValuePut` on the handler's
/// NORMAL subscription (`onGossipKeyRotationPut`) once the engine activates and walks the store.
/// These tests verify late-joiner decrypt works by construction through that single subscription:
/// a SILENT snapshot install (no notification) followed by [`KVStore#replayNotifications`].
class GossipKeyRotationHandlerTest {
    private static final byte[] BOOT_KEY = key((byte) 1);
    private static final byte[] ROTATED_KEY = key((byte) 2);
    private static final int BOOT_KEY_ID = 1;
    private static final int ROTATED_KEY_ID = 2;
    private static final byte[] MESSAGE = "swim-datagram".getBytes(StandardCharsets.UTF_8);

    private MessageRouter.MutableRouter router;
    private SliceCodec codec;
    private KVStore<AetherKey, AetherValue> kvStore;
    private RotatingGossipEncryptor rotatingEncryptor;
    private GossipKeyRotationHandler handler;

    @BeforeEach
    void setUp() {
        router = MessageRouter.mutable();
        codec = NodeCodecs.nodeCodecs(SliceCodec.sliceCodec(frameworkCodecs(), List.of()));
        kvStore = new KVStore<>(router, codec, codec);
        rotatingEncryptor = RotatingGossipEncryptor.rotatingGossipEncryptor(
            AesGcmGossipEncryptor.aesGcmGossipEncryptor(BOOT_KEY, BOOT_KEY_ID).unwrap());
        handler = GossipKeyRotationHandler.gossipKeyRotationHandler(rotatingEncryptor);
        // Subscribe-before-start: the handler registers its NORMAL KV subscription up front; the
        // replay burst (and any live PUT) reaches it through this single route.
        router.addRoute(ValuePut.class, this::routeIfRotation);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void routeIfRotation(ValuePut put) {
        if (put.cause().key() instanceof GossipKeyRotationKey) {
            handler.onGossipKeyRotationPut(put);
        }
    }

    @Test
    void rotation_deliveredViaReplay_lateJoinerDecryptsClusterTraffic() {
        // Late joiner: the rotation was committed before this node synced. A SILENT snapshot
        // install populates storage WITHOUT firing any notification (the boot key is unchanged so
        // far); the engine-level replay then fires the synthetic ValuePut on the normal subscription.
        installSnapshotSilently(snapshotContaining(ROTATED_KEY_ID));
        assertThat(rotatingEncryptor.decrypt(peerCiphertext(ROTATED_KEY, ROTATED_KEY_ID)).isFailure())
            .as("silent install fires NO notification — the joiner has not yet adopted the rotated key")
            .isTrue();

        kvStore.replayNotifications();

        assertThat(rotatingEncryptor.decrypt(peerCiphertext(ROTATED_KEY, ROTATED_KEY_ID)).unwrap())
            .as("after the replayed rotation the joiner decrypts traffic encrypted with the rotated key")
            .isEqualTo(MESSAGE);
        assertThat(rotatingEncryptor.decrypt(peerCiphertext(BOOT_KEY, BOOT_KEY_ID)).isFailure())
            .as("single-key rotation replaces the boot key — boot-key ciphertext is now rejected")
            .isTrue();
    }

    @Test
    void noRotation_replayLeavesBootKey() {
        installSnapshotSilently(emptySnapshot());

        kvStore.replayNotifications();

        assertThat(rotatingEncryptor.decrypt(peerCiphertext(BOOT_KEY, BOOT_KEY_ID)).unwrap())
            .as("empty store fires no rotation notification — the boot-time key stays installed")
            .isEqualTo(MESSAGE);
    }

    @Test
    void liveRotationPut_adoptsRotatedKey() {
        // A rotation that arrives live (node already ACTIVE) flows through the same subscription.
        kvStore.process(kvStore.createBatch(List.of(new KVCommand.Put<>(GossipKeyRotationKey.gossipKeyRotationKey(),
                                                                        rotationValue()))));

        assertThat(rotatingEncryptor.decrypt(peerCiphertext(ROTATED_KEY, ROTATED_KEY_ID)).unwrap())
            .as("a live rotation PUT adopts the rotated key via the same normal subscription")
            .isEqualTo(MESSAGE);
    }

    /// Build a snapshot of a store that holds the rotation, then install it into the system-under-
    /// test store via the SILENT `restoreSnapshot` path (no notifications).
    private byte[] snapshotContaining(@SuppressWarnings("unused") int rotatedKeyId) {
        var source = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), codec, codec);
        source.process(source.createBatch(List.of(new KVCommand.Put<>(GossipKeyRotationKey.gossipKeyRotationKey(),
                                                                      rotationValue()))));
        return source.makeSnapshot().unwrap();
    }

    private byte[] emptySnapshot() {
        return new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), codec, codec).makeSnapshot().unwrap();
    }

    private void installSnapshotSilently(byte[] snapshot) {
        kvStore.restoreSnapshot(snapshot).unwrap();
    }

    private static GossipKeyRotationValue rotationValue() {
        return GossipKeyRotationValue.gossipKeyRotationValue(ROTATED_KEY_ID,
                                                             Base64.getEncoder().encodeToString(ROTATED_KEY));
    }

    private static byte[] peerCiphertext(byte[] rawKey, int keyId) {
        return AesGcmGossipEncryptor.aesGcmGossipEncryptor(rawKey, keyId)
                                    .unwrap()
                                    .encrypt(MESSAGE)
                                    .unwrap();
    }

    private static byte[] key(byte fill) {
        var raw = new byte[32];
        Arrays.fill(raw, fill);
        return raw;
    }
}
