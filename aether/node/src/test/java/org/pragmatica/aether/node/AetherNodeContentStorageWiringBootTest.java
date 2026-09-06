// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.node.ProvisioningContextCaptureFactory.CapturedProvisioningContext;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Option;
import org.pragmatica.storage.StorageInstance;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #783 review round 2, BLOCKING 2: the load-bearing `AetherNode.assembleNode` hunk is the one that
/// hands `registerRuntimeExtensions` the `content` setup's `StorageInstance` -- the instance the
/// `StorageMaintenanceDriver` ticks and the keyring wraps -- as the `StorageInstance` extension
/// `ContentStoreFactory.provision` reads. Every other #783 test stops at `StorageFactory.createAll`;
/// the reviewer's probe F re-wired the extension to the `artifacts` instance and all 1166 node tests
/// stayed green, which is #783 re-opened with a green suite as evidence it is closed.
///
/// This pins the wiring THROUGH THE NODE: a real boot (`AetherNode.aetherNode`), then a resource
/// provisioned through the process-wide `SpiResourceProvider` the node installed, whose factory
/// ([ProvisioningContextCaptureFactory], test-only, `ServiceLoader`-discovered) returns the context
/// the SPI enriched with the node's runtime extensions. The `StorageInstance` found there must be the
/// SAME OBJECT as `node.storageSetups().get("content").instance()`, and not `artifacts`'.
///
/// Red-before (probe F): passing `artifactStorage` instead of `contentStorage` at the
/// `registerRuntimeExtensions` call site in `assembleNode` turns `isSameAs` red (the extension is
/// then `artifacts`' instance). Registering a bare instance built outside `storageSetups` -- the
/// pre-#783 shape -- turns it red the same way.
///
/// Boot fixture shared with [AetherNodeContentStorageWarnBootTest]: same config shape (see the
/// rationale on `minimalConfig` there), plus a `[context_capture]` section so the SPI's config
/// loader finds the capture factory's section -- `SpiResourceProvider.loadConfig` runs BEFORE the
/// factory is invoked and fails the provision on a missing section.
class AetherNodeContentStorageWiringBootTest {
    private static final String CAPTURE_SECTION = "context_capture";
    private static final String ARTIFACTS = "artifacts";
    private static final String CONTENT = "content";

    private AetherNode node;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop()
                .await(timeSpan(10).seconds())
                .onFailure(cause -> {});
        }

        // Process-wide singletons set by AetherNode.createResourceProviderFacade -- cleared so this
        // fixture never bleeds into another test class in the same surefire fork.
        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void assembleNode_registersContentSetupInstance_asTheSpiStorageInstanceExtension() {
        var configProvider = ConfigurationProvider.builder()
                                                  .withDefaults(Map.of(CAPTURE_SECTION + ".enabled", "true"))
                                                  .build();

        node = AetherNode.aetherNode(AetherNodeContentStorageWarnBootTest.minimalConfig(Option.none(),
                                                                                       Option.none(),
                                                                                       configProvider),
                                     () -> {})
                          .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                          .unwrap();

        var contentInstance = node.storageSetups().get(CONTENT).instance();
        var artifactsInstance = node.storageSetups().get(ARTIFACTS).instance();

        assertThat(contentInstance).as("fixture: the two setups must be distinct objects, or the identity "
                                       + "assertion below could not distinguish them")
                                   .isNotSameAs(artifactsInstance);

        var spi = ResourceProvider.instance()
                                  .fold(() -> fail("AetherNode.createResourceProviderFacade installs the SPI provider as "
                                                   + "the process-wide ResourceProvider whenever a configProvider is "
                                                   + "present -- this config supplies one"),
                                        provider -> provider);

        var captured = spi.provide(CapturedProvisioningContext.class, CAPTURE_SECTION, ProvisioningContext.provisioningContext())
                          .await()
                          .onFailure(cause -> fail("provisioning through the node's SPI must succeed: " + cause.message()))
                          .unwrap();

        var extension = captured.context()
                                .extension(StorageInstance.class)
                                .onFailure(cause -> fail("registerRuntimeExtensions must register a StorageInstance "
                                                         + "extension (#251) -- " + cause.message()))
                                .unwrap();

        assertThat(extension).as("#783: the StorageInstance every ContentStore provisions through must be the "
                                 + "`content` setup's own instance -- the one StorageMaintenanceDriver ticks and "
                                 + "the keyring wraps -- not `artifacts`' and not a bare instance built outside "
                                 + "storageSetups")
                             .isSameAs(contentInstance);
        assertThat(extension.name()).as("secondary, name-based check of the same property")
                                    .isEqualTo(CONTENT);
    }
}
