// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.launcher;

import java.util.List;
import org.pragmatica.lang.Promise;
import org.pragmatica.terra.TerraContext;
import org.pragmatica.terra.TerraFactory;

/// A slice that starts: lets a launcher test reach a running application (#3650 metric sink settings).
public final class StartingFactoryFixture implements TerraFactory<Integer> {
    public String artifact() { return "test:starting:1"; }
    public Class<Integer> sliceType() { return Integer.class; }
    public List<Class<?>> dependencies() { return List.of(); }
    public Promise<Integer> create(TerraContext context) { return Promise.success(1); }
}
