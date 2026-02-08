package org.pragmatica.aether.infra.statemachine;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;

/**
 * In-memory implementation of StateMachine.
 * Uses ConcurrentHashMap for thread-safe storage.
 */
final class InMemoryStateMachine<S, E, C> implements StateMachine<S, E, C> {
    private final StateMachineDefinition<S, E, C> definition;
    private final ConcurrentHashMap<String, MachineInstance<S, C>> instances = new ConcurrentHashMap<>();

    private InMemoryStateMachine(StateMachineDefinition<S, E, C> definition) {
        this.definition = definition;
    }

    static <S, E, C> InMemoryStateMachine<S, E, C> inMemoryStateMachine(StateMachineDefinition<S, E, C> definition) {
        return new InMemoryStateMachine<>(definition);
    }

    @Override
    public Promise<StateInfo<S>> create(String machineId, C context) {
        return option(instances.get(machineId))
               .map(existing -> StateMachineError.alreadyStarted(machineId,
                   existing.stateInfo.currentState().toString()).<StateInfo<S>>promise())
               .or(() -> createNewInstance(machineId, context));
    }

    private Promise<StateInfo<S>> createNewInstance(String machineId, C context) {
        var stateInfo = StateInfo.stateInfo(machineId, definition.initialState());
        var instance = new MachineInstance<>(stateInfo, context);
        instances.put(machineId, instance);
        return Promise.success(stateInfo);
    }

    @Override
    public Promise<StateInfo<S>> send(String machineId, E event) {
        return getInstanceOrFail(machineId).flatMap(instance -> processEvent(instance, event));
    }

    private Promise<StateInfo<S>> processEvent(MachineInstance<S, C> instance, E event) {
        var currentState = instance.stateInfo.currentState();
        return definition.findTransition(currentState, event)
                         .toResult(StateMachineError.eventNotHandled(currentState.toString(), event.toString()))
                         .async()
                         .flatMap(transition -> executeTransition(instance, transition));
    }

    private Promise<StateInfo<S>> executeTransition(MachineInstance<S, C> instance, Transition<S, E, C> transition) {
        var context = TransitionContext.transitionContext(instance.stateInfo.machineId(),
                                                          transition.fromState(),
                                                          transition.toState(),
                                                          transition.event(),
                                                          instance.userContext);
        if (!transition.isAllowed(context)) {
            return StateMachineError.invalidTransition(transition.fromState()
                                                                 .toString(),
                                                       transition.toState()
                                                                 .toString(),
                                                       transition.event()
                                                                 .toString())
                                    .promise();
        }
        return transition.executeAction(context)
                         .map(unit -> applyTransition(instance,
                                                      transition.toState()));
    }

    private StateInfo<S> applyTransition(MachineInstance<S, C> instance, S newState) {
        var newStateInfo = instance.stateInfo.transitionTo(newState);
        instances.put(instance.stateInfo.machineId(), new MachineInstance<>(newStateInfo, instance.userContext));
        return newStateInfo;
    }

    @Override
    public Promise<Option<StateInfo<S>>> getState(String machineId) {
        return Promise.success(option(instances.get(machineId)).map(i -> i.stateInfo));
    }

    @Override
    public Promise<Boolean> exists(String machineId) {
        return Promise.success(instances.containsKey(machineId));
    }

    @Override
    public Promise<Boolean> isComplete(String machineId) {
        return getState(machineId)
               .map(opt -> opt.map(info -> definition.isFinalState(info.currentState())).or(false));
    }

    @Override
    public Promise<Set<E>> getAvailableEvents(String machineId) {
        return getState(machineId)
               .map(opt -> opt.map(info -> definition.getEventsFrom(info.currentState())).or(Set.of()));
    }

    @Override
    public Promise<StateInfo<S>> reset(String machineId) {
        return getInstanceOrFail(machineId).map(instance -> resetInstance(instance));
    }

    private StateInfo<S> resetInstance(MachineInstance<S, C> instance) {
        var newStateInfo = StateInfo.stateInfo(instance.stateInfo.machineId(), definition.initialState());
        instances.put(instance.stateInfo.machineId(), new MachineInstance<>(newStateInfo, instance.userContext));
        return newStateInfo;
    }

    @Override
    public Promise<Boolean> delete(String machineId) {
        return Promise.success(option(instances.remove(machineId)).isPresent());
    }

    @Override
    public Promise<List<String>> listInstances() {
        return Promise.success(List.copyOf(instances.keySet()));
    }

    @Override
    public StateMachineDefinition<S, E, C> getDefinition() {
        return definition;
    }

    @Override
    public Promise<Unit> stop() {
        instances.clear();
        return Promise.success(unit());
    }

    private Promise<MachineInstance<S, C>> getInstanceOrFail(String machineId) {
        return option(instances.get(machineId))
               .toResult(StateMachineError.notStarted(machineId))
               .async();
    }

    private record MachineInstance<S, C>(StateInfo<S> stateInfo, C userContext) {}
}
