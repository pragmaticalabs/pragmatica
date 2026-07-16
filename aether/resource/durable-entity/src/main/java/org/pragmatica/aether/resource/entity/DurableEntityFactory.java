// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.lang.Promise;


/// [ResourceFactory] for the [DurableEntity] primitive, discovered via the resource SPI
/// (`META-INF/services/org.pragmatica.aether.resource.ResourceFactory`). Registering a
/// durable-entity resource is therefore pure SPI — no slice-processor, envelope, or framework edits.
///
/// Provisioning is synchronous and IO-free: the HA-only in-memory entity holds only in-process
/// state (two maps), so the returned [Promise] is already resolved.
@SuppressWarnings("rawtypes")
public final class DurableEntityFactory implements ResourceFactory<DurableEntity, DurableEntityConfig> {
    @Override
    public Class<DurableEntity> resourceType() {
        return DurableEntity.class;
    }

    @Override
    public Class<DurableEntityConfig> configType() {
        return DurableEntityConfig.class;
    }

    @Override
    public Promise<DurableEntity> provision(DurableEntityConfig config) {
        return Promise.success(InMemoryDurableEntity.inMemoryDurableEntity());
    }
}
