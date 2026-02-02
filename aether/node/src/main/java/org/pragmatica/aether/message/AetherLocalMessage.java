package org.pragmatica.aether.message;

import org.pragmatica.messaging.Message;

/**
 * Marker interface for Aether-specific local messages.
 *
 * <p>Local messages are dispatched within a single node via MessageRouter,
 * not sent over the network. This serves as documentation for the message
 * types used in Aether:
 *
 * <ul>
 *   <li>{@link org.pragmatica.aether.metrics.deployment.DeploymentEvent} - Slice deployment lifecycle events</li>
 *   <li>{@link org.pragmatica.aether.invoke.SliceFailureEvent} - Invocation failure events</li>
 * </ul>
 *
 * <p>Compile-time exhaustiveness is validated via {@link org.pragmatica.messaging.MessageRouter.Entry.SealedBuilder}
 * when building routes in {@link org.pragmatica.aether.node.AetherNode}.
 *
 * <p>Note: Network messages (InvocationMessage, MetricsMessage, etc.) extend
 * ProtocolMessage and are handled separately through the cluster network layer.
 *
 * @see RouteGroup
 * @see org.pragmatica.messaging.MessageRouter.Entry.SealedBuilder
 */
public interface AetherLocalMessage extends Message.Local {}
