// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// Unit coverage for the single, non-overridable [ProvisionRequest#resolve] choke (RFC-0016 §2).
/// Verifies the per-field precedence (spec > provider config > loud fallback), the
/// `spot` role → SPOT market mapping, and that a spec built by any of the three producer shapes
/// (bootstrap seed, CTM auto-heal replacement, `CloudProviderSupport`) resolves correctly — the
/// CTM heal path in particular, the site the pre-boundary design silently downgraded.
class ProvisionRequestTest {
    @Nested
    class InstanceSizeResolution {
        @Test
        void resolve_specInstanceSize_winsOverProviderDefault() {
            var request = ProvisionRequest.resolve(spec("ccx23", "core"), defaults()).unwrap();

            assertThat(request.instanceSize()).isEqualTo("ccx23");
        }

        @Test
        void resolve_specSentinel_fallsBackToProviderDefault() {
            var request = ProvisionRequest.resolve(spec("default", "core"), defaults()).unwrap();

            assertThat(request.instanceSize()).isEqualTo("cx22");
        }

        @Test
        void resolve_specBlank_fallsBackToProviderDefault() {
            var request = ProvisionRequest.resolve(spec("", "core"), defaults()).unwrap();

            assertThat(request.instanceSize()).isEqualTo("cx22");
        }

        @Test
        void resolve_neitherSpecNorProviderInstanceSize_failsLoud() {
            ProvisionRequest.resolve(spec("default", "core"),
                                     sizelessDefaults())
                            .onSuccess(request -> assertThat(request).isNull())
                            .onFailure(ProvisionRequestTest::assertProvisionFailed);
        }
    }

    @Nested
    class ImageResolution {
        @Test
        void resolve_specImage_winsOverProviderConfigImage() {
            var request = ProvisionRequest.resolve(spec("cx22", "core").withImage("snapshot-42"), defaults()).unwrap();

            assertThat(request.image()).isEqualTo("snapshot-42");
        }

        @Test
        void resolve_providerConfigImage_usedWhenSpecAbsent() {
            var request = ProvisionRequest.resolve(spec("cx22", "core"), defaults()).unwrap();

            assertThat(request.image()).isEqualTo("ubuntu-24.04");
        }

        @Test
        void resolve_neitherImage_supportsImage_usesLoudStockFallback() {
            var request = ProvisionRequest.resolve(spec("cx22", "core"), imagelessDefaults(true)).unwrap();

            assertThat(request.image()).isEqualTo("ubuntu-22.04");
        }

        @Test
        void resolve_neitherImage_singleImageProvider_resolvesEmptyWithoutWarning() {
            var request = ProvisionRequest.resolve(spec("cx22", "core"), imagelessDefaults(false)).unwrap();

            assertThat(request.image()).isEmpty();
        }
    }

    @Nested
    class ZoneResolution {
        @Test
        void resolve_zonePlacementHint_usedAsZone() {
            var request = ProvisionRequest.resolve(spec("cx22", "core").withPlacement(PlacementHint.zoneHint("nbg1")),
                                                   defaults())
                                          .unwrap();

            assertThat(request.zone()).isEqualTo("nbg1");
        }

        @Test
        void resolve_noPlacement_fallsBackToProviderZone() {
            var request = ProvisionRequest.resolve(spec("cx22", "core"), defaults()).unwrap();

            assertThat(request.zone()).isEqualTo("fsn1");
        }

        @Test
        void resolve_unsupportedPlacementHint_fallsBackToProviderZone() {
            var request = ProvisionRequest.resolve(spec("cx22", "core").withPlacement(PlacementHint.hostGroupHint("g1")),
                                                   defaults())
                                          .unwrap();

            assertThat(request.zone()).isEqualTo("fsn1");
        }
    }

    @Nested
    class UserDataResolution {
        @Test
        void resolve_specUserData_winsOverProviderDefault() {
            var request = ProvisionRequest.resolve(spec("cx22", "core").withUserData("#spec-init"),
                                                   defaults())
                                          .unwrap();

            assertThat(request.userData()).isEqualTo(Option.some("#spec-init"));
        }

        @Test
        void resolve_providerUserData_usedWhenSpecAbsent() {
            var request = ProvisionRequest.resolve(spec("cx22", "core"), defaults()).unwrap();

            assertThat(request.userData()).isEqualTo(Option.some("#config-init"));
        }

        @Test
        void resolve_neitherSpecNorProviderUserData_resolvesEmpty() {
            var request = ProvisionRequest.resolve(spec("cx22", "core"), userDatalessDefaults()).unwrap();

            assertThat(request.userData()).isEqualTo(Option.empty());
        }
    }

    @Nested
    class MarketResolution {
        @Test
        void resolve_spotRole_mapsToSpotMarketAndOptions() {
            var request = ProvisionRequest.resolve(spec("cx22", "spot"), defaults()).unwrap();

            assertThat(request.market()).isEqualTo(InstanceType.SPOT);
            assertThat(request.marketOptions()).isInstanceOf(MarketOptions.Spot.class);
        }

