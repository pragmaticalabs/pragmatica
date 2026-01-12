package org.pragmatica.aether.infra.statemachine;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.pragmatica.lang.Option.option;

/**
 * Defines the structure of a state machine.
 *
 * @param <S> State type
 * @param <E> Event type
 * @param <C> Context type
 */
public record StateMachineDefinition<S, E, C>(String name,
                                              S initialState,
                                              Set<S> finalStates,
                                              List<Transition<S, E, C>> transitions) {
    /**
     * Builder for creating state machine definitions.
     */
    public static <S, E, C> Builder<S, E, C> builder(String name) {
        return new Builder<>(name);
    }

    /**
     * Finds a transition for the given state and event.
     */
    public Option<Transition<S, E, C>> findTransition(S currentState, E event) {
        return option(transitions.stream()
                                 .filter(t -> t.matches(currentState, event))
                                 .findFirst()
                                 .orElse(null));
    }

    /**
     * Checks if a state is a final state.
     */
    public boolean isFinalState(S state) {
        return finalStates.contains(state);
    }

    /**
     * Gets all possible events from a given state.
     */
    public Set<E> getEventsFrom(S state) {
        var events = new HashSet<E>();
        for (var transition : transitions) {
            if (transition.fromState()
                          .equals(state)) {
                events.add(transition.event());
            }
        }
        return Set.copyOf(events);
    }

    /**
     * Gets all possible target states from a given state.
     */
    public Set<S> getTargetStatesFrom(S state) {
        var targets = new HashSet<S>();
        for (var transition : transitions) {
            if (transition.fromState()
                          .equals(state)) {
                targets.add(transition.toState());
            }
        }
        return Set.copyOf(targets);
    }

    /**
     * Builder for state machine definitions.
     */
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
                           .map(v -> new StateMachineDefinition<>(name,
                                                                  initialState,
                                                                  Set.copyOf(finalStates),
                                                                  List.copyOf(transitions)));
        }

        private Result<Builder<S, E, C>> validate() {
            if (name == null || name.isBlank()) {
                return StateMachineError.invalidConfiguration("Name cannot be null or empty")
                                        .result();
            }
            if (initialState == null) {
                return StateMachineError.invalidConfiguration("Initial state must be defined")
                                        .result();
            }
            if (transitions.isEmpty()) {
                return StateMachineError.invalidConfiguration("At least one transition must be defined")
                                        .result();
            }
            return Result.success(this);
        }
    }
}
