// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.testkit;

import org.pragmatica.aether.slice.MethodHandle;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;


/// Invoker for the single-slice-under-test seam. Slice-to-slice invocation is deferred to a later
/// milestone (spec §6, assumption [A6]); any request fails with a clear, named error instead of
/// silently returning a broken handle.
enum NoOpSliceInvoker implements SliceInvokerFacade {
    INSTANCE;

    @Override
    public <R, T> Result<MethodHandle<R, T>> methodHandle(String sliceArtifact,
                                                          String methodName,
                                                          TypeToken<T> requestType,
                                                          TypeToken<R> responseType) {
        return new TestKitError.UnscriptedInteraction("Slice-to-slice invocation is not supported by the slice test kit MVP: "
                                                      + sliceArtifact + "." + methodName + "() (assumption A6)").result();
    }
}
