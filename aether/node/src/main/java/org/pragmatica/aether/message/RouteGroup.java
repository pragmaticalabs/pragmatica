package org.pragmatica.aether.message;

import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.MessageRouter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;


/// Groups related message routes by concern for organized routing configuration.
///
///
/// RouteGroup provides a fluent API for building route collections, supporting:
///
///   - Fan-out routes: same message type to multiple handlers
///   - SealedBuilder entries: compile-time validated sealed hierarchy routing
///   - Simple routes: single message type to single handler
///
///
///
/// Usage example:
/// ```{@code
/// var deploymentRoutes = RouteGroup.routeGroup("deployment")
///     .sealedHierarchy(DeploymentEvent.class,
///         route(DeploymentStarted.class, collector::onDeploymentStarted),
///         route(StateTransition.class, collector::onStateTransition),
///         route(DeploymentCompleted.class, collector::onDeploymentCompleted),
///         route(DeploymentFailed.class, collector::onDeploymentFailed))
///     .build();
/// }```
///
/// @param name Short descriptive name for this route group (for logging/debugging)
/// @param entries Accumulated route entries
public record RouteGroup(String name, List<MessageRouter.Entry<?>> entries) {
    public static Builder routeGroup(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;

        private final List<MessageRouter.Entry<?>> entries = new ArrayList<>();

        private Builder(String name) {
            this.name = name;
        }

        public <M extends Message> Builder route(Class<M> type, Consumer<M> handler) {
            entries.add(MessageRouter.Entry.route(type, handler));
            return this;
        }

        @SafeVarargs public final <M extends Message> Builder fanOut(Class<M> type, Consumer<M>... handlers) {
            for (var handler : handlers) {entries.add(MessageRouter.Entry.route(type, handler));}
            return this;
        }

        public Builder entry(MessageRouter.Entry<?> entry) {
            entries.add(entry);
            return this;
        }

        public Builder merge(RouteGroup other) {
            entries.addAll(other.entries());
            return this;
        }

        public RouteGroup build() {
            return new RouteGroup(name, List.copyOf(entries));
        }
    }

    public List<MessageRouter.Entry<?>> toList() {
        return entries;
    }
}
