// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.validation;

import org.pragmatica.aether.slice.topology.SliceTopology;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.ProviderBasedConfigService;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.List;

import static org.pragmatica.aether.deployment.validation.MissingConfigSection.missingConfigSection;

/// Deploy-time pre-flight for generic resource dependencies (#547): every [SliceTopology.ResourceDep]
/// declared by a slice being deployed must resolve to a config section that actually exists in the
/// target cluster, checked ONCE up front across the whole blueprint instead of failing later, one
/// node at a time, inside [org.pragmatica.aether.resource.SpiResourceProvider.loadConfig].
///
/// Scope is deliberately narrow: only [SliceTopology#resources()] (database/cache/HTTP/idempotency-
/// style resources) is checked. `publishes()`/`subscribes()` (stream/pub-sub resources) are excluded —
/// that subsystem already has its own validation stage ([StreamResourceValidator]) with different,
/// deliberately non-gating semantics, and folding it into this hard-fail check would be scope creep
/// beyond #547's acceptance criteria.
///
/// Honest limitation: this checks presence and shape against the *leader's* composite configuration
/// view (KV operator overlay layered over the leader's own `node.toml`), not environmental correctness
/// and not cross-node `node.toml` homogeneity — a section present on the leader but missing on a
/// follower's `node.toml` is not caught here `[design intent — unverified]`. When no
/// [ConfigurationProvider] is configured at all (`nodeComposite` is empty), the check fails OPEN —
/// it cannot tell presence from absence, so it must not manufacture false positives.
public interface ConfigSectionPreflightValidator {
    /// Validates every resource dependency across all given topologies, aggregating every missing
    /// section into a single failure (acceptance criterion: a complete list, not stop-at-first).
    static Result<Unit> validate(List<SliceTopology> topologies, Option<ConfigurationProvider> nodeComposite) {
        var checks = topologies.stream()
                                .flatMap(topology -> topology.resources()
                                                              .stream()
                                                              .map(resource -> checkSection(topology.sliceName(), resource, nodeComposite)))
                                .toList();

        return Result.allOf(checks).mapToUnit();
    }

    private static Result<Unit> checkSection(String sliceName, SliceTopology.ResourceDep resource, Option<ConfigurationProvider> nodeComposite) {
        return hasSection(nodeComposite, resource.config())
               ? Result.unitResult()
               : missingConfigSection(sliceName, resource.type(), resource.config()).result();
    }

    private static boolean hasSection(Option<ConfigurationProvider> nodeComposite, String section) {
        return nodeComposite.map(provider -> ProviderBasedConfigService.providerBasedConfigService(provider).hasSection(section))
                             .or(true);
    }
}
