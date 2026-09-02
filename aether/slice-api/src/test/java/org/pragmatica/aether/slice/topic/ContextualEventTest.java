// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.topic;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/// Unit tests for the [ContextualEvent] carrier: the single value the runtime dispatch path hands to a
/// generated adapter, which unpacks it into the subscriber's `(T event, MessageContext context)` shape.
class ContextualEventTest {
    record SeatSold(String seatId) {}

    private static final MessageContext CONTEXT =
            MessageContext.messageContext("2Zk9QpVn7bTsW3xLmH0aRdYcFgJ", "acme.orders:seat-sold:1.0.0", 3, 4171L);

    @Test
    void contextualEvent_carriesPayloadAndContext() {
        var payload = new SeatSold("A-14");
        var contextual = ContextualEvent.contextualEvent(payload, CONTEXT);

        assertThat(contextual.event()).isSameAs(payload);
        assertThat(contextual.context()).isEqualTo(CONTEXT);
    }

    @Test
    void contextualEvent_factoryAgreesWithCanonicalConstructor() {
        var payload = new SeatSold("A-14");

        assertThat(ContextualEvent.contextualEvent(payload, CONTEXT)).isEqualTo(new ContextualEvent(payload, CONTEXT));
    }

    /// The payload is erased on purpose: the carrier accepts any type because the dispatch path that
    /// builds it holds no type information, and the generated adapter is the one place that knows the
    /// subscriber's declared type and casts to it. Pinned because narrowing `event` to a type parameter
    /// later would compile here while breaking every generated adapter.
    @Test
    void event_isErased_soAnyPayloadTypeIsCarried() {
        assertThat(ContextualEvent.contextualEvent(new SeatSold("A-14"), CONTEXT).event()).isInstanceOf(SeatSold.class);
        assertThat(ContextualEvent.contextualEvent("a bare string", CONTEXT).event()).isEqualTo("a bare string");
        assertThat(ContextualEvent.contextualEvent(42, CONTEXT).event()).isEqualTo(42);
    }

    /// Component order is what generated adapter code constructs against positionally, so it is pinned
    /// rather than left to the compiler — the two components have unrelated types, but a reorder would
    /// still be a silent source-compatibility break for every emitted adapter.
    @Test
    void recordComponents_declareTheSpecifiedNamesTypesAndOrder() {
        assertThat(Arrays.stream(ContextualEvent.class.getRecordComponents())
                         .map(component -> tuple(component.getName(), component.getType())))
                .containsExactly(tuple("event", Object.class),
                                 tuple("context", MessageContext.class));
    }

    @Test
    void equality_isByValueOverPayloadAndContext() {
        var payload = new SeatSold("A-14");

        assertThat(ContextualEvent.contextualEvent(payload, CONTEXT))
                .isEqualTo(ContextualEvent.contextualEvent(new SeatSold("A-14"), CONTEXT))
                .hasSameHashCodeAs(ContextualEvent.contextualEvent(new SeatSold("A-14"), CONTEXT))
                .isNotEqualTo(ContextualEvent.contextualEvent(new SeatSold("B-2"), CONTEXT));
    }
}
