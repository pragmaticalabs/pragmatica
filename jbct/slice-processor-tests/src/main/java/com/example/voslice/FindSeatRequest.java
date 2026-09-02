// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package com.example.voslice;

import org.pragmatica.lang.Option;


/// Query-parameter request record: an optional `seat` query segment binds as `Option<SeatId>` — the
/// value object is lifted from the query primitive, and a missing parameter stays `Option.none()`.
public record FindSeatRequest(Option<SeatId> seat) {}
