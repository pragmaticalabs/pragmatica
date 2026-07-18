// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Result.success;


/// A fully-resolved, provider-agnostic create request (RFC-0016 §2). Every provisioning
/// dispatch funnels through the static [#resolve] at the [ComputeProvider#provision(ProvisionSpec)]
/// boundary — the single choke all three producer sites reach (bootstrap seed,
/// `CloudProviderSupport`, and the CTM auto-heal path). resolve() applies a uniform per-field
/// precedence (`spec` field → provider config default → loud fallback) so a provider's
/// [ComputeProvider#createFrom] receives a TOTAL request and performs no re-derivation: every
/// resolved-primary field is concrete, never an [Option] a provider might silently drop.
///
/// Field semantics:
///  - [#market] — ON_DEMAND vs SPOT, derived from the context role (`spot` → SPOT).
///  - [#instanceSize] — effective server type / machine type / VM size. Never blank: when
///    neither the spec nor the provider config resolves one, resolve() fails loud (#442).
///  - [#image] — effective boot image / AMI / snapshot. `""` only for a single-image provider.
///  - [#zone] — effective zone/region; `""` requests the provider default placement.
///  - [#userData] — effective cloud-init user-data.
///  - [#marketOptions] — spot placement parameters (price cap, interruption behavior); only
///    the AWS spot arm consumes them today (RFC-0016 §2.5-ii).
///  - [#context] — the unchanged provisioning intent (role/cluster/source/nodeId/peers/tags).
public record ProvisionRequest(InstanceType market,
                               String instanceSize,
                               String image,
                               String zone,
                               Option<String> userData,
                               MarketOptions marketOptions,
                               ProvisionContext context) {
    private static final Logger log = LoggerFactory.getLogger(ProvisionRequest.class);
    /// Sentinel the producers pass in [ProvisionSpec#instanceSize] when they carry no concrete
    /// type (CTM auto-heal always; bootstrap for roles without an `instance_type`). Treated as
    /// "unset" so resolution falls through to the provider config default.
    public static final String DEFAULT_INSTANCE_SIZE_SENTINEL = "default";
    /// Context role that maps to a SPOT market. Compared as a literal because
    /// `environment-integration` is a leaf module and cannot depend on `aether-config`'s
    /// `NodeRole` (whose `SPOT.value()` is this string).
    private static final String SPOT_ROLE = "spot";

    private static final String NO_INSTANCE_SIZE_MESSAGE = "No instance size resolved: neither the provision spec instance size nor the provider config default is set. "
                                                         + "Set instance_type on the source's role (bootstrap) or the provider's instance-type config so auto-heal "
                                                         + "replacements inherit the cluster's type.";

    /// Resolve a producer-built [ProvisionSpec] into a total create request using
    /// [ProviderDefaults] for the config-level fallbacks. Fails loud (#442) when no instance
    /// size resolves; every other field falls back silently to the provider default, with a
    /// LOUD warning only for the stock-image fallback (#459).
    public static Result<ProvisionRequest> resolve(ProvisionSpec spec, ProviderDefaults defaults) {
        return resolveInstanceSize(spec.instanceSize(), defaults.instanceSize()).map(instanceSize -> build(spec,
                                                                                                           defaults,
                                                                                                           instanceSize));
    }

    private static ProvisionRequest build(ProvisionSpec spec, ProviderDefaults defaults, String instanceSize) {
        var role = spec.context().role();

        return new ProvisionRequest(market(role),
                                    instanceSize,
                                    resolveImage(spec.imageId(), defaults),
                                    resolveZone(spec.placement(), defaults.zone()),
                                    spec.userData().orElse(defaults.userData()),
                                    marketOptions(role),
                                    spec.context());
    }

    private static Result<String> resolveInstanceSize(String specSize, String configSize) {
        if (isConcreteSize(specSize)) {
            return success(specSize);
        }

        return isConcreteSize(configSize)
               ? success(configSize)
               : EnvironmentError.provisionFailed(new RuntimeException(NO_INSTANCE_SIZE_MESSAGE)).result();
    }

    private static boolean isConcreteSize(String value) {
        return Verify.Is.present(value) && !DEFAULT_INSTANCE_SIZE_SENTINEL.equals(value);
    }

    /// Image precedence: spec image (per-role, populated by W2) → provider config image → loud
    /// provider stock default. A single-image provider ([ProviderDefaults#supportsImage] false)
    /// resolves to `""` with no warning.
    private static String resolveImage(Option<String> specImage, ProviderDefaults defaults) {
        return specImage.filter(ProvisionRequest::isConcreteImage)
                        .or(() -> imageFromDefaults(defaults));
    }

    private static String imageFromDefaults(ProviderDefaults defaults) {
        if (isConcreteImage(defaults.image())) {
            return defaults.image();
        }

        return defaults.supportsImage()
               ? imageFallback(defaults.fallbackImage())
               : "";
    }

    private static boolean isConcreteImage(String value) {
        return Verify.Is.present(value);
    }

    private static String imageFallback(String fallbackImage) {
        log.warn("Provision resolve: no image resolved from the spec or provider config; using the provider stock "
                + "default '{}'. Set an image on the source's role (or the provider image config) to boot from a "
                + "prepared snapshot.",
                 fallbackImage);

        return fallbackImage;
    }

    private static String resolveZone(Option<PlacementHint> placement, String defaultZone) {
        return placement.flatMap(ProvisionRequest::zoneFromHint)
                        .or(defaultZone);
    }

    private static Option<String> zoneFromHint(PlacementHint hint) {
        return switch (hint) {
            case PlacementHint.ZoneHint zone -> Option.some(zone.zoneName());
            case PlacementHint.HostGroupHint ignored -> unsupportedHint("HostGroupHint");
            case PlacementHint.AffinityHint ignored -> unsupportedHint("AffinityHint");
            case PlacementHint.AntiAffinityHint ignored -> unsupportedHint("AntiAffinityHint");
        };
    }

    private static Option<String> unsupportedHint(String hintType) {
        log.debug("Provision resolve: ignoring {} placement hint — only ZoneHint is honored", hintType);

        return Option.empty();
    }

    private static InstanceType market(String role) {
        return isSpotRole(role)
               ? InstanceType.SPOT
               : InstanceType.ON_DEMAND;
    }

    private static MarketOptions marketOptions(String role) {
        return isSpotRole(role)
               ? MarketOptions.spot()
               : MarketOptions.ON_DEMAND;
    }

    private static boolean isSpotRole(String role) {
        return SPOT_ROLE.equals(role);
    }
}
