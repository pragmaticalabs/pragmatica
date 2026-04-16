// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;


/// Facade interface for slice invocation.
/// This is a simplified interface in slice-api that the full SliceInvoker implements.
///
/// Slices use this to call other slices without depending on the node module.
public interface SliceInvokerFacade {
    <R, T> Result<MethodHandle<R, T>> methodHandle(String sliceArtifact,
                                                   String methodName,
                                                   TypeToken<T> requestType,
                                                   TypeToken<R> responseType);
}
