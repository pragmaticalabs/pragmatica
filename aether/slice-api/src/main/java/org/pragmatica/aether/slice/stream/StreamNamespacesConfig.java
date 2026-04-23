// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;


/// Feature flag configuration for the three-component stream-addressing scheme (#165).
///
/// Default: **disabled**. When enabled, the node runs the system-stream bootstrap at startup and
/// exposes the `/api/streams/{namespace}/{stream}[/{version}]` read-only route. When disabled,
/// the existing flat-stream-name runtime is unchanged.
///
/// The flag-on path is intended to mature during RC1 validation without disturbing the default
/// ring-buffer event pipeline. Flipping the default is a separate follow-up (see #165 risk
/// register in the tracking issue).
public record StreamNamespacesConfig(boolean enabled) {
    public static final StreamNamespacesConfig DISABLED = new StreamNamespacesConfig(false);

    public static final StreamNamespacesConfig ENABLED = new StreamNamespacesConfig(true);

    public static StreamNamespacesConfig defaultConfig() {
        return DISABLED;
    }
}
