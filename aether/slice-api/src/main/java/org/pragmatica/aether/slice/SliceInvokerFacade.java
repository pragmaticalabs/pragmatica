// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;


public interface SliceInvokerFacade {
    <R, T> Result<MethodHandle<R, T>> methodHandle(String sliceArtifact,
                                                   String methodName,
                                                   TypeToken<T> requestType,
                                                   TypeToken<R> responseType);
}
