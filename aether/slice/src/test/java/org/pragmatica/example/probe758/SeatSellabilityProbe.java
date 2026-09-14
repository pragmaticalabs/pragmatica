// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.example.probe758;

/// #758 fixture: the ticket's own incident type — an APPLICATION class scaffolded under the vendor
/// namespace (the real one: `org.pragmatica.example.ticketing.…seatsellability.SeatSellability`).
/// No name prefix can tell it from a runtime class; only the loader that would serve its package can.
/// Never load it through the application loader in a test (no class literal): that would define
/// its package there and turn the fixture into a runtime class.
public class SeatSellabilityProbe {}
