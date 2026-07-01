// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.repr;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;

/// Pure, reflection-free descriptor of how a single-column value object maps to and from its
/// SQL representation. It is the pair of functions a value object already owns:
///
///   - `lower` — the total accessor `T -> P` that unwraps the value object to the primitive
///     column type (e.g. `SeatId::raw`, `SeatState::dbValue`). Binding is total: unwrapping a
///     valid value object can never fail.
///   - `lift`  — the fallible factory `P -> Result<T>` that re-parses the raw column value back
///     into the value object (e.g. `SeatId::seatId`, `SeatState::seatState`). Decoding is
///     parse-don't-validate at the database boundary: a corrupt column value fails the decode
///     with a typed cause rather than producing an invalid value object.
///
/// The descriptor carries no Aether, JDBC, or persistence dependency — only [Fn1] and [Result]
/// from Pragmatica Core — so a value object can declare its own representation without its
/// module importing persistence. `pg-codegen` discovers the descriptor through the convention
/// that a value object exposes a `public static PgRepr<Self, P> pgRepr()` method, then generates
/// literal bind code (`vo.pgRepr().lower().apply(value)`) and decode code
/// (`row.get...(column).flatMap(Vo.pgRepr().lift())`) — open the generated factory and read it.
///
/// Scope is single-column value objects (the wrapper 90%: IDs, enums, `Percent`). Multi-column
/// value objects (`Money(amountMinor, currency)`) need a future `PgComposite<Money>` and are out
/// of scope here.
///
/// @param <T> the value object type
/// @param <P> the primitive SQL-column representation (e.g. `UUID`, `String`, `Long`)
public record PgRepr<T, P>(Fn1<P, T> lower, Fn1<Result<T>, P> lift) {
    /// Builds a descriptor from the value object's total accessor and fallible factory.
    public static <T, P> PgRepr<T, P> of(Fn1<P, T> lower, Fn1<Result<T>, P> lift) {
        return new PgRepr<>(lower, lift);
    }

    /// Escape hatch for accepted-risk round-trips: builds a descriptor whose decode can never
    /// fail. Use only when the raw column value is guaranteed to reconstruct a valid value
    /// object (e.g. a trusted internal enum ordinal). Corruption at the column is silently
    /// accepted rather than surfaced, so this is a visible, deliberate choice.
    public static <T, P> PgRepr<T, P> trusted(Fn1<P, T> lower, Fn1<T, P> infallibleLift) {
        return new PgRepr<>(lower, infallibleLift.then(Result::success));
    }
}
