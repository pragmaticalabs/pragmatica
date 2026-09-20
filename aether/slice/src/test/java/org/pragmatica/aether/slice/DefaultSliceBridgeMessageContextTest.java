// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.topic.ContextualEvent;
import org.pragmatica.aether.slice.topic.MessageContext;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/// #1295: a durable-topic delivery carries a [MessageContext]. The bridge chooses the shape from the
/// target method's OWN declared parameter type: a generated 2-arg subscriber adapter declares
/// [ContextualEvent] and receives `contextualEvent(event, context)`; a 1-arg subscriber declares the
/// event type and receives the bare event, exactly as before. Before #1295 the dispatcher only ever
/// handed over the bare event, and the 2-arg adapter failed every delivery with a ClassCastException.
class DefaultSliceBridgeMessageContextTest {
    private static final Artifact ARTIFACT = Artifact.artifact("com.example:orders:1.0.0").unwrap();
    private static final String EVENT = "order-1";
    private static final MessageContext CONTEXT = MessageContext.messageContext("2mKsuidMessageId",
                                                                                "org.example:order-events:1.0.0",
                                                                                3,
                                                                                42L);

    @Test
    void invokeWithContext_deliversContextualEvent_toATwoArgumentSubscriber() {
        var seen = new AtomicReference<ContextualEvent>();
        var bridge = bridgeWith(contextualMethod("onPlacedWithContext", seen));

        bridge.invokeWithContext("onPlacedWithContext", new byte[]{1}, CONTEXT)
              .await()
              .onFailure(cause -> fail("a 2-arg subscriber must receive its event and context: " + cause.message()));

        assertThat(seen.get()).isEqualTo(ContextualEvent.contextualEvent(EVENT, CONTEXT));
    }

    @Test
    void invokeWithContext_deliversTheBareEvent_toAOneArgumentSubscriber() {
        var seen = new AtomicReference<Object>();
        var bridge = bridgeWith(bareMethod("onPlaced", seen));

        bridge.invokeWithContext("onPlaced", new byte[]{1}, CONTEXT)
              .await()
              .onFailure(cause -> fail("a 1-arg subscriber must receive the bare event: " + cause.message()));

        assertThat(seen.get()).isEqualTo(EVENT);
    }

    @Test
    void invokeWithContext_choosesTheShapePerMethod_onOneBridge() {
        var contextual = new AtomicReference<ContextualEvent>();
        var bare = new AtomicReference<Object>();
        var bridge = bridgeWith(contextualMethod("onPlacedWithContext", contextual), bareMethod("onPlaced", bare));

        bridge.invokeWithContext("onPlacedWithContext", new byte[]{1}, CONTEXT).await();
        bridge.invokeWithContext("onPlaced", new byte[]{1}, CONTEXT).await();

        assertThat(contextual.get().context()
                             .messageId()).isEqualTo(CONTEXT.messageId());
        assertThat(bare.get()).isEqualTo(EVENT);
    }

    @Test
    void invokeWithContext_failsForAnUnknownMethod() {
        bridgeWith(bareMethod("onPlaced", new AtomicReference<>()))
            .invokeWithContext("missing", new byte[]{1}, CONTEXT)
            .await()
            .onSuccess(_ -> fail("an unknown method must not be invoked"));
    }

    private static DefaultSliceBridge bridgeWith(SliceMethod<?, ?>... methods) {
        var codec = Mockito.mock(SliceCodec.class);

        when(codec.decode(any(byte[].class))).thenReturn(EVENT);
        when(codec.encode(any())).thenReturn(new byte[0]);

        return DefaultSliceBridge.defaultSliceBridge(ARTIFACT, () -> List.of(methods), codec);
    }

    /// The exact shape `FactoryClassGenerator` emits for a `(T event, MessageContext context)` subscriber.
    private static SliceMethod<Unit, ContextualEvent> contextualMethod(String name, AtomicReference<ContextualEvent> seen) {
        return new SliceMethod<>(MethodName.methodName(name).unwrap(),
                                 contextual -> record(seen, ContextualEvent.contextualEvent((String) contextual.event(),
                                                                                            contextual.context())),
                                 new TypeToken<Unit>() {},
                                 new TypeToken<ContextualEvent>() {});
    }

    private static SliceMethod<Unit, String> bareMethod(String name, AtomicReference<Object> seen) {
        return new SliceMethod<>(MethodName.methodName(name).unwrap(),
                                 event -> record(seen, event),
                                 new TypeToken<Unit>() {},
                                 new TypeToken<String>() {});
    }

    private static <T> Promise<Unit> record(AtomicReference<T> seen, T value) {
        seen.set(value);
        return Promise.unitPromise();
    }
}
