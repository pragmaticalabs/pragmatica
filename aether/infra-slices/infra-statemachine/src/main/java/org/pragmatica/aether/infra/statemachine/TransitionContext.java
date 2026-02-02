package org.pragmatica.aether.infra.statemachine;

import java.time.Instant;

/**
 * Context provided to transition actions and guards.
 *
 * @param <S> State type
 * @param <E> Event type
 * @param <C> User context type
 * @param machineId     State machine identifier
 * @param fromState     Source state
 * @param toState       Target state
 * @param event         Triggering event
 * @param userContext   User-provided context data
 * @param timestamp     When the transition occurred
 */
public record TransitionContext<S, E, C>(String machineId,
                                         S fromState,
                                         S toState,
                                         E event,
                                         C userContext,
                                         Instant timestamp) {
    /**
     * Creates a transition context.
     */
    public static <S, E, C> TransitionContext<S, E, C> transitionContext(String machineId,
                                                                         S fromState,
                                                                         S toState,
                                                                         E event,
                                                                         C userContext) {
        return new TransitionContext<>(machineId, fromState, toState, event, userContext, Instant.now());
    }
}
