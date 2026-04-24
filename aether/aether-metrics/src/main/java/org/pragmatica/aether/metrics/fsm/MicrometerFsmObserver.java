// SPDX-License-Identifier: BUSL-1.1
// Licensed Work: aether — Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Change Date: 2030-01-01
// Change License: Apache-2.0

package org.pragmatica.aether.metrics.fsm;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.pragmatica.lang.Contract;
import org.pragmatica.statemachine.FsmObserver;
import org.pragmatica.statemachine.FsmTags;

/// [`FsmObserver`] implementation that emits per-FSM metrics to a Micrometer `MeterRegistry`.
///
/// Metrics emitted (tag set per FSM/state shown in curly braces):
/// - `fsm_transitions_total{fsm,node_id,from,to}` — counter of completed transitions.
/// - `fsm_cas_lost_total{fsm,node_id,expected,actual}` — counter of CAS losses (another thread won).
/// - `fsm_events_ignored_total{fsm,node_id,state,event}` — counter of explicitly-ignored events.
/// - `fsm_events_handled_total{fsm,node_id,state,event}` — counter of side-effect-handled events.
///
/// The `fsm` tag carries the bounded FSM kind (e.g. `leader-election`, `control-loop`) while the
/// `node_id` tag carries the per-instance discriminator. This split prevents cardinality explosion
/// on the `fsm` label across node churn.
///
/// State and event names are derived via the class's simple name. For data-carrying states,
/// different entries into the same state class share the same label (since the label is derived
/// from the class, not the instance).
public final class MicrometerFsmObserver<S, E> implements FsmObserver<S, E> {
    private final MeterRegistry registry;

    private MicrometerFsmObserver(MeterRegistry registry) {
        this.registry = registry;
    }

    public static <S, E> MicrometerFsmObserver<S, E> micrometerFsmObserver(MeterRegistry registry) {
        return new MicrometerFsmObserver<>(registry);
    }

    @Contract
    @Override
    public void onTransition(FsmTags tags, S from, S to) {
        Counter.builder("fsm_transitions_total")
               .tags("fsm", tags.kind(),
                     "node_id", tags.instance(),
                     "from", label(from),
                     "to", label(to))
               .register(registry)
               .increment();
    }

    @Contract
    @Override
    public void onCasLost(FsmTags tags, S expected, S actual) {
        Counter.builder("fsm_cas_lost_total")
               .tags("fsm", tags.kind(),
                     "node_id", tags.instance(),
                     "expected", label(expected),
                     "actual", label(actual))
               .register(registry)
               .increment();
    }

    @Contract
    @Override
    public void onEventIgnored(FsmTags tags, S state, E event) {
        Counter.builder("fsm_events_ignored_total")
               .tags("fsm", tags.kind(),
                     "node_id", tags.instance(),
                     "state", label(state),
                     "event", label(event))
               .register(registry)
               .increment();
    }

    @Contract
    @Override
    public void onHandled(FsmTags tags, S state, E event) {
        Counter.builder("fsm_events_handled_total")
               .tags("fsm", tags.kind(),
                     "node_id", tags.instance(),
                     "state", label(state),
                     "event", label(event))
               .register(registry)
               .increment();
    }

    private static String label(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }
}
