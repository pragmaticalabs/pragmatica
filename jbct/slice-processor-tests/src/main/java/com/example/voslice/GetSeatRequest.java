// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.voslice;

/// Path-parameter request record: the `seatId` path segment binds directly to the [SeatId] value
/// object (a required path segment is not optional).
public record GetSeatRequest(SeatId seatId) {}
