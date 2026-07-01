// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

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
}
