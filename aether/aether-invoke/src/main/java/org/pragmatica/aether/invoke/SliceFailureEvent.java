package org.pragmatica.aether.invoke;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.Message;

import java.util.List;


/// Events related to slice invocation failures.
///
///
/// Used to notify the controller and alerting system about
/// critical failure conditions requiring action.
///
///
/// This is a sealed hierarchy validated at route-building time via SealedBuilder.
/// These events are dispatched locally via MessageRouter.
///
/// @see org.pragmatica.messaging.MessageRouter.Entry.SealedBuilder
public sealed interface SliceFailureEvent extends Message.Local {
    record AllInstancesFailed(String requestId,
                              Artifact artifact,
                              MethodName method,
                              Option<Cause> lastError,
                              List<NodeId> attemptedNodes,
                              long timestamp) implements SliceFailureEvent {
        public static AllInstancesFailed allInstancesFailed(String requestId,
                                                            Artifact artifact,
                                                            MethodName method,
                                                            Option<Cause> lastError,
                                                            List<NodeId> attemptedNodes) {
            return new AllInstancesFailed(requestId,
                                          artifact,
                                          method,
                                          lastError,
                                          attemptedNodes,
                                          System.currentTimeMillis());
        }
    }
}
