// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.concurrent.atomic.AtomicReference;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.unitResult;


sealed interface ResourceProviderHolder {
    AtomicReference<ResourceProvider> INSTANCE = new AtomicReference<>();

    static Option<ResourceProvider> instance() {
        return option(INSTANCE.get());
    }

    static Result<Unit> setInstance(ResourceProvider provider) {
        INSTANCE.set(provider);

        return unitResult();
    }

    static Result<Unit> clear() {
        INSTANCE.set(null);

        return unitResult();
    }

    record unused() implements ResourceProviderHolder {}
}
