package org.pragmatica.aether.infra.config;
/**
 * Handle for a configuration watch subscription.
 * Can be used to cancel the subscription.
 */
public interface ConfigSubscription {
    /**
     * Cancel this subscription.
     * After cancellation, the callback will no longer be invoked.
     */
    void cancel();

    /**
     * Check if this subscription is still active.
     *
     * @return true if still active
     */
    boolean isActive();
}
