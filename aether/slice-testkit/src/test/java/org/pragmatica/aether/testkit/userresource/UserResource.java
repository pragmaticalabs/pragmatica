// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.testkit.userresource;

/// A resource TYPE owned by a user's slice rather than by the platform (#773).
///
/// The compiled class of this record is packaged into a throw-away slice jar by
/// [SliceScopedResourceFactoryTest] and re-defined by a `SliceClassLoader`, so the `Class` the
/// slice's generated factory would pass to `provide(...)` is NOT the `Class` any node-boot registry
/// could ever hold.
public record UserResource(String config) {}
