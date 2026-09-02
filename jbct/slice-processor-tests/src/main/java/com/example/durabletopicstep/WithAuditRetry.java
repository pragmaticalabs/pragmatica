// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package com.example.durabletopicstep;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.pragmatica.aether.slice.MethodInterceptor;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;

/// A method interceptor on the HOST slice's own business method, deliberately placed so this
/// fixture pairs an interceptor with a context-carrying subscriber that is reached transitively.
///
/// The interceptor wrapper is generated per SLICE and walks the slice's OWN methods, so it sees
/// `report` and never the step's subscriber. That is why this combination is legal while
/// interceptor-plus-direct-subscriber is refused, and this fixture is what holds that line: a guard
/// widened bluntly to "slice has interceptors" would refuse this and the module would stop
/// compiling.
@ResourceQualifier(type = MethodInterceptor.class, config = "retry.audit")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface WithAuditRetry {}
