// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Result.success;


/// Typed failure raised when a row value cannot be decoded into its target value object.
///
/// Value-object columns are decoded with the value object's fallible `lift` (parse-don't-validate
/// at the database boundary), so a corrupt or unexpected column value fails the surrounding row
/// decode instead of throwing or silently producing an invalid value object. The failure names
/// the offending `column` and carries the underlying [Cause], so it surfaces on the [Result] /
/// `Promise` the caller is already on. Generated factories wrap each value-object column decode
/// with [#guard(String, Result)].
public sealed interface RowDecodeError extends Cause {
    /// A row value in `column` failed to decode; `cause` is the underlying validation or
    /// row-access failure.
    record RowDecode(String column, Cause cause) implements RowDecodeError {
        @Override
        public String message() {
            return "Failed to decode column '" + column + "': " + cause.message();
        }
    }

    /// Wraps a single-column decode so any failure carries the column name as a typed cause.
    /// Used by generated row mappers around a value-object column's `lift`.
    static <T> Result<T> guard(String column, Result<T> decoded) {
        return decoded.mapError(cause -> new RowDecode(column, cause));
    }

    /// Decodes a nullable column read: an empty `raw` (SQL NULL) stays empty and is never decoded; a present
    /// value goes through `lift`, whose failure fails the decode. Used by generated row mappers for an
    /// `Option<T>` component that needs a `lift` (a value object or a `@Jsonb` document).
    static <P, T> Result<Option<T>> liftOptional(Option<P> raw, Fn1<Result<T>, P> lift) {
        return raw.map(lift)
                  .fold(() -> success(none()), RowDecodeError::present);
    }

    private static <T> Result<Option<T>> present(Result<T> decoded) {
        return decoded.map(Option::some);
    }
}
