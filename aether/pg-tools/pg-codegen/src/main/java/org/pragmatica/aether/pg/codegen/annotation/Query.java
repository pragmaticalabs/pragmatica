// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.codegen.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/// Specifies the SQL query for a persistence interface method.
///
/// The query uses named parameters with `:paramName` syntax, which the
/// annotation processor rewrites to PostgreSQL positional `$N` parameters.
///
/// Example:
/// ```{@code
/// @Query("SELECT id, name FROM users WHERE email = :email")
/// Promise<Option<UserRow>> findByEmail(String email);
/// }```
@Retention(RetentionPolicy.SOURCE) @Target(ElementType.METHOD) public@interface Query {
    String value();
}
