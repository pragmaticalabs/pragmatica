// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
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
