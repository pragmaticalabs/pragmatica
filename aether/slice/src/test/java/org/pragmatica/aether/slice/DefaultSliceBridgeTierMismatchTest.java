// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.topic.ContextualEvent;
import org.pragmatica.aether.slice.topic.MessageContext;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/// #1337: the bare-event path ([DefaultSliceBridge#invoke], the ephemeral RPC fan-out) must refuse a method whose parameter is
/// [ContextualEvent] with a typed, non-defect [TopicTierMismatch], never hand it the bare event.
class DefaultSliceBridgeTierMismatchTest {
    private static final Artifact ARTIFACT = Artifact.artifact("com.example:orders:1.0.0").unwrap();
    private static final MethodName CALL = MethodName.methodName("call").unwrap();

    @Test
    void invoke_contextTakingMethod_isRefusedAsTierMismatch_andNeverCalled() {
        var calls = new AtomicInteger();
        var bridge = DefaultSliceBridge.defaultSliceBridge(ARTIFACT, () -> List.of(contextMethod(calls)), workingCodec());

        var cause = failureOf(bridge);

        assertThat(cause).isInstanceOf(TopicTierMismatch.class);
        assertThat(cause).isNotInstanceOf(SliceDefect.class);
        assertThat(((TopicTierMismatch) cause).method()).isEqualTo("call");
        assertThat(calls.get()).as("the handler must not see a bare event").isZero();
    }

    /// Control: a bare-typed method on the same path is still delivered, so the refusal keys on the ContextualEvent parameter.
    @Test
    void invoke_bareMethod_isStillDelivered() {
        var calls = new AtomicInteger();
        var bridge = DefaultSliceBridge.defaultSliceBridge(ARTIFACT, () -> List.of(bareMethod(calls)), workingCodec());

        bridge.invoke("call", new byte[]{1}).await().onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));

        assertThat(calls.get()).isEqualTo(1);
    }

    /// Control: the durable path wraps the event for the same method, so the refusal is specific to the bare path.
    @Test
    void invokeWithContext_contextTakingMethod_isStillDelivered() {
        var calls = new AtomicInteger();
        var bridge = DefaultSliceBridge.defaultSliceBridge(ARTIFACT, () -> List.of(contextMethod(calls)), workingCodec());
        var context = new MessageContext("m-1", "orders", 0, 0L);

        bridge.invokeWithContext("call", new byte[]{1}, context).await().onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void tierMismatch_isRecognisedLocally_andFromRelayedText() {
        var local = TopicTierMismatch.FACTORY.apply("call");

        assertThat(TopicTierMismatch.isTierMismatch(local)).isTrue();
        assertThat(TopicTierMismatch.isTierMismatch(Causes.cause("Remote invocation failed: " + local.message()))).isTrue();
        assertThat(TopicTierMismatch.isTierMismatch(Causes.cause("Remote invocation failed: insufficient funds"))).isFalse();
    }

    private static Cause failureOf(DefaultSliceBridge bridge) {
        return bridge.invoke("call", new byte[]{1})
                     .await()
                     .fold(cause -> cause, _ -> Causes.cause("unexpected success"));
    }

    private static SliceCodec workingCodec() {
        var codec = Mockito.mock(SliceCodec.class);

        when(codec.decode(any(byte[].class))).thenReturn("request");
        when(codec.encode(any())).thenReturn(new byte[0]);

        return codec;
    }

    private static SliceMethod<String, ContextualEvent> contextMethod(AtomicInteger calls) {
        return new SliceMethod<>(CALL,
                                 _ -> {
                                     calls.incrementAndGet();
                                     return Promise.success("ok");
                                 },
                                 new TypeToken<String>() {},
                                 new TypeToken<ContextualEvent>() {});
    }

    private static SliceMethod<String, String> bareMethod(AtomicInteger calls) {
        return new SliceMethod<>(CALL,
                                 _ -> {
                                     calls.incrementAndGet();
                                     return Promise.success("ok");
                                 },
                                 new TypeToken<String>() {},
                                 new TypeToken<String>() {});
    }
}
