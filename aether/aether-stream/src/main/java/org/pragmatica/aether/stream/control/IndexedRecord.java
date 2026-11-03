// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.control;

/// A key's latest committed record and the log offset it sits at (#2590 P1). For a key whose latest record is a CARRY,
/// `record` is the restated record and `offset` the CARRY's own offset: the index serves what the key holds, and the
/// retention floor follows where it now sits.
public record IndexedRecord(long offset, ControlRecord record) {}
