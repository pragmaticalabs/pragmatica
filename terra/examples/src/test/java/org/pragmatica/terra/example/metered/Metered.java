// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.example.metered;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.pragmatica.aether.resource.interceptor.MetricsMethodInterceptor;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;

/// #3650 Terra fixture: one method metered through the `@Metrics`-style interceptor qualifier.
@Slice
public interface Metered {
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @ResourceQualifier(type = MetricsMethodInterceptor.class, config = "metrics.greet")
    @interface Metrics {}

    @Metrics
    Promise<String> greet(String name);

    static Metered metered() {
        return name -> Promise.success("hello " + name);
    }
}
