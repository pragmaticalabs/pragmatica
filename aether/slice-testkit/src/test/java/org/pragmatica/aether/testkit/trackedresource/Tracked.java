// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.testkit.trackedresource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.pragmatica.aether.slice.annotation.ResourceQualifier;


/// Binds a slice parameter to [TrackedResource]. A plain resource qualifier — not a publisher, not
/// a stream, not a configuration section — so the generator emits the no-context `provide(type,
/// section)` overload, which is the shape most deployed slices actually use.
@ResourceQualifier(type = TrackedResource.class, config = "tracked.resource")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Tracked {}
