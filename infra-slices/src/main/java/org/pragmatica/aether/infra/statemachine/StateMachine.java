package org.pragmatica.aether.infra.statemachine;

import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Set;

/**
 * Generic state machine service.
 * Manages state transitions based on events and configured transitions.
 *
 * @param <S> State type (typically an enum)
 * @param <E> Event type (typically an enum)
 * @param <C> User context type
 */
public interface StateMachine<S, E, C> extends Slice {
    /**
     * Creates a new state machine instance with the given ID.
     *
     * @param machineId  Unique identifier for this instance
     * @param context    User context data
     * @return State info for the newly created instance
     */
    Promise<StateInfo<S>> create(String machineId, C context);

    /**
     * Sends an event to trigger a state transition.
     *
     * @param machineId Machine instance ID
     * @param event     Event to process
     * @return Updated state info after transition
     */
    Promise<StateInfo<S>> send(String machineId, E event);

    /**
     * Gets the current state of a machine instance.
     *
     * @param machineId Machine instance ID
     * @return Current state info, or empty if not found
     */
    Promise<Option<StateInfo<S>>> getState(String machineId);

    /**
     * Checks if a machine instance exists.
     *
     * @param machineId Machine instance ID
     * @return true if instance exists
     */
    Promise<Boolean> exists(String machineId);

    /**
     * Checks if a machine instance is in a final state.
     *
     * @param machineId Machine instance ID
     * @return true if in final state
     */
    Promise<Boolean> isComplete(String machineId);

    /**
     * Gets the possible events from the current state.
     *
     * @param machineId Machine instance ID
     * @return Set of valid events
     */
    Promise<Set<E>> getAvailableEvents(String machineId);

    /**
     * Resets a machine instance to its initial state.
     *
     * @param machineId Machine instance ID
     * @return Updated state info
     */
    Promise<StateInfo<S>> reset(String machineId);

    /**
     * Deletes a machine instance.
     *
     * @param machineId Machine instance ID
     * @return true if instance existed and was deleted
     */
    Promise<Boolean> delete(String machineId);

    /**
     * Lists all machine instance IDs.
     *
     * @return List of machine IDs
     */
    Promise<List<String>> listInstances();

    /**
     * Gets the state machine definition.
     *
     * @return The definition
     */
    StateMachineDefinition<S, E, C> getDefinition();

    /**
     * Factory method to create a state machine from a definition.
     */
    static <S, E, C> StateMachine<S, E, C> stateMachine(StateMachineDefinition<S, E, C> definition) {
        return InMemoryStateMachine.inMemoryStateMachine(definition);
    }

    // ========== Slice Lifecycle ==========
    @Override
    default Promise<Unit> start() {
        return Promise.success(Unit.unit());
    }

    @Override
    default Promise<Unit> stop() {
        return Promise.success(Unit.unit());
    }

    @Override
    default List<SliceMethod< ?, ?>> methods() {
        return List.of();
    }
}
