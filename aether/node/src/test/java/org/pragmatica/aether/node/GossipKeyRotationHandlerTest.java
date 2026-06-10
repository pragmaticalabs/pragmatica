// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GossipKeyRotationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GossipKeyRotationValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.swim.AesGcmGossipEncryptor;
import org.pragmatica.swim.RotatingGossipEncryptor;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Late-joiner gossip-key replay: a node that syncs KV state AFTER the GossipKeyRotationKey PUT
/// never sees the live ValuePut notification, so the rotation must be re-readable directly from
/// the synced store (`GossipKeyRotationHandler#replayFromStore`) — otherwise the joiner keeps its
/// boot-time per-node key and every SWIM datagram it sends is rejected by the cluster.
class GossipKeyRotationHandlerTest {
    private static final byte[] BOOT_KEY = key((byte) 1);
    private static final byte[] ROTATED_KEY = key((byte) 2);
    private static final int BOOT_KEY_ID = 1;
    private static final int ROTATED_KEY_ID = 2;
    private static final byte[] MESSAGE = "swim-datagram".getBytes(StandardCharsets.UTF_8);

    private KVStore<AetherKey, AetherValue> kvStore;
    private RotatingGossipEncryptor rotatingEncryptor;
    private GossipKeyRotationHandler handler;

    @BeforeEach
    void setUp() {
        var router = MessageRouter.DelegateRouter.delegate();
        router.quiesce();
        kvStore = new KVStore<>(router, noopSerializer(), null);
        rotatingEncryptor = RotatingGossipEncryptor.rotatingGossipEncryptor(
            AesGcmGossipEncryptor.aesGcmGossipEncryptor(BOOT_KEY, BOOT_KEY_ID).unwrap());
        handler = GossipKeyRotationHandler.gossipKeyRotationHandler(rotatingEncryptor);
    }

    @Test
    void replayFromStore_adoptsRotatedKey_whenRotationValuePresent() {
        seedRotation();

        handler.replayFromStore(kvStore);

        assertThat(rotatingEncryptor.decrypt(peerCiphertext(ROTATED_KEY, ROTATED_KEY_ID)).unwrap())
            .as("after replay the joiner must decrypt cluster traffic encrypted with the rotated key")
            .isEqualTo(MESSAGE);
        assertThat(rotatingEncryptor.decrypt(peerCiphertext(BOOT_KEY, BOOT_KEY_ID)).isFailure())
            .as("single-key rotation replaces the boot key — boot-key ciphertext must now be rejected")
            .isTrue();
    }

    @Test
    void replayFromStore_keepsBootKey_whenStoreEmpty() {
        handler.replayFromStore(kvStore);

        assertThat(rotatingEncryptor.decrypt(peerCiphertext(BOOT_KEY, BOOT_KEY_ID)).unwrap())
            .as("empty store is a no-op — the boot-time key stays installed")
            .isEqualTo(MESSAGE);
    }

    @Test
    void replayFromStore_staysOnRotatedKey_whenReplayedRepeatedly() {
        seedRotation();

        handler.replayFromStore(kvStore);
        handler.replayFromStore(kvStore);

        assertThat(rotatingEncryptor.decrypt(peerCiphertext(ROTATED_KEY, ROTATED_KEY_ID)).unwrap())
            .as("replay is idempotent — re-applying the same rotation is harmless")
            .isEqualTo(MESSAGE);
    }

    private void seedRotation() {
        var value = GossipKeyRotationValue.gossipKeyRotationValue(ROTATED_KEY_ID,
                                                                  Base64.getEncoder()
                                                                        .encodeToString(ROTATED_KEY));
        kvStore.process(kvStore.createBatch(List.of(new KVCommand.Put<>(GossipKeyRotationKey.gossipKeyRotationKey(),
                                                                        value))));
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

    /// No-op serializer: this test seeds the KV store directly (not via consensus dedup), so
    /// the content-based batch id is irrelevant — an empty encoding satisfies `createBatch`.
    private static Serializer noopSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }
}
