package org.pragmatica.aether.invoke;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.messaging.Message;

import java.util.List;

/**
 * Events related to slice invocation failures.
 *
 * <p>Used to notify the controller and alerting system about
 * critical failure conditions requiring action.
 *
 * <p>These events are dispatched locally via MessageRouter.
 */
public sealed interface SliceFailureEvent extends Message.Local {
    /**
     * Emitted when all instances of a slice have failed during invocation.
     *
     * <p>This is a critical condition indicating:
     * <ul>
     *   <li>All available endpoints were tried and failed</li>
     *   <li>The slice may need rollback or manual intervention</li>
     * </ul>
     *
     * @param requestId the distributed tracing ID
     * @param artifact the failing slice
     * @param method the method being invoked
     * @param lastError the last error encountered
     * @param attemptedNodes nodes that were tried
     * @param timestamp when the failure was detected
     */
    record AllInstancesFailed(String requestId,
                              Artifact artifact,
                              MethodName method,
                              Cause lastError,
                              List<NodeId> attemptedNodes,
                              long timestamp) implements SliceFailureEvent {
        public static AllInstancesFailed allInstancesFailed(String requestId,
                                                            Artifact artifact,
                                                            MethodName method,
                                                            Cause lastError,
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
