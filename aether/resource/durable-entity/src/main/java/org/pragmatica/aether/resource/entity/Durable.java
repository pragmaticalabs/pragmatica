// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.pragmatica.aether.slice.annotation.ResourceQualifier;


/// Parameter qualifier binding a [DurableEntity] to an `[entities.*]` section of the blueprint's
/// `resources.toml` — the durable-entity counterpart of `@Http` (`HttpClient`) and `@Notify`
/// ([org.pragmatica.aether.resource.notification.NotificationSender]), which the module shipped
/// without until #345 I1(c). Every author had to hand-roll one; the I0 fixture's `OrderEntity` was
/// exactly that hand-roll.
///
/// ## Naming
/// Named for the CAPABILITY, like its siblings (`@Http HttpClient`, `@Notify NotificationSender`,
/// `@Durable DurableEntity`), and deliberately NOT `@Entity`: `jakarta.persistence.Entity` is a
/// TYPE-target annotation marking a mapped table, whereas this is a PARAMETER-target resource
/// qualifier. The two would be import-ambiguous in any slice that also touches JPA, and the
/// resemblance would be misleading exactly where it was noticed.
///
/// ## Declaring more than one entity family
/// A slice with a single entity family binds the default `[entities]` section:
/// ```
/// static OrderSlice orderSlice(@Durable DurableEntity<String, OrderState> orders) { ... }
/// ```
/// A slice with several names its section per parameter, the alias convention `[streams.X]` uses:
/// ```
/// static Slice slice(@Durable(config = "entities.orders") DurableEntity<String, OrderState> orders,
///                    @Durable(config = "entities.customers") DurableEntity<String, Customer> customers) { ... }
/// ```
/// The `config` member overrides the meta-annotation default; `ResourceQualifierModel` reads the
/// user-supplied value in preference to it, so no per-alias qualifier type has to be declared.
@ResourceQualifier(type = DurableEntity.class, config = "entities")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Durable {
    /// The `resources.toml` section this entity binds to. Defaults to `entities`; name an
    /// `entities.<alias>` subsection when a slice declares more than one entity family.
    String config() default "entities";
}
