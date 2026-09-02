// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.topic;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/// Unit tests for the [MessageContext] carrier: the delivery context a durable subscriber's two-argument
/// handler shape receives (durable-pubsub-spec §8).
class MessageContextTest {
    private static final String MESSAGE_ID = "2Zk9QpVn7bTsW3xLmH0aRdYcFgJ";
    private static final String TOPIC = "acme.orders:seat-sold:1.0.0";

    @Test
    void messageContext_carriesIdTopicAndSourcePosition() {
        var context = MessageContext.messageContext(MESSAGE_ID, TOPIC, 3, 4171L);

        assertThat(context.messageId()).isEqualTo(MESSAGE_ID);
        assertThat(context.topic()).isEqualTo(TOPIC);
        assertThat(context.partition()).isEqualTo(3);
        assertThat(context.offset()).isEqualTo(4171L);
    }

    @Test
    void messageContext_factoryAgreesWithCanonicalConstructor() {
        assertThat(MessageContext.messageContext(MESSAGE_ID, TOPIC, 3, 4171L))
                .isEqualTo(new MessageContext(MESSAGE_ID, TOPIC, 3, 4171L));
    }

    /// The component ORDER is load-bearing and cannot be checked by the compiler at the construction
    /// site: `messageId` and `topic` are adjacent `String` components, so transposing them still
    /// compiles everywhere — in generated adapter code above all, which builds this positionally — and
    /// the only symptom is an idempotency key silently built from the topic name, identical for every
    /// event on the topic. That failure collapses a whole topic into one dedup key rather than
    /// throwing, so it is pinned here where a reorder shows up as a red test instead.
    @Test
    void recordComponents_declareTheSpecifiedNamesTypesAndOrder() {
        assertThat(Arrays.stream(MessageContext.class.getRecordComponents())
                         .map(component -> tuple(component.getName(), component.getType())))
                .containsExactly(tuple("messageId", String.class),
                                 tuple("topic", String.class),
                                 tuple("partition", int.class),
                                 tuple("offset", long.class));
    }

    /// Value equality is what lets a context be carried through collections and compared in tests; the
    /// identity that actually keys deduplication is [MessageContext#messageId] alone, never the whole
    /// context — the two positional components differ between deliveries of the same event.
    @Test
    void equality_isByValueAcrossAllComponents() {
        var context = MessageContext.messageContext(MESSAGE_ID, TOPIC, 3, 4171L);

        assertThat(context).isEqualTo(MessageContext.messageContext(MESSAGE_ID, TOPIC, 3, 4171L))
                           .hasSameHashCodeAs(MessageContext.messageContext(MESSAGE_ID, TOPIC, 3, 4171L))
                           .isNotEqualTo(MessageContext.messageContext("6HdR1sYbN4vQmZpKtA8eXwLcJgU", TOPIC, 3, 4171L));
    }

    /// The redelivery/redrive contract in one assertion: the same event met at a different source
    /// position keeps its `messageId`, so a subscriber deduplicating on the id sees one event while a
    /// subscriber deduplicating on the position sees two. This pins the property the javadoc states and
    /// the D4 guard depends on.
    @Test
    void sameEventAtDifferentPosition_keepsMessageId_whilePositionDiffers() {
        var firstDelivery = MessageContext.messageContext(MESSAGE_ID, TOPIC, 3, 4171L);
        var afterRedrive = MessageContext.messageContext(MESSAGE_ID, TOPIC, 7, 12L);

        assertThat(afterRedrive.messageId()).isEqualTo(firstDelivery.messageId());
        assertThat(afterRedrive).isNotEqualTo(firstDelivery);
        assertThat(tuple(afterRedrive.partition(), afterRedrive.offset()))
                .isNotEqualTo(tuple(firstDelivery.partition(), firstDelivery.offset()));
    }

    @Test
    void recordComponents_areExactlyTheFourSpecified() {
        assertThat(MessageContext.class.getRecordComponents()).extracting(RecordComponent::getName)
                                                              .hasSize(4);
    }
}
