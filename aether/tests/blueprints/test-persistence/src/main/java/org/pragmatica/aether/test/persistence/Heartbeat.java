// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.test.persistence;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.pragmatica.aether.slice.Scheduled;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;


/// Method-level qualifier marking a slice method as a scheduled task whose
/// schedule lives at `[scheduling.heartbeat]` in the blueprint's `resources.toml`.
/// The annotated method must take zero parameters and return `Promise<Unit>`
/// (validated by the slice processor in `MethodModel.validateScheduled`).
@ResourceQualifier(type = Scheduled.class, config = "scheduling.heartbeat")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Heartbeat {}
