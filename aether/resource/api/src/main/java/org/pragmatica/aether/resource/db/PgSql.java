// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import org.pragmatica.aether.slice.annotation.ResourceQualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/// Qualifier for PostgreSQL persistence adapters.
///
/// When used on a persistence interface, marks it for compile-time SQL validation
/// and code generation by the QueryAnnotationProcessor.
///
/// When used on a slice factory parameter, triggers PgSqlConnector provisioning
/// from the "database" config section.
///
/// For multiple datasources, create custom annotations:
/// {@code @ResourceQualifier(type = PgSqlConnector.class, config = "database.analytics")}
@ResourceQualifier(type = PgSqlConnector.class, config = "database") @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.PARAMETER, ElementType.TYPE}) public@interface PgSql {}
