// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.List;

import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceCreationContext;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;

/// The slice class of [WorkerConsumerSlice]: a slice with one stream-consumer method, `onEvent`, that accepts and drops every event.
///
/// It is resolved from the test classpath (the slice class loader's parent is the node's own class loader), so the staged jar carries only the
/// manifest a real slice jar has. Public and named `<Name>Factory` with a `<name>Slice` method because that is the shape `SliceFactory` looks for.
public final class WorkerConsumerFactory {
    private WorkerConsumerFactory() {}

    public static Promise<Slice> workerConsumerSlice(SliceCreationContext context) {
        return MethodName.methodName("onEvent")
                         .flatMap(name -> SliceMethod.sliceMethod(name, WorkerConsumerFactory::drop, new TypeToken<Unit>() {}, new TypeToken<String>() {}))
                         .map(WorkerConsumerFactory::sliceOf)
                         .async();
    }

    private static Slice sliceOf(SliceMethod<Unit, String> onEvent) {
        return () -> List.<SliceMethod<?, ?>> of(onEvent);
    }

    private static Promise<Unit> drop(String event) {
        return Promise.unitPromise();
    }
}
