// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
// Fixture for ControlLogFloorTripwireTest: one construction in code, one in a comment, one declaration-shaped line.
class FactoryFixture {
    // ControlLog.controlLog(partitionLog, admissions, mirror) in a comment is not a construction
    static ControlLog controlLog(int declaration) { return null; }

    Object build() {
        return ControlLog.controlLog(partitionLog, admissions, mirror);
    }
}