        @Test
        void resolve_spotRole_spotOptionsCarryDefaultParams() {
            var request = ProvisionRequest.resolve(spec("cx22", "spot"), defaults()).unwrap();

            assertThat(request.marketOptions()).isInstanceOfSatisfying(MarketOptions.Spot.class,
                                                                       ProvisionRequestTest::assertDefaultSpotParams);
        }

        @Test
        void resolve_coreRole_mapsToOnDemand() {
            var request = ProvisionRequest.resolve(spec("cx22", "core"), defaults()).unwrap();

            assertThat(request.market()).isEqualTo(InstanceType.ON_DEMAND);
            assertThat(request.marketOptions()).isInstanceOf(MarketOptions.OnDemand.class);
        }

        @Test
        void resolve_workerRole_mapsToOnDemand() {
            var request = ProvisionRequest.resolve(spec("cx22", "worker"), defaults()).unwrap();

            assertThat(request.market()).isEqualTo(InstanceType.ON_DEMAND);
        }
    }

    @Nested
    class ProducerPathsReachResolve {
        @Test
        void resolve_bootstrapSeedContext_producesOnDemandRequest() {
            var context = ProvisionContext.forBootstrap("prod", "core", "eu-1", "eu-1-core-0");
            var spec = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "cx22", "core", context).unwrap();
            var request = ProvisionRequest.resolve(spec, defaults()).unwrap();

            assertThat(request.market()).isEqualTo(InstanceType.ON_DEMAND);
            assertThat(request.instanceSize()).isEqualTo("cx22");
        }

        @Test
        void resolve_ctmReplacementSpotRole_mapsToSpotDespiteOnDemandLiteral() {
            // The CTM auto-heal producer hardcodes InstanceType.ON_DEMAND and pool "core"
            // (ClusterTopologyManagerRecord:528); resolve() derives the market from the context ROLE,
            // so a spot replacement maps to SPOT — the downgrade the pre-boundary design missed.
            var context = ProvisionContext.forReplacement("prod", "spot", "node-abc", "peers", 5);
            var spec = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "default", "core", context).unwrap();
            var request = ProvisionRequest.resolve(spec, defaults()).unwrap();

            assertThat(request.market()).isEqualTo(InstanceType.SPOT);
            assertThat(request.marketOptions()).isInstanceOf(MarketOptions.Spot.class);
        }

        @Test
        void resolve_cloudSupportContext_producesResolvedRequest() {
            var context = ProvisionContext.provisionContext("prod",
                                                            "core",
                                                            "eu-1",
                                                            ProvisionContext.PROVISIONED_BY_BOOTSTRAP);
            var spec = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "cx32", "core", context).unwrap();
            var request = ProvisionRequest.resolve(spec, defaults()).unwrap();

            assertThat(request.instanceSize()).isEqualTo("cx32");
            assertThat(request.context().sourceName()).isEqualTo("eu-1");
        }
    }

    private static ProvisionSpec spec(String instanceSize, String role) {
        var context = ProvisionContext.provisionContext("cluster-x",
                                                        role,
                                                        "eu-1",
                                                        ProvisionContext.PROVISIONED_BY_BOOTSTRAP);

        return ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, instanceSize, role, context).unwrap();
    }

    private static ProviderDefaults defaults() {
        return ProviderDefaults.providerDefaults("cx22",
                                                 "ubuntu-24.04",
                                                 "ubuntu-22.04",
                                                 "fsn1",
                                                 Option.some("#config-init"),
                                                 true);
    }

    private static ProviderDefaults sizelessDefaults() {
        return ProviderDefaults.providerDefaults("",
                                                 "ubuntu-24.04",
                                                 "ubuntu-22.04",
                                                 "fsn1",
                                                 Option.some("#config-init"),
                                                 true);
    }

    private static ProviderDefaults imagelessDefaults(boolean supportsImage) {
        return ProviderDefaults.providerDefaults("cx22",
                                                 "",
                                                 "ubuntu-22.04",
                                                 "fsn1",
                                                 Option.some("#config-init"),
                                                 supportsImage);
    }

    private static ProviderDefaults userDatalessDefaults() {
        return ProviderDefaults.providerDefaults("cx22",
                                                 "ubuntu-24.04",
                                                 "ubuntu-22.04",
                                                 "fsn1",
                                                 Option.empty(),
                                                 true);
    }

    private static void assertDefaultSpotParams(MarketOptions.Spot spot) {
        assertThat(spot.maxPrice()).isEqualTo(Option.empty());
        assertThat(spot.interruptionBehavior()).isEqualTo(MarketOptions.InterruptionBehavior.TERMINATE);
    }

    private static void assertProvisionFailed(Cause cause) {
        assertThat(cause).isInstanceOf(EnvironmentError.ProvisionFailed.class);
    }
}
