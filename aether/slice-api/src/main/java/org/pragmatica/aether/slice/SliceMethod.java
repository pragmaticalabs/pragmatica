// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;

import static org.pragmatica.lang.Result.success;


public record SliceMethod<R, T>(MethodName name,
                                Fn1<Promise<R>, T> method,
                                TypeToken<R> returnType,
                                TypeToken<T> parameterType) implements Fn1<Promise<R>, T> {
    public static <R, T> Result<SliceMethod<R, T>> sliceMethod(MethodName name,
                                                               Fn1<Promise<R>, T> method,
                                                               TypeToken<R> returnType,
                                                               TypeToken<T> parameterType) {
        return success(new SliceMethod<>(name, method, returnType, parameterType));
    }

    @Override
    public Promise<R> apply(T param1) {
        return method.apply(param1);
    }
}
