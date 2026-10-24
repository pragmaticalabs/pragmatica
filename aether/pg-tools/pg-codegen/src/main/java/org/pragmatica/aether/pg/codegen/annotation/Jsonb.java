// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.pg.codegen.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/// Marks a value stored as a JSON document in a `json` or `jsonb` column. The generated factory encodes and
/// decodes it with a generated JSON codec; the declared type must be a record or an enum the codec generator
/// supports, otherwise compilation fails with the generator's reason.
///
/// The marker is needed only where the schema cannot tell: where it knows the column is json/jsonb (a CRUD row
/// record or condition param, a `@Query` param the SQL syntax tree pairs with the column: an `INSERT` value, a `SET`
/// item or a comparison with the column; a record expanded into `VALUES (:record)` / `SET :record`), a record or enum,
/// or `Option` of one, is a document without it. It stays required for `@Query` results, a record param bound whole
/// instead of expanded, and params the tree does not pair: operands of `@>`, function arguments, other expressions.
///
/// - On a method: the method's single result column is the document (`Promise<T>`, `Promise<Option<T>>` or
///   `Promise<List<T>>`). `Option` means zero or one row: a missing row is empty, but a row whose column is SQL NULL
///   fails the read. A nullable document is read through an `Option<T>` record component.
/// - On a method parameter: the argument is bound as the document.
/// - On a record component: that column of a row record (read or written) is the document. An `Option<T>`
///   component reads SQL NULL as empty and writes empty as NULL.
///
/// Where the column's type is known (a CRUD method's table, a `WHERE column = :param` pair) it must be `json`
/// or `jsonb`. `CLASS` retention keeps the marker visible on records compiled in another module.
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface Jsonb {}
