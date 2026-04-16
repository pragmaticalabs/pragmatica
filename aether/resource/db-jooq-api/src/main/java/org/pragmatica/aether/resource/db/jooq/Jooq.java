// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.db.jooq;

import org.pragmatica.aether.slice.annotation.ResourceQualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/// Resource qualifier for injecting JooqConnector instances.
///
/// Use this annotation on factory method parameters to inject a JooqConnector
/// configured from the "database" section of aether.toml.
///
/// Transport (JDBC or R2DBC) is selected automatically based on configuration:
/// - If `r2dbc_url` is present → R2DBC transport
/// - Otherwise → JDBC transport (default)
///
/// @see JooqConnector
/// @see ResourceQualifier
@ResourceQualifier(type = JooqConnector.class, config = "database") @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.PARAMETER) public@interface Jooq {}
