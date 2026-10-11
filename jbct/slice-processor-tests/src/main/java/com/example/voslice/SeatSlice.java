// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.voslice;

import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;


/// Test slice verifying value-object HTTP path/query binding (#397): a path segment binds directly
/// to a [SeatId], and an optional query segment binds as `Option<SeatId>`. Both are lifted through
/// the value object's `valueMapping()` at the boundary.
@Slice
public interface SeatSlice {
    // Path param bound to a value object: GET /api/seats/{seatId}
    Promise<SeatResponse> getSeat(GetSeatRequest request);
    // Query param bound to a value object: GET /api/seats/find?seat
    Promise<SeatResponse> findSeat(FindSeatRequest request);

    static SeatSlice seatSlice() {
        return new SeatSlice() {
            @Override
            public Promise<SeatResponse> getSeat(GetSeatRequest request) {
                return Promise.success(new SeatResponse(request.seatId().value().toString(),
                                                        "seat"));
            }

            @Override
            public Promise<SeatResponse> findSeat(FindSeatRequest request) {
                return Promise.success(request.seat()
                                              .map(id -> new SeatResponse(id.value().toString(),
                                                                          "found"))
                                              .or(new SeatResponse("none", "absent")));
            }
        };
    }
}
