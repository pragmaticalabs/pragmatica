package org.pragmatica.aether.infra.statemachine;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.function.Function;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;

/**
 * Represents a state transition in the state machine.
 *
 * @param <S> State type
 * @param <E> Event type
 * @param <C> Context type
 */
public record Transition<S, E, C>(S fromState,
                                  E event,
                                  S toState,
                                  Option<Function<TransitionContext<S, E, C>, Promise<Unit>>> action,
                                  Option<Function<TransitionContext<S, E, C>, Boolean>> guard) {
    /**
     * Creates a simple transition without action or guard.
     */
    public static <S, E, C> Transition<S, E, C> transition(S fromState, E event, S toState) {
        return new Transition<>(fromState, event, toState, none(), none());
    }

    /**
     * Creates a transition with an action.
     */
    public static <S, E, C> Transition<S, E, C> transition(S fromState,
                                                           E event,
                                                           S toState,
                                                           Function<TransitionContext<S, E, C>, Promise<Unit>> action) {
        return new Transition<>(fromState, event, toState, option(action), none());
    }

    /**
     * Creates a transition with a guard condition.
     */
    public static <S, E, C> Transition<S, E, C> transitionGuarded(S fromState,
                                                                  E event,
                                                                  S toState,
                                                                  Function<TransitionContext<S, E, C>, Boolean> guard) {
        return new Transition<>(fromState, event, toState, none(), option(guard));
    }

    /**
     * Creates a transition with both action and guard.
     */
    public static <S, E, C> Transition<S, E, C> transitionFull(S fromState,
                                                               E event,
                                                               S toState,
                                                               Function<TransitionContext<S, E, C>, Promise<Unit>> action,
                                                               Function<TransitionContext<S, E, C>, Boolean> guard) {
        return new Transition<>(fromState, event, toState, option(action), option(guard));
    }

    /**
     * Checks if the guard allows this transition.
     */
    public boolean isAllowed(TransitionContext<S, E, C> context) {
        return guard.fold(() -> true, g -> g.apply(context));
    }

    /**
     * Executes the transition action if present.
     */
    public Promise<Unit> executeAction(TransitionContext<S, E, C> context) {
        return action.fold(() -> Promise.success(Unit.unit()),
                           a -> a.apply(context));
    }

    /**
     * Checks if this transition matches the given state and event.
     */
    public boolean matches(S currentState, E triggerEvent) {
        return fromState.equals(currentState) && event.equals(triggerEvent);
    }
}
