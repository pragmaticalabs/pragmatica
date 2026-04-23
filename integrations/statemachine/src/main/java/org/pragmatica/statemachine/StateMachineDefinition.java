package org.pragmatica.statemachine;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.pragmatica.lang.Option.option;

/// Defines the structure of a state machine.
///
/// @param <S> State type
/// @param <E> Event type
/// @param <C> Context type
public record StateMachineDefinition<S, E, C>(String name,
                                              S initialState,
                                              Set<S> finalStates,
                                              List<Transition<S, E, C>> transitions,
                                              Map<S, Function<TransitionContext<S, E, C>, Promise<Unit>>> entryActions,
                                              Map<S, Function<TransitionContext<S, E, C>, Promise<Unit>>> exitActions) {
    /// Builder for creating state machine definitions.
    public static <S, E, C> Builder<S, E, C> builder(String name) {
        return new Builder<>(name);
    }

    /// Finds the first transition matching the given state and event. Guards are NOT consulted here;
    /// callers that need guard-aware selection should filter transitions themselves.
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

    /// Returns the optional entry action associated with the given state.
    public Option<Function<TransitionContext<S, E, C>, Promise<Unit>>> entryAction(S state) {
        return option(entryActions.get(state));
    }

    /// Returns the optional exit action associated with the given state.
    public Option<Function<TransitionContext<S, E, C>, Promise<Unit>>> exitAction(S state) {
        return option(exitActions.get(state));
    }

    /// Builder for state machine definitions.
    public static class Builder<S, E, C> {
        private final String name;
        private S initialState;
        private final Set<S> finalStates = new HashSet<>();
        private final List<Transition<S, E, C>> transitions = new ArrayList<>();
        private final Map<S, Function<TransitionContext<S, E, C>, Promise<Unit>>> entryActions = new HashMap<>();
        private final Map<S, Function<TransitionContext<S, E, C>, Promise<Unit>>> exitActions = new HashMap<>();

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

        /// Register an action to run when entering the given state. Entry actions run AFTER the
        /// triggering transition's action. Not invoked for self-transitions (fromState == toState).
        public Builder<S, E, C> onEntry(S state, Function<TransitionContext<S, E, C>, Promise<Unit>> action) {
            this.entryActions.put(state, action);
            return this;
        }

        /// Register an action to run when exiting the given state. Exit actions run BEFORE the
        /// triggering transition's action. Not invoked for self-transitions (fromState == toState).
        public Builder<S, E, C> onExit(S state, Function<TransitionContext<S, E, C>, Promise<Unit>> action) {
            this.exitActions.put(state, action);
            return this;
        }

        public Result<StateMachineDefinition<S, E, C>> build() {
            return validate()
            .map(v -> new StateMachineDefinition<>(name,
                                                   initialState,
                                                   Set.copyOf(finalStates),
                                                   List.copyOf(transitions),
                                                   Map.copyOf(entryActions),
                                                   Map.copyOf(exitActions)));
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
