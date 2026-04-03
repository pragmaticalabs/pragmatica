package org.pragmatica.aether.resource.config;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// Handle for a configuration watch subscription.
/// Can be used to cancel the subscription.
public interface ConfigSubscription {
    Result<Unit> cancel();
    boolean isActive();
}
