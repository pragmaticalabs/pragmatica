// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.Base64;

import org.pragmatica.aether.slice.kvstore.AetherKey.GossipKeyRotationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.GossipKeyRotationValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.swim.AesGcmGossipEncryptor;
import org.pragmatica.swim.RotatingGossipEncryptor;

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

    @SuppressWarnings("JBCT-RET-01")
    private void applyRotation(GossipKeyRotationValue value) {
        var currentKey = Base64.getDecoder().decode(value.currentKey());
        var hasPrevious = value.previousKeyId() != 0 && !value.previousKey().isEmpty();
        var result = hasPrevious
                     ? buildDualKeyEncryptor(currentKey, value.currentKeyId(), value)
                     : AesGcmGossipEncryptor.aesGcmGossipEncryptor(currentKey, value.currentKeyId());

        result.onSuccess(newEncryptor -> rotateAndLog(newEncryptor,
                                                      value.currentKeyId()))
              .onFailure(cause -> log.error("Failed to apply gossip key rotation: {}",
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
