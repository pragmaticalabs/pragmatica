// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


public interface MethodHandle<R, T> {
    Promise<R> invoke(T request);
    Promise<Unit> fireAndForget(T request);
    String artifactCoordinate();
    MethodName methodName();

    default Result<Unit> materialize() {
        return Result.unitResult();
    }
}
