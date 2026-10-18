// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra;

import java.util.List;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// Generated construction contract. Dependency references are injected once during startup.
public interface TerraFactory<T> {
    String artifact();
    Class<T> sliceType();
    List<Class<?>> dependencies();
    Promise<T> create(TerraContext context);

    default Result<Unit> bind(T instance, TerraContext context) {
        return Result.unitResult();
    }
}
