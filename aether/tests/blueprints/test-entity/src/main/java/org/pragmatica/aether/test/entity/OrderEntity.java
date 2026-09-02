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
/// Declared HERE, in the fixture, because that is the PATTERN, not a gap: durable entities are
/// per-keyspace, so the resource module ships no `@Entity` counterpart to `@Http` / `@Notify`
/// (both of which name a single fixed section and can therefore be parameterless). One qualifier
/// per keyspace keeps the section name in exactly one place and leaves the use site a bare
/// `@OrderEntity` with no string in it. See [DurableEntity]'s type javadoc for the full rule.
///
/// The precedent for a locally declared qualifier is
/// `examples/comprehensive-persistence/.../AnalyticsPgSql.java`.
@ResourceQualifier(type = DurableEntity.class, config = "entities.orders")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface OrderEntity {}
