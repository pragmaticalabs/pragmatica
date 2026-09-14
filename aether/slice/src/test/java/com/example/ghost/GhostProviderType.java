// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package com.example.ghost;

/// #758 fixture: an APPLICATION type another slice provides, outside the vendor namespace (the
/// in-namespace twin is `org.pragmatica.example.probe758.SeatSellabilityProbe`). A slice cannot lose
/// it to a runtime upgrade; it can only be missing from the consumer's loader chain. Never load it
/// through the application loader in a test (no class literal): that would define its package there
/// and turn the fixture into a runtime class.
public class GhostProviderType {}
