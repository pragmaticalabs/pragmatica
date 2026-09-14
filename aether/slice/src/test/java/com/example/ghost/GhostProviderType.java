// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package com.example.ghost;

/// #758 fixture: an APPLICATION type another slice provides (the real case: `SeatSellability` from
/// `ticketing-seat-sellability`). Outside the runtime namespace on purpose — a slice cannot lose it
/// to a runtime upgrade; it can only be missing from the consumer's classloader.
public class GhostProviderType {}
