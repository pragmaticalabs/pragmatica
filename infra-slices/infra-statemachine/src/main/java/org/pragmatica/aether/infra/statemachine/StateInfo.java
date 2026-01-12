package org.pragmatica.aether.infra.statemachine;

import java.time.Instant;

/**
 * Information about the current state of a state machine instance.
 *
 * @param <S> State type
 * @param machineId     State machine identifier
 * @param currentState  Current state
 * @param enteredAt     When the current state was entered
 * @param transitionCount Number of transitions since start
 */
public record StateInfo<S>(String machineId,
                           S currentState,
                           Instant enteredAt,
                           long transitionCount) {
    /**
     * Creates state info.
     */
    public static <S> StateInfo<S> stateInfo(String machineId, S currentState) {
        return new StateInfo<>(machineId, currentState, Instant.now(), 0);
    }

    /**
     * Creates state info with transition count.
     */
    public static <S> StateInfo<S> stateInfo(String machineId, S currentState, long transitionCount) {
        return new StateInfo<>(machineId, currentState, Instant.now(), transitionCount);
    }

    /**
     * Creates new state info after a transition.
     */
    public StateInfo<S> transitionTo(S newState) {
        return new StateInfo<>(machineId, newState, Instant.now(), transitionCount + 1);
    }
}
