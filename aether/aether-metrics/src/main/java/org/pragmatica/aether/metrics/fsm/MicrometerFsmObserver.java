// SPDX-License-Identifier: BUSL-1.1
// Licensed Work: aether — Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Change Date: 2030-01-01
// Change License: Apache-2.0
package org.pragmatica.aether.metrics.fsm;

import org.pragmatica.lang.Contract;
import org.pragmatica.statemachine.FsmObserver;
import org.pragmatica.statemachine.FsmTags;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;


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
               .tags("fsm",
                     tags.kind(),
                     "node_id",
                     tags.instance(),
                     "from",
                     label(from),
                     "to",
                     label(to))
               .register(registry)
               .increment();
    }

    @Contract
    @Override
    public void onCasLost(FsmTags tags, S expected, S actual) {
        Counter.builder("fsm_cas_lost_total")
               .tags("fsm",
                     tags.kind(),
                     "node_id",
                     tags.instance(),
                     "expected",
                     label(expected),
                     "actual",
                     label(actual))
               .register(registry)
               .increment();
    }

    @Contract
    @Override
    public void onEventIgnored(FsmTags tags, S state, E event) {
        Counter.builder("fsm_events_ignored_total")
               .tags("fsm",
                     tags.kind(),
                     "node_id",
                     tags.instance(),
                     "state",
                     label(state),
                     "event",
                     label(event))
               .register(registry)
               .increment();
    }

    @Contract
    @Override
    public void onHandled(FsmTags tags, S state, E event) {
        Counter.builder("fsm_events_handled_total")
               .tags("fsm",
                     tags.kind(),
                     "node_id",
                     tags.instance(),
                     "state",
                     label(state),
                     "event",
                     label(event))
               .register(registry)
               .increment();
    }

    @SuppressWarnings("JBCT-RET-06")  // observability label over an arbitrary observed Object; null renders as the literal "null" tag
    private static String label(Object value) {
        return value == null
               ? "null"
               : value.getClass()
                      .getSimpleName();
    }
}
