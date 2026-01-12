package org.pragmatica.aether.infra.pubsub;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

/**
 * Handle to a pub/sub subscription.
 */
public interface Subscription {
    /**
     * Get the topic this subscription is for.
     *
     * @return Topic name
     */
    String topic();

    /**
     * Get the unique subscription identifier.
     *
     * @return Subscription ID
     */
    String subscriptionId();

    /**
     * Pause message delivery for this subscription.
     *
     * @return Promise completing when paused
     */
    Promise<Unit> pause();

    /**
     * Resume message delivery for this subscription.
     *
     * @return Promise completing when resumed
     */
    Promise<Unit> resume();
}
