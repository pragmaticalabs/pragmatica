// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Option;


/// Provider-supplied fallbacks consumed by [ProvisionRequest#resolve] when a spec field is
/// absent. It carries the second (provider-config) and third (loud stock) precedence tiers so
/// resolution stays a single provider-agnostic function while the provider-specific defaults
/// live with the provider.
///
/// Field semantics:
///  - [#instanceSize] — provider config instance size (`server_type` / `machineType` /
///    `vmSize`); `""` when unset (then instance-size resolution fails loud, per #442).
///  - [#image] — provider config image (`image` / `amiId` / `sourceImage`); `""` when unset.
///  - [#fallbackImage] — provider stock boot image used for the LOUD final image fallback
///    (#459); `""` for single-image providers (see [#supportsImage]).
///  - [#zone] — provider config default zone/region; `""` for provider default placement.
///  - [#userData] — provider config default user-data.
///  - [#supportsImage] — `false` for a single-image provider (Docker): resolution yields no
///    image and emits no loud warning.
public record ProviderDefaults(String instanceSize,
                               String image,
                               String fallbackImage,
                               String zone,
                               Option<String> userData,
                               boolean supportsImage) {
    public static ProviderDefaults providerDefaults(String instanceSize,
                                                    String image,
                                                    String fallbackImage,
                                                    String zone,
                                                    Option<String> userData,
                                                    boolean supportsImage) {
        return new ProviderDefaults(instanceSize, image, fallbackImage, zone, userData, supportsImage);
    }

    /// Inert defaults for a provider that has not migrated to the [ProvisionRequest] contract.
    /// Never reached in practice: an unported provider takes the legacy dispatch branch in
    /// [ComputeProvider#provision(ProvisionSpec)] and so never calls [ProvisionRequest#resolve].
    public static ProviderDefaults none() {
        return new ProviderDefaults("", "", "", "", Option.empty(), false);
    }
}
