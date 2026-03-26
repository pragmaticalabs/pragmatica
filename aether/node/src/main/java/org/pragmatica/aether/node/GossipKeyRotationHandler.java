package org.pragmatica.aether.node;

import org.pragmatica.aether.slice.kvstore.AetherKey.GossipKeyRotationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.GossipKeyRotationValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.swim.AesGcmGossipEncryptor;
import org.pragmatica.swim.RotatingGossipEncryptor;

import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Handles gossip key rotation events from the KV-Store.
///
/// When a [GossipKeyRotationKey] put is committed through consensus, this handler
/// decodes the key material and atomically swaps the [RotatingGossipEncryptor] delegate.
/// During rotation, both current and previous keys are accepted for decryption,
/// providing a seamless overlap window.
public final class GossipKeyRotationHandler {
    private static final Logger log = LoggerFactory.getLogger(GossipKeyRotationHandler.class);

    private final RotatingGossipEncryptor encryptor;

    private GossipKeyRotationHandler(RotatingGossipEncryptor encryptor) {
        this.encryptor = encryptor;
    }

    /// Factory method.
    public static GossipKeyRotationHandler gossipKeyRotationHandler(RotatingGossipEncryptor encryptor) {
        return new GossipKeyRotationHandler(encryptor);
    }

    /// Handle a gossip key rotation KV-Store put notification.
    @SuppressWarnings("JBCT-RET-01") // Event callback - void inherent
    public void onGossipKeyRotationPut(ValuePut<GossipKeyRotationKey, GossipKeyRotationValue> put) {
        var value = put.cause()
                       .value();
        log.info("Gossip key rotation received: currentKeyId={}, previousKeyId={}",
                 value.currentKeyId(),
                 value.previousKeyId());
        applyRotation(value);
    }

    @SuppressWarnings("JBCT-RET-01") // Side-effect: swap encryptor + log outcome
    private void applyRotation(GossipKeyRotationValue value) {
        var currentKey = Base64.getDecoder()
                               .decode(value.currentKey());
        var hasPrevious = value.previousKeyId() != 0 && !value.previousKey()
                                                              .isEmpty();
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
        var previousKey = Base64.getDecoder()
                                .decode(value.previousKey());
        return AesGcmGossipEncryptor.aesGcmGossipEncryptor(currentKey, currentKeyId, previousKey, value.previousKeyId());
    }
}
