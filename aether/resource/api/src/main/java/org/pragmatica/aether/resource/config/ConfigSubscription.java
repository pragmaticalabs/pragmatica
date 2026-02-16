package org.pragmatica.aether.resource.config;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

/// Handle for a configuration watch subscription.
/// Can be used to cancel the subscription.
public interface ConfigSubscription {
    /// Cancel this subscription.
    /// After cancellation, the callback will no longer be invoked.
    Result<Unit> cancel();

    /// Check if this subscription is still active.
    ///
    /// @return true if still active
    boolean isActive();
}
