// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/// #2157: the bridge chooses the argument from the method's declared parameter type. A method declaring
/// [CallerRequest] (generated for a slice method taking a `SecurityContext`) gets the decoded payload wrapped with the
/// caller it was handed; a plain [SliceBridge#invoke] hands it an empty caller, never a stand-in one. Any other method
/// gets the bare payload.
class DefaultSliceBridgeCallerTest {
    private static final Artifact ARTIFACT = Artifact.artifact("com.example:orders:1.0.0").unwrap();
    private static final String PAYLOAD = "order-1";
    private static final CallerIdentity CALLER = new CallerIdentity() {};

    @Test
    void invokeAsCaller_deliversThePayloadAndTheCaller_toACallerTakingMethod() {
        var seen = new AtomicReference<CallerRequest>();

        call(bridgeWith(callerMethod("whoAmI", seen)).invokeAsCaller("whoAmI", new byte[]{1}, Option.some(CALLER)));

        assertThat(seen.get()).isEqualTo(CallerRequest.callerRequest(PAYLOAD, Option.some(CALLER)));
    }

    @Test
    void invoke_deliversAnEmptyCaller_toACallerTakingMethod() {
        var seen = new AtomicReference<CallerRequest>();

        call(bridgeWith(callerMethod("whoAmI", seen)).invoke("whoAmI", new byte[]{1}));

        assertThat(seen.get()).isEqualTo(CallerRequest.callerRequest(PAYLOAD, Option.none()));
    }

    @Test
    void invokeAsCaller_deliversTheBarePayload_toAnyOtherMethod() {
        var seen = new AtomicReference<Object>();

        call(bridgeWith(bareMethod("plain", seen)).invokeAsCaller("plain", new byte[]{1}, Option.some(CALLER)));

        assertThat(seen.get()).isEqualTo(PAYLOAD);
    }

    private static void call(Promise<byte[]> invocation) {
        invocation.await()
                  .onFailure(cause -> fail("the method must be invoked: " + cause.message()));
    }

    private static DefaultSliceBridge bridgeWith(SliceMethod<?, ?>... methods) {
        var codec = Mockito.mock(SliceCodec.class);

        when(codec.decode(any(byte[].class))).thenReturn(PAYLOAD);
        when(codec.encode(any())).thenReturn(new byte[0]);

        return DefaultSliceBridge.defaultSliceBridge(ARTIFACT, () -> List.of(methods), codec);
    }

    /// The shape `FactoryClassGenerator` emits for a slice method taking a `SecurityContext`.
    private static SliceMethod<Unit, CallerRequest> callerMethod(String name, AtomicReference<CallerRequest> seen) {
        return new SliceMethod<>(MethodName.methodName(name).unwrap(),
                                 request -> record(seen, request),
                                 new TypeToken<Unit>() {},
                                 new TypeToken<CallerRequest>() {});
    }

    private static SliceMethod<Unit, String> bareMethod(String name, AtomicReference<Object> seen) {
        return new SliceMethod<>(MethodName.methodName(name).unwrap(),
                                 payload -> record(seen, payload),
                                 new TypeToken<Unit>() {},
                                 new TypeToken<String>() {});
    }

    private static <T> Promise<Unit> record(AtomicReference<T> seen, T value) {
        seen.set(value);
        return Promise.unitPromise();
    }
}
