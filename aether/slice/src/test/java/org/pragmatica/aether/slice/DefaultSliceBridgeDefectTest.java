// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/// #1573: the bridge tags only ITS OWN failures as [SliceDefect]; a cause the method returned passes through
/// untouched, so the leader's all-instances-failed detector can never mistake a business failure for a
/// broken version.
class DefaultSliceBridgeDefectTest {
    private static final Artifact ARTIFACT = Artifact.artifact("com.example:orders:1.0.0").unwrap();
    private static final Cause BUSINESS = Causes.cause("insufficient funds");

    @Test
    void invoke_methodThrows_isMethodThrewDefect() {
        assertThat(failureOf(bridge(method(_ -> {
            throw new IllegalStateException("bug");
        }), workingCodec()), "call")).isInstanceOf(SliceDefect.MethodThrew.class);
    }

    @Test
    void invoke_businessFailure_passesThroughUntagged() {
        assertThat(failureOf(bridge(method(_ -> BUSINESS.promise()), workingCodec()), "call")).isSameAs(BUSINESS);
    }

    @Test
    void invoke_requestDecodeFails_isCodecFailedDefect() {
        var codec = Mockito.mock(SliceCodec.class);

        when(codec.decode(any(byte[].class))).thenThrow(new IllegalArgumentException("no codec"));

        assertThat(failureOf(bridge(method(_ -> Promise.success("ok")), codec), "call")).isInstanceOf(SliceDefect.CodecFailed.class);
    }

    @Test
    void invoke_unknownMethod_isMethodNotFoundDefect() {
        assertThat(failureOf(bridge(method(_ -> Promise.success("ok")), workingCodec()), "missing")).isInstanceOf(SliceDefect.MethodNotFound.class);
    }

    private static Cause failureOf(DefaultSliceBridge bridge, String method) {
        return bridge.invoke(method, new byte[]{1})
                     .await()
                     .fold(cause -> cause, _ -> Causes.cause("unexpected success"));
    }

    private static SliceCodec workingCodec() {
        var codec = Mockito.mock(SliceCodec.class);

        when(codec.decode(any(byte[].class))).thenReturn("request");
        when(codec.encode(any())).thenReturn(new byte[0]);

        return codec;
    }

    private static DefaultSliceBridge bridge(SliceMethod<?, ?> method, SliceCodec codec) {
        return DefaultSliceBridge.defaultSliceBridge(ARTIFACT, () -> List.of(method), codec);
    }

    private static SliceMethod<String, String> method(Function<String, Promise<String>> body) {
        return new SliceMethod<>(MethodName.methodName("call").unwrap(),
                                 body::apply,
                                 new TypeToken<String>() {},
                                 new TypeToken<String>() {});
    }
}
