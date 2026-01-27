package org.pragmatica.aether.message;

import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.MessageRouter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Groups related message routes by concern for organized routing configuration.
 *
 * <p>RouteGroup provides a fluent API for building route collections, supporting:
 * <ul>
 *   <li>Fan-out routes: same message type to multiple handlers</li>
 *   <li>SealedBuilder entries: compile-time validated sealed hierarchy routing</li>
 *   <li>Simple routes: single message type to single handler</li>
 * </ul>
 *
 * <p>Usage example:
 * <pre>{@code
 * var deploymentRoutes = RouteGroup.routeGroup("deployment")
 *     .sealedHierarchy(DeploymentEvent.class,
 *         route(DeploymentStarted.class, collector::onDeploymentStarted),
 *         route(StateTransition.class, collector::onStateTransition),
 *         route(DeploymentCompleted.class, collector::onDeploymentCompleted),
 *         route(DeploymentFailed.class, collector::onDeploymentFailed))
 *     .build();
 * }</pre>
 *
 * @param name Short descriptive name for this route group (for logging/debugging)
 * @param entries Accumulated route entries
 */
public record RouteGroup(String name, List<MessageRouter.Entry<?>> entries) {
    /**
     * Factory method to create a new route group builder.
     *
     * @param name Descriptive name for this group
     * @return New builder instance
     */
    public static Builder routeGroup(String name) {
        return new Builder(name);
    }

    /**
     * Builder for constructing RouteGroup instances.
     */
    public static final class Builder {
        private final String name;
        private final List<MessageRouter.Entry<?>> entries = new ArrayList<>();

        private Builder(String name) {
            this.name = name;
        }

        /**
         * Add a single route: message type to handler.
         *
         * @param <M> Message type (must extend Message)
         * @param type Message class
         * @param handler Handler for the message
         * @return This builder for chaining
         */
        public <M extends Message> Builder route(Class<M> type, Consumer<M> handler) {
            entries.add(MessageRouter.Entry.route(type, handler));
            return this;
        }

        /**
         * Add a fan-out route: same message type to multiple handlers.
         *
         * <p>This is a common pattern where multiple components need to react
         * to the same message (e.g., KVStoreNotification.ValuePut going to
         * multiple managers).
         *
         * @param <M> Message type (must extend Message)
         * @param type Message class
         * @param handlers Handlers that will all receive the message
         * @return This builder for chaining
         */
        @SafeVarargs
        public final <M extends Message> Builder fanOut(Class<M> type, Consumer<M>... handlers) {
            for (var handler : handlers) {
                entries.add(MessageRouter.Entry.route(type, handler));
            }
            return this;
        }

        /**
         * Add a pre-built entry (e.g., from SealedBuilder).
         *
         * @param entry Pre-built route entry
         * @return This builder for chaining
         */
        public Builder entry(MessageRouter.Entry<?> entry) {
            entries.add(entry);
            return this;
        }

        /**
         * Add all entries from another route group.
         *
         * @param other Route group to merge
         * @return This builder for chaining
         */
        public Builder merge(RouteGroup other) {
            entries.addAll(other.entries());
            return this;
        }

        /**
         * Build the route group.
         *
         * @return Immutable RouteGroup with all accumulated entries
         */
        public RouteGroup build() {
            return new RouteGroup(name, List.copyOf(entries));
        }
    }

    /**
     * Get all entries as a flat list for router configuration.
     *
     * @return List of all route entries
     */
    public List<MessageRouter.Entry<?>> toList() {
        return entries;
    }
}
