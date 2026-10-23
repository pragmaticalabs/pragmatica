// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice;

/// Which JSON implementation a slice's HTTP routes encode and decode bodies with.
public enum JsonImplementation {
    /// The slice's generated codecs, with Jackson for every type they do not cover.
    GENERATED,
    /// Jackson for every type.
    JACKSON
}
