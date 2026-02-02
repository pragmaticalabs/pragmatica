package org.pragmatica.aether.infra.statemachine;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;

/**
 * Error types for state machine operations.
 */
public sealed interface StateMachineError extends Cause {
    /**
     * Invalid state transition attempted.
     */
    record InvalidTransition(String fromState, String toState, String event) implements StateMachineError {
        @Override
        public String message() {
            return "Invalid transition from '" + fromState + "' to '" + toState + "' on event '" + event + "'";
        }
    }

    /**
     * State not found in machine.
     */
    record StateNotFound(String state) implements StateMachineError {
        @Override
        public String message() {
            return "State not found: " + state;
        }
    }

    /**
     * Event not handled in current state.
     */
    record EventNotHandled(String state, String event) implements StateMachineError {
        @Override
        public String message() {
            return "Event '" + event + "' not handled in state '" + state + "'";
        }
    }

    /**
     * State machine not started.
     */
    record NotStarted(String machineId) implements StateMachineError {
        @Override
        public String message() {
            return "State machine not started: " + machineId;
        }
    }

    /**
     * State machine already started.
     */
    record AlreadyStarted(String machineId, String currentState) implements StateMachineError {
        @Override
        public String message() {
            return "State machine '" + machineId + "' already started in state '" + currentState + "'";
        }
    }

    /**
     * Action execution failed.
     */
    record ActionFailed(String action, Option<Throwable> cause) implements StateMachineError {
        @Override
        public String message() {
            return "Action failed: " + action + cause.fold(() -> "", c -> " - " + c.getMessage());
        }
    }

    /**
     * Invalid configuration.
     */
    record InvalidConfiguration(String reason) implements StateMachineError {
        @Override
        public String message() {
            return "Invalid state machine configuration: " + reason;
        }
    }

    // Factory methods
    static InvalidTransition invalidTransition(String fromState, String toState, String event) {
        return new InvalidTransition(fromState, toState, event);
    }

    static StateNotFound stateNotFound(String state) {
        return new StateNotFound(state);
    }

    static EventNotHandled eventNotHandled(String state, String event) {
        return new EventNotHandled(state, event);
    }

    static NotStarted notStarted(String machineId) {
        return new NotStarted(machineId);
    }

    static AlreadyStarted alreadyStarted(String machineId, String currentState) {
        return new AlreadyStarted(machineId, currentState);
    }

    static ActionFailed actionFailed(String action) {
        return new ActionFailed(action, Option.none());
    }

    static ActionFailed actionFailed(String action, Throwable cause) {
        return new ActionFailed(action, Option.option(cause));
    }

    static InvalidConfiguration invalidConfiguration(String reason) {
        return new InvalidConfiguration(reason);
    }
}
