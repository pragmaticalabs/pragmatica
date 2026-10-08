// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.terra.launcher;

import java.util.List;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.terra.TerraContext;
import org.pragmatica.terra.TerraFactory;

public final class ConfigFactoryFixture implements TerraFactory<String> {
    public String artifact() { return "test:config:1"; }
    public Class<String> sliceType() { return String.class; }
    public List<Class<?>> dependencies() { return List.of(); }
    public Promise<String> create(TerraContext context) { return Causes.cause("Check mode must not create this slice").promise(); }
}
