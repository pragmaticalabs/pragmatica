// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.test.durabletopic;

/// An application-defined topic payload (#386).
///
/// Deliberately not `String`: the durable publish path encodes through the deployed slice's codec,
/// so a payload type only the slice knows proves the codec actually travelled with the resource
/// rather than the node-wide one being substituted. `sequence` is the ordering probe — the fixture
/// publishes ascending sequences and the test asserts they arrive in that order, which on a
/// single-partition topic is exactly the serial per-(group x partition) dispatch claim.
public record OrderPlaced(String orderId, int sequence) {}
