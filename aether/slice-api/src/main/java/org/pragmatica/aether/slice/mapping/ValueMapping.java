// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.mapping;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;

/// Pure, reflection-free, boundary-neutral descriptor of how a value object `T` maps to and from a
/// single primitive representation `P`. It is the pair of functions a value object already owns:
///
///   - `lower` — the total accessor `T -> P` that unwraps the value object to its primitive
///     representation (e.g. `SeatId::raw`, `SeatState::code`). Unwrapping a valid value object can
///     never fail.
///   - `lift`  — the fallible factory `P -> Result<T>` that re-parses the raw primitive back into
///     the value object (e.g. `SeatId::seatId`, `SeatState::seatState`). Decoding is
///     parse-don't-validate at the boundary: a corrupt raw value fails the decode with a typed
///     cause rather than producing an invalid value object or throwing an exception.
///
/// The descriptor carries no DB, HTTP, or wire dependency — only [Fn1] and [Result] from Pragmatica
/// Core — so a value object declares its representation once, against its own domain primitive `P`,
/// without importing persistence or transport. Every boundary derives its binding from that single
/// declaration and composes the framework-owned `P <-> wire` leg itself, so no transport specifics
/// (SQL column, HTTP path segment, wire codec) ever leak into value-object code.
///
/// Boundaries discover the descriptor through the reflection-free convention that a value object
/// exposes a `public static ValueMapping<Self, P> valueMapping()` method. Each boundary's code
/// generator reads the declared return type at compile time to learn `P`; the method is never
/// invoked at compile time. Because the convention is a single conventionally-named static method,
/// a value object can carry at most one `ValueMapping` — two is a compiler error by construction —
/// so no ambiguity check is required.
///
///   - DB (`pg-codegen`): generates literal bind code (`Vo.valueMapping().lower().apply(value)`) and
///     guarded decode code (`row.get...(column).flatMap(Vo.valueMapping().lift())`).
///   - HTTP (`slice-processor`): composes the framework-owned `String -> P` parser with the value
///     object's `lift` so a value-object path/query segment is parsed to its primitive then lifted
///     into the value object, a lift failure yielding a typed 400.
///
/// Scope is single-primitive value objects (the wrapper 90%: IDs, enums, `Percent`). Multi-primitive
/// value objects (`Money(amountMinor, currency)`) need a future composite descriptor and are out of
/// scope here.
///
/// @param <T> the value object type
/// @param <P> the primitive representation (e.g. `UUID`, `String`, `Long`)
public record ValueMapping<T, P>(Fn1<P, T> lower, Fn1<Result<T>, P> lift) {
    /// Builds a descriptor from the value object's total accessor and fallible factory.
    public static <T, P> ValueMapping<T, P> of(Fn1<P, T> lower, Fn1<Result<T>, P> lift) {
        return new ValueMapping<>(lower, lift);
    }

    /// Escape hatch for accepted-risk round-trips: builds a descriptor whose decode can never fail.
    /// Use only when the raw value is guaranteed to reconstruct a valid value object (e.g. a trusted
    /// internal enum ordinal). Corruption is silently accepted rather than surfaced, so this is a
    /// visible, deliberate choice.
    public static <T, P> ValueMapping<T, P> trusted(Fn1<P, T> lower, Fn1<T, P> infallibleLift) {
        return new ValueMapping<>(lower, infallibleLift.then(Result::success));
    }
}
