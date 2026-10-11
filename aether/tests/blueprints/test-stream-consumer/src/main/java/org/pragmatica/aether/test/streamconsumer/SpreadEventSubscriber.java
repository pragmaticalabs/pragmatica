// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.test.streamconsumer;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.pragmatica.aether.slice.StreamSubscriber;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;


/// Declarative consumer qualifier for the MULTI-PARTITION stream (#535). Same mechanism as
/// [ConsumerEventSubscriber]; the only variable is that `streams.spread-events` has more partitions
/// than a placement-restricted deployment can possibly own, which is what forces the assignee to read
/// through the owner rather than locally.
@ResourceQualifier(type = StreamSubscriber.class, config = "streams.spread-events")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SpreadEventSubscriber {}
