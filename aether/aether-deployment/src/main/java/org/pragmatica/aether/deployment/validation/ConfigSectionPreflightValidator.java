// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.validation;

import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.topology.SliceTopology;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.ProviderBasedConfigService;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

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
/// **Where a section may come from (#1067).** Each slice's sections are evaluated over the layering the
/// slice loader applies to THAT slice, built by the loader's own functions rather than a copy of them:
/// the node composite (KV operator overlay layered over `node.toml`) on top, and beneath it the slice
/// jar's own `META-INF/resources.toml` ([SliceStore#sliceIntrinsicLayer], [SliceStore#layerSliceComposite]).
/// A deploy is refused iff some declared section is absent from every layer the loader would consult for
/// the slice declaring it. The view is per slice — a section shipped in one slice's jar never satisfies
/// another slice, just as at load, where each slice's composite closes over its own jar. A jar whose
/// `resources.toml` does not parse contributes no layer: the loader then attaches no slice composite and
/// provisioning falls back to the node-wide `ConfigService`, which wraps the node composite alone, so the
/// node composite alone is the view checked. Before #1067 the jar layer was ignored here, and a section
/// shipped only in the jar was refused although the runtime resolves it.
///
/// Honest limitations:
/// - The node composite is the *leader's* (KV overlay over the leader's own `node.toml`), not a
///   cross-node homogeneity check — a section present on the leader but missing from a follower's
///   `node.toml` is not caught here `[design intent — unverified]`.
/// - `${secrets:...}` placeholders in the jar layer are not resolved here. At load, one secret that fails
///   to resolve drops that node's whole jar layer, so a section present only in a jar whose secrets fail
///   passes this check and fails at provisioning `[mechanism: SliceStore.sliceStore.resolveIntrinsicSecrets]`.
/// - When no [ConfigurationProvider] is configured at all (`nodeComposite` is empty), the check fails
///   OPEN — it cannot tell presence from absence, so it must not manufacture false positives.
public interface ConfigSectionPreflightValidator {
    /// One resolved slice jar as the pre-flight sees it: the topologies its manifests declare, and the text
    /// of the jar's own `META-INF/resources.toml` when it ships one.
    record SliceJar(Artifact artifact, List<SliceTopology> topologies, Option<String> resourcesToml) {
        public static SliceJar sliceJar(Artifact artifact,
                                        List<SliceTopology> topologies,
                                        Option<String> resourcesToml) {
            return new SliceJar(artifact, topologies, resourcesToml);
        }
    }

    /// Validates every resource dependency across all given slice jars, aggregating every missing
    /// section into a single failure (acceptance criterion: a complete list, not stop-at-first).
    static Result<Unit> validate(List<SliceJar> sliceJars, Option<ConfigurationProvider> nodeComposite) {
        var checks = sliceJars.stream().flatMap(sliceJar -> checkSliceJar(sliceJar, nodeComposite)).toList();

        return Result.allOf(checks).mapToUnit();
    }

    private static Stream<Result<Unit>> checkSliceJar(SliceJar sliceJar, Option<ConfigurationProvider> nodeComposite) {
        var loaderView = nodeComposite.map(composite -> loaderView(sliceJar, composite));

        return sliceJar.topologies()
                       .stream()
                       .flatMap(topology -> checkTopology(topology, loaderView));
    }

    private static Stream<Result<Unit>> checkTopology(SliceTopology topology,
                                                      Option<ConfigurationProvider> loaderView) {
        return topology.resources()
                       .stream()
                       .map(resource -> checkSection(topology.sliceName(),
                                                     resource,
                                                     loaderView));
    }

    /// The layers the loader would consult for this slice's resource sections. The `.or(nodeComposite)` is
    /// not an absorbed failure: an unparseable jar `resources.toml` yields no layer at load too, and the
    /// runtime then answers from the node composite alone (see the interface header).
    private static ConfigurationProvider loaderView(SliceJar sliceJar, ConfigurationProvider nodeComposite) {
        return SliceStore.sliceIntrinsicLayer(sliceJar.artifact(),
                                              sliceJar.resourcesToml())
                         .map(intrinsic -> SliceStore.layerSliceComposite(intrinsic, nodeComposite))
                         .or(nodeComposite);
    }

    private static Result<Unit> checkSection(String sliceName,
                                             SliceTopology.ResourceDep resource,
                                             Option<ConfigurationProvider> loaderView) {
        return hasSection(loaderView, resource.config())
               ? Result.unitResult()
               : missingConfigSection(sliceName, resource.type(), resource.config()).result();
    }

    private static boolean hasSection(Option<ConfigurationProvider> loaderView, String section) {
        return loaderView.map(provider -> ProviderBasedConfigService.providerBasedConfigService(provider).hasSection(section))
                         .or(true);
    }
}
