// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.launcher;

import java.util.List;
import org.pragmatica.lang.Promise;
import org.pragmatica.terra.TerraContext;
import org.pragmatica.terra.TerraFactory;

/// A slice that starts and serves no route, for launches that must reach the HTTP host.
public final class OkSliceFixture implements TerraFactory<OkSliceFixture.Slice> {
    public record Slice() {}

    public String artifact() { return "test:ok:1"; }
    public Class<Slice> sliceType() { return Slice.class; }
    public List<Class<?>> dependencies() { return List.of(); }
    public Promise<Slice> create(TerraContext context) { return Promise.success(new Slice()); }
}
