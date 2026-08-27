// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.validation;

import org.pragmatica.lang.Cause;

/// A slice declares a generic resource dependency ([org.pragmatica.aether.slice.topology.SliceTopology.ResourceDep])
/// whose `resources.toml` section is not found in the leader's composite configuration view
/// (node.toml layered with the operator KV overlay), checked at deploy time. Raised by
/// [ConfigSectionPreflightValidator] so the gap surfaces as one aggregated failure instead of a
/// per-node [org.pragmatica.aether.resource.SpiResourceProvider] load failure after slices have
/// already started activating (#547). The message names the exact view checked so an operator
/// does not over-trust it as a cross-node homogeneity guarantee.
public record MissingConfigSection(String field, String rule, String message) implements Cause {
    public static final String RULE_SECTION_NOT_CONFIGURED = "config-section-not-configured";

    public static MissingConfigSection missingConfigSection(String sliceName, String resourceType, String section) {
        var field = "[" + section + "]";
        var message = "slice '%s' requires config section [%s] for its %s resource — not found in the leader's composite configuration view (node.toml layered with the operator KV overlay), checked at deploy time. This does not confirm the section is present on every follower's node.toml. Add the section before deploying, or remove the resource dependency from the slice."
                          .formatted(sliceName, section, resourceType);

        return new MissingConfigSection(field, RULE_SECTION_NOT_CONFIGURED, message);
    }
}
