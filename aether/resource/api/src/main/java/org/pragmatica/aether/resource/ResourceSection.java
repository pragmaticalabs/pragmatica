// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource;

/// The configuration section a resource is being provisioned for (#280 R27), carried as a `ProvisioningContext`
/// extension so a factory can tell two sections of one resource type in one slice apart: the provider shares one
/// instance per `(type, section)`, so the section name is the instance's identity.
///
/// [SpiResourceProvider] adds it only on the PER-SLICE paths (the cached path and the per-slice pool path). The
/// node-shared path (#2973) never carries it: a [NodeShareable] factory's resource belongs to no section and no slice,
/// so a factory must not be able to learn either from that path.
///
/// @param name the section name exactly as the slice declared it
public record ResourceSection(String name) {
    public static ResourceSection resourceSection(String name) {
        return new ResourceSection(name);
    }
}
