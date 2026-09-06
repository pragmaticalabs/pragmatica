// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.testkit.deployedconfig;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.pragmatica.aether.slice.annotation.ConfigurationSection;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;


/// The repo's first `ConfigurationSection` qualifier (#889).
///
/// The books teach this resource kind as first-class (`book-aether/part1-no-magic.md`,
/// `custom-qualifiers.md`) yet nothing in the repo, the 24 examples or the ticketing demo declared
/// one. The likelier reading of that absence, once the deployed `ctx.config()` turned out to be a
/// facade that fails every read, is that it could not work — so this fixture exists to make the
/// claim falsifiable rather than to fill an adoption gap.
@ResourceQualifier(type = ConfigurationSection.class, config = "deployed.endpoint")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface EndpointSettings {}
