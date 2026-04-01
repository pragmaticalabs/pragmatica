package org.pragmatica.aether.environment;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

/// Callback for secret rotation events.
@FunctionalInterface public interface SecretRotationCallback {
    Result<Unit> onRotated(String secretPath, String newValue);
}
