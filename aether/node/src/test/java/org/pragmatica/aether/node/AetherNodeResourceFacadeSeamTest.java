// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// VERIFICATION-ONLY (v892 adversarial pass): the pin the #892 report says does not exist.
///
/// The report's section 7 states the `AetherNode` hunk "is not independently pinned by a test",
/// because `createResourceProviderFacade` "is a private static method with no seam; there is no way
/// to construct its output without booting a node", and that a regression reverting it to a
/// hand-rolled partial facade "would compile, pass the whole suite, and reintroduce the defect".
///
/// That method is a PURE FUNCTION of `AetherNodeConfig`: it reads `configProvider()` and
/// `environment()`, builds the layered config, and returns a `ResourceProviderSetup`. It opens no
/// port, forms no cluster and boots no node. Reflection reaches it with no production change at all;
/// the module already carries a reflection precedent (`ScheduledTaskRoutesExecutionsByNodeTest`) and
/// a `ServiceLoader`-discovered test factory (`ProvisioningContextCaptureFactory`).
///
/// The assertion is on whether the RESOURCE was closed, never on whether the release promise
/// succeeded -- it succeeded before the fix too, which is exactly what made #892 invisible.
class AetherNodeResourceFacadeSeamTest {
    private static final String SCOPE = "org.example:probe-slice:1.0.0";

    @BeforeEach
    void setUp() {
        ReleaseProbeFactory.reset();
    }

    @AfterEach
    void tearDown() {
        // Process-wide singletons set by AetherNode.createResourceProviderFacade.
        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    void nodeFacade_releaseAll_forwardsToTheProvider_andClosesTheProvisionedResource() throws Exception {
        var configProvider = ConfigurationProvider.builder()
                                                  .withDefaults(Map.of(ReleaseProbeFactory.SECTION + ".enabled", "true"))
                                                  .build();
        var config = AetherNodeContentStorageWarnBootTest.minimalConfig(Option.none(), Option.none(), configProvider);

        var method = AetherNode.class.getDeclaredMethod("createResourceProviderFacade", AetherNodeConfig.class);

        method.setAccessible(true);

        var setup = (AetherNode.ResourceProviderSetup) method.invoke(null, config);

        assertThat(setup.spiProvider().isPresent()).as("arming: the config carries a configProvider, so the populated "
                                                       + "branch must be taken -- on the noOp branch this test would "
                                                       + "prove nothing")
                                                   .isTrue();

        var facade = setup.facade();
        var context = ProvisioningContext.provisioningContext()
                                         .withExtension(String.class, SCOPE);

        facade.provide(ReleaseProbeFactory.ProbeResource.class, ReleaseProbeFactory.SECTION, context)
              .await(timeSpan(10).seconds())
              .onFailure(cause -> fail("provisioning through the node's own facade must succeed: " + cause.message()));

        assertThat(ReleaseProbeFactory.provisioned()).as("fixture: exactly one resource provisioned through the facade")
                                                     .hasSize(1);
        assertThat(ReleaseProbeFactory.provisioned().getFirst().isClosed()).as("control: not closed before the release, "
                                                                              + "so a pass is attributable to releaseAll")
                                                                          .isFalse();

        facade.releaseAll(SCOPE)
              .await(timeSpan(10).seconds());

        assertThat(ReleaseProbeFactory.provisioned().getFirst().isClosed())
                .as("#892: the facade AetherNode hands to the slice-loading chain must FORWARD releaseAll to the "
                    + "provider. A hand-rolled partial facade inherits ResourceProviderFacade's no-op default, which "
                    + "reports success while closing nothing.")
                .isTrue();
    }
}
