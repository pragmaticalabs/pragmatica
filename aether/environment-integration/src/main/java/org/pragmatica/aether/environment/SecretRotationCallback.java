package org.pragmatica.aether.environment;

/// Callback for secret rotation events.
@FunctionalInterface
public interface SecretRotationCallback {
    void onRotated(String secretPath, String newValue);
}
