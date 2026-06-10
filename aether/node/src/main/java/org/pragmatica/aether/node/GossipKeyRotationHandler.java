// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GossipKeyRotationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GossipKeyRotationValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.lang.Contract;
import org.pragmatica.swim.AesGcmGossipEncryptor;
import org.pragmatica.swim.RotatingGossipEncryptor;

import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public final class GossipKeyRotationHandler {
    private static final Logger log = LoggerFactory.getLogger(GossipKeyRotationHandler.class);

    private final RotatingGossipEncryptor encryptor;

    private GossipKeyRotationHandler(RotatingGossipEncryptor encryptor) {
        this.encryptor = encryptor;
    }

    public static GossipKeyRotationHandler gossipKeyRotationHandler(RotatingGossipEncryptor encryptor) {
        return new GossipKeyRotationHandler(encryptor);
    }

    @SuppressWarnings("JBCT-RET-01")
    public void onGossipKeyRotationPut(ValuePut<GossipKeyRotationKey, GossipKeyRotationValue> put) {
        var value = put.cause().value();

        log.info("Gossip key rotation received: currentKeyId={}, previousKeyId={}",
                 value.currentKeyId(),
                 value.previousKeyId());
        applyRotation(value);
    }

    /// Replays the cluster gossip-key rotation from an already-synced KV store. A late joiner
    /// never receives the live `ValuePut` that distributed the rotation (the PUT happened
    /// before it joined), so after consensus state restore the rotation must be re-read from
    /// the restored store — otherwise the node keeps its boot-time per-node key and every SWIM
    /// datagram it sends is rejected by the cluster ("Unknown gossip key ID"), permanently
    /// locking it out of SWIM membership. Absent value → debug-logged no-op. Idempotent by
    /// construction: re-applying the same key is harmless.
    @Contract
    public void replayFromStore(KVStore<AetherKey, AetherValue> store) {
        store.getTyped(GossipKeyRotationKey.gossipKeyRotationKey(), GossipKeyRotationValue.class)
             .onPresent(this::replayRotation)
             .onEmpty(GossipKeyRotationHandler::logRotationAbsent);
    }

    @Contract
    private void replayRotation(GossipKeyRotationValue value) {
        log.info("Replaying gossip key rotation from synced store: currentKeyId={}, previousKeyId={}",
                 value.currentKeyId(),
                 value.previousKeyId());
        applyRotation(value);
    }

    private static void logRotationAbsent() {
        log.debug("No gossip key rotation present in synced store — keeping boot-time gossip key");
    }

    @SuppressWarnings("JBCT-RET-01")
    private void applyRotation(GossipKeyRotationValue value) {
        var currentKey = Base64.getDecoder().decode(value.currentKey());
        var hasPrevious = value.previousKeyId() != 0 && !value.previousKey().isEmpty();
        var result = hasPrevious
                     ? buildDualKeyEncryptor(currentKey, value.currentKeyId(), value)
                     : AesGcmGossipEncryptor.aesGcmGossipEncryptor(currentKey, value.currentKeyId());

        result.onSuccess(newEncryptor -> rotateAndLog(newEncryptor, value.currentKeyId())).onFailure(cause -> log.error("Failed to apply gossip key rotation: {}",
                                                                                                                        cause.message()));
    }

    private void rotateAndLog(org.pragmatica.swim.GossipEncryptor newEncryptor, int keyId) {
        encryptor.rotate(newEncryptor);
        log.info("Gossip encryptor rotated to keyId={}", keyId);
    }

    private static org.pragmatica.lang.Result<org.pragmatica.swim.GossipEncryptor> buildDualKeyEncryptor(byte[] currentKey,
                                                                                                         int currentKeyId,
                                                                                                         GossipKeyRotationValue value) {
        var previousKey = Base64.getDecoder().decode(value.previousKey());

        return AesGcmGossipEncryptor.aesGcmGossipEncryptor(currentKey, currentKeyId, previousKey, value.previousKeyId());
    }
}
