// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.testkit.fake;

import java.util.List;


/// A SQL statement executed against an in-memory connector fake, captured for assertions
/// (spec §3.3 "records executed statements").
public record RecordedStatement(String sql, List<Object> params) {}
