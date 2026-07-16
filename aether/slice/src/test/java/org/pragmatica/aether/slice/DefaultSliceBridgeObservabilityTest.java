// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.serialization.SliceCodec;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// #277 increment 2, east-west/topic/timer seam: DefaultSliceBridge mints one per-injection-point cell
/// per method at bridge build (the created-once-per-method InternalMethod structure), keyed
/// `artifactBase/methodName` — the same key the interceptor and config registry use — and exposes them
/// for the dispatch sites (observabilityCell) and the registrar lifecycle (observabilityCells).
class DefaultSliceBridgeObservabilityTest {
    private static final Artifact ARTIFACT = Artifact.artifact("com.example:my-slice:1.0.0").unwrap();

    private static DefaultSliceBridge bridge() {
        return DefaultSliceBridge.defaultSliceBridge(ARTIFACT, echoSlice(), Mockito.mock(SliceCodec.class));
    }

    @Test
    void defaultSliceBridge_mintsOneCellPerMethod_keyedByArtifactBaseAndMethod() {
        var bridge = bridge();

        assertThat(bridge.observabilityCells()).hasSize(1);
        assertThat(bridge.observabilityCell("echo").isPresent()).isTrue();
        bridge.observabilityCell("echo")
              .onPresent(cell -> assertThat(cell.key()).isEqualTo("com.example:my-slice/echo"));
    }

    @Test
    void observabilityCell_returnsNone_forUnknownMethod() {
        assertThat(bridge().observabilityCell("missing").isEmpty()).isTrue();
    }

    @Test
    void observabilityCell_matchesTheStableCell_inObservabilityCells() {
        var bridge = bridge();
        var listed = bridge.observabilityCells()
                           .getFirst();

        assertThat(bridge.observabilityCell("echo")
                         .or(listed)).isSameAs(listed);
    }

    private static Slice echoSlice() {
        return () -> List.of(echoMethod());
    }

    private static SliceMethod<?, ?> echoMethod() {
        return new SliceMethod<>(MethodName.methodName("echo").unwrap(),
                                 (String value) -> Promise.success("echo:" + value),
                                 new TypeToken<String>() {},
                                 new TypeToken<String>() {});
    }
}
