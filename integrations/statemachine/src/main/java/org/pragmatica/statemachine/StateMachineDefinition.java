package org.pragmatica.statemachine;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;

/// Defines the structure of a state machine.
///
/// @param <S> State type
/// @param <E> Event type
/// @param <C> Context type
public record StateMachineDefinition<S, E, C>(String name,
                                              S initialState,
                                              Set<S> finalStates,
                                              List<Transition<S, E, C>> transitions) {
    /// Builder for creating state machine definitions.
    public static <S, E, C> Builder<S, E, C> builder(String name) {
        return new Builder<>(name);
    }

    /// Finds a transition for the given state and event.
    public Option<Transition<S, E, C>> findTransition(S currentState, E event) {
        return Option.from(transitions.stream()
                                      .filter(t -> t.matches(currentState, event))
                                      .findFirst());
    }

    /// Checks if a state is a final state.
    public boolean isFinalState(S state) {
        return finalStates.contains(state);
    }

    /// Gets all possible events from a given state.
    public Set<E> getEventsFrom(S state) {
        var fromState = transitions.stream()
                                   .filter(t -> t.fromState()
                                                 .equals(state));
        return fromState.map(Transition::event)
                        .collect(Collectors.toUnmodifiableSet());
    }

    /// Gets all possible target states from a given state.
    public Set<S> getTargetStatesFrom(S state) {
        var fromState = transitions.stream()
                                   .filter(t -> t.fromState()
                                                 .equals(state));
        return fromState.map(Transition::toState)
                        .collect(Collectors.toUnmodifiableSet());
    }

    /// Builder for state machine definitions.
    public static class Builder<S, E, C> {
        private final String name;
        private S initialState;
        private final Set<S> finalStates = new HashSet<>();
        private final List<Transition<S, E, C>> transitions = new ArrayList<>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder<S, E, C> initialState(S state) {
            this.initialState = state;
            return this;
        }

        public Builder<S, E, C> finalState(S state) {
            this.finalStates.add(state);
            return this;
        }

        public Builder<S, E, C> finalStates(Set<S> states) {
            this.finalStates.addAll(states);
            return this;
        }

        public Builder<S, E, C> transition(Transition<S, E, C> transition) {
            this.transitions.add(transition);
            return this;
        }

        public Builder<S, E, C> transition(S from, E event, S to) {
            return transition(Transition.transition(from, event, to));
        }

        public Result<StateMachineDefinition<S, E, C>> build() {
            return validate()
            .map(v -> new StateMachineDefinition<>(name, initialState, Set.copyOf(finalStates), List.copyOf(transitions)));
        }

        private Result<Builder<S, E, C>> validate() {
            return validateName().flatMap(_ -> validateInitialState())
                               .flatMap(_ -> validateTransitions());
        }

        private Result<Builder<S, E, C>> validateName() {
            return option(name).filter(n -> !n.isBlank())
                         .toResult(StateMachineError.invalidConfiguration("Name cannot be null or empty"))
                         .map(_ -> this);
        }

        private Result<Builder<S, E, C>> validateInitialState() {
            return option(initialState).toResult(StateMachineError.invalidConfiguration("Initial state must be defined"))
                         .map(_ -> this);
        }

        private Result<Builder<S, E, C>> validateTransitions() {
            return option(transitions).filter(t -> !t.isEmpty())
                         .toResult(StateMachineError.invalidConfiguration("At least one transition must be defined"))
                         .map(_ -> this);
        }
    }
}
