// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.resource;

/// Configuration for [RecordedResource]. The SPI provider only needs a type it can construct a
/// config binding for; no field is read.
public record RecordedResourceConfig(String name) {}
