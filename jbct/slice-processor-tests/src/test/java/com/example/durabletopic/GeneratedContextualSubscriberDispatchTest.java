// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package com.example.durabletopic;

import java.lang.reflect.Proxy;

import org.pragmatica.aether.slice.ResourceProviderFacade;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceCreationContext;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.aether.slice.topic.ContextualEvent;
import org.pragmatica.aether.slice.topic.MessageContext;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

/// #1295, against the REAL generated factory (not a hand-written copy of its shape): the runtime bridge
/// hands a subscriber [ContextualEvent] exactly when the method's declared parameter type is
/// [ContextualEvent] (`DefaultSliceBridge.invokeWithContext`). This pins the codegen side of that
/// contract — the generated 2-arg method declares that parameter type, accepts a `ContextualEvent`, and
/// fails with a ClassCastException on the bare event, which is precisely the failure every durable
/// delivery to a 2-arg subscriber hit before #1295.
class GeneratedContextualSubscriberDispatchTest {
    private static final MessageContext CONTEXT = MessageContext.messageContext("2mKsuidMessageId",
                                                                                "com.example:order-events:1.0.0",
                                                                                0,
                                                                                11L);
    private static final OrderPlaced EVENT = new OrderPlaced("order-1", 25L);

    @Test
    void generatedTwoArgSubscriber_declaresContextualEventAsItsParameterType() {
        assertThat(onOrderPlaced().parameterType()
                                  .rawType()).isEqualTo(ContextualEvent.class);
    }

    @Test
    void generatedTwoArgSubscriber_acceptsAContextualEvent() {
        contextualAdapter().apply(ContextualEvent.contextualEvent(EVENT, CONTEXT))
                           .await()
                           .onFailure(cause -> fail("the generated adapter must accept its ContextualEvent: " + cause.message()));
    }

    @Test
    void generatedTwoArgSubscriber_rejectsTheBareEvent_withAClassCastException() {
        assertThatThrownBy(() -> bareAdapter().apply(EVENT)).isInstanceOf(ClassCastException.class);
    }

    private static SliceMethod<?, ?> onOrderPlaced() {
        return generatedSlice().methods()
                               .stream()
                               .filter(method -> method.name()
                                                       .name()
                                                       .equals("onOrderPlaced"))
                               .findFirst()
                               .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static SliceMethod<?, ContextualEvent> contextualAdapter() {
        return (SliceMethod<?, ContextualEvent>) onOrderPlaced();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static SliceMethod<?, Object> bareAdapter() {
        return (SliceMethod) onOrderPlaced();
    }

    private static Slice generatedSlice() {
        return DurableOrderSliceFactory.durableOrderSliceSlice(SliceCreationContext.sliceCreationContext(unused(SliceInvokerFacade.class),
                                                                                                        unused(ResourceProviderFacade.class)))
                                       .await()
                                       .unwrap();
    }

    /// The subscriber-only fixture needs neither an invoker nor resources; any call is a test error.
    private static <T> T unused(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),
                                                new Class[]{type},
                                                (_, method, _) -> {
                                                    throw new UnsupportedOperationException(type.getSimpleName() + "."
                                                                                            + method.getName());
                                                }));
    }
}
