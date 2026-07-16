// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package com.example.voslice;

import java.util.UUID;

import org.pragmatica.aether.slice.mapping.ValueMapping;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// A value object keyed on a `UUID`, exposing a single [ValueMapping] so it binds identically at the
/// DB column and the HTTP path/query boundaries. The `seatId` factory rejects the nil UUID to
/// exercise the lift-failure path.
public record SeatId(UUID value) {
    private static final Cause NIL_SEAT = Causes.cause("seat id must not be the nil UUID");
    private static final UUID NIL = new UUID(0L, 0L);

    public static Result<SeatId> seatId(UUID raw) {
        return raw.equals(NIL)
               ? NIL_SEAT.result()
               : Result.success(new SeatId(raw));
    }

    public static ValueMapping<SeatId, UUID> valueMapping() {
        return ValueMapping.of(SeatId::value, SeatId::seatId);
    }
}
