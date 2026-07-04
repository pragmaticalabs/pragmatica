// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import org.pragmatica.aether.slice.SliceBridge;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Promise;

// East-west / topic / timer seam attachment (#277 increment 2). Wraps the ObservabilityInterceptor
// invocation at each of the invoker's dispatch sites with the per-injection-point cell resolved from the
// local bridge's InternalMethod — the created-once-per-method structure already in hand at every site,
// so no cell is minted or looked up outside the bridge's own per-call method resolution. The cell is the
// OUTER system layer (it observes the whole intercept, matching increment 1's "outer, runtime-switchable
// system layer"); while OFF it forwards `intercepted` untouched. Bridges without cells (stubs) fall
// straight through to the interceptor.
sealed interface ObservabilityCells {
    static <R> Promise<R> around(SliceBridge bridge, String methodName, Fn0<Promise<R>> intercepted) {
        return bridge.observabilityCell(methodName)
                     .map(cell -> cell.around(intercepted))
                     .or(intercepted::apply);
    }

    record unused() implements ObservabilityCells {}
}
