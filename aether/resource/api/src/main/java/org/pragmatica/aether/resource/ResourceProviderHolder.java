// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource;

import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

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

    // JBCT-RET-08: AtomicReference clear — null is the JDK sentinel, not Option-wrappable
    @SuppressWarnings("JBCT-RET-08")
    static Result<Unit> clear() {
        INSTANCE.set(null);

        return unitResult();
    }

    record unused() implements ResourceProviderHolder {}
}
