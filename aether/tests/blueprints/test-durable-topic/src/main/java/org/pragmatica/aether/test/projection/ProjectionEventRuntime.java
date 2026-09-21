// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.test.projection;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.pragmatica.aether.resource.projection.ProjectionRuntime;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;


/// Binds the node's [ProjectionRuntime] for the `projection-events` topic (#1333): the same section the
/// publisher and subscriber above use, so the runtime resolves exactly the stream the group consumes.
@ResourceQualifier(type = ProjectionRuntime.class, config = "projection-events")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ProjectionEventRuntime {}
