// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;


@FunctionalInterface
public interface MethodInterceptor {
    <R, T> Fn1<Promise<R>, T> intercept(Fn1<Promise<R>, T> method);
}
