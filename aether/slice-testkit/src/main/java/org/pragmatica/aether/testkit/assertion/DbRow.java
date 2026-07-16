// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.testkit.assertion;

/// Typed, read-only view of one DB row, used to assert on real rows returned from the container
/// path (spec §3.2 `db(section)`). Backed live by the driver's row accessor during mapping.
public interface DbRow {
    String string(String column);
    long integer(String column);
    double number(String column);
    boolean bool(String column);
    byte[] bytes(String column);
}
