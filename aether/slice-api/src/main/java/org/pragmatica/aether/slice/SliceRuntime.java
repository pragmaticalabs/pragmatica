// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;


public sealed interface SliceRuntime {
    record unused() implements SliceRuntime {
        static Result<unused > unused() {
            return success(new unused());
        }
    }

    static Result<SliceInvokerFacade> getSliceInvoker() {
        return option(SliceRuntimeHolder.INVOKER_REF.get()).toResult(SliceRuntimeError.InvokerNotConfigured.INSTANCE);
    }

    static Option<SliceInvokerFacade> trySliceInvoker() {
        return option(SliceRuntimeHolder.INVOKER_REF.get());
    }

    static Result<Unit> setSliceInvoker(SliceInvokerFacade invoker) {
        SliceRuntimeHolder.INVOKER_REF.set(invoker);

        return success(unit());
    }

    static Result<Unit> clear() {
        SliceRuntimeHolder.INVOKER_REF.set(null);

        return success(unit());
    }
}

sealed interface SliceRuntimeHolder {
    record unused() implements SliceRuntimeHolder {}

    java.util.concurrent.atomic.AtomicReference<SliceInvokerFacade> INVOKER_REF = new java.util.concurrent.atomic.AtomicReference<>();
}
