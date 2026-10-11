// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
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
