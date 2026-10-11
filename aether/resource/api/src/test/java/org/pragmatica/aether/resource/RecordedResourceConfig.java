// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.resource;

/// Configuration for [RecordedResource]. The SPI provider only needs a type it can construct a
/// config binding for; no field is read.
public record RecordedResourceConfig(String name) {}
