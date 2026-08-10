// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.test.entity;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.pragmatica.aether.resource.entity.DurableEntity;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;


/// Parameter qualifier binding a [DurableEntity] to the `[entities.orders]` section of this
/// blueprint's `resources.toml`, which the runtime binds to a `DurableEntityConfig`.
///
/// Declared HERE, in the fixture, rather than imported from the resource module, because
/// `resource/durable-entity` ships NO qualifier annotation — there is no `@Entity` counterpart to
/// `@Http` (`resource/api/.../http/Http.java`) or `@Notify`
/// (`resource/notification/.../Notify.java`). A slice author therefore cannot declare a durable
/// entity without hand-rolling this file. That is a real DX gap for #345 I1, not a fixture
/// convenience; the precedent for a locally declared qualifier is
/// `examples/comprehensive-persistence/.../AnalyticsPgSql.java`.
@ResourceQualifier(type = DurableEntity.class, config = "entities.orders")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface OrderEntity {}
