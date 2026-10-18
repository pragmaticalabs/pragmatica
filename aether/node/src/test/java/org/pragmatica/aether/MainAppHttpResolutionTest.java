// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.AetherConfig;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.ConfigLoader;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.net.tcp.security.CertificateProvider;
import org.pragmatica.lang.Option;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.mock;

/// #962: [Main#resolveAppHttp] replaces a disabled `[app-http]` whole with the fail-closed default (#573), and that
/// default must still carry the operator's `[management] security_mode`, which is not part of the app plane.
class MainAppHttpResolutionTest {
    private static final String MINIMAL_CLUSTER = """
        [cluster]
        environment = "docker"
        nodes = 3
        """;

    private static Option<AetherConfig> load(String toml) {
        return Option.some(ConfigLoader.loadFromString(MINIMAL_CLUSTER + toml)
                                       .onFailure(cause -> fail(cause.message()))
                                       .unwrap());
    }

    @Test
    void resolveAppHttp_keepsManagementNone_whenAppHttpIsDisabled() {
        var resolved = Main.resolveAppHttp(load("""

            [management]
            security_mode = "none"
            """));

        assertThat(resolved.securityEnabled()).as("the app plane falls back to the fail-closed default").isTrue();
        assertThat(resolved.managementSecurityEnabled()).as("the management plane keeps its own explicit none").isFalse();
    }

    @Test
    void resolveAppHttp_failsClosed_whenNeitherSectionIsPresent() {
        var resolved = Main.resolveAppHttp(load(""));

        assertThat(resolved.managementSecurityEnabled()).isTrue();
    }

    @Test
    void resolveAppHttp_failsClosed_whenNoConfigAtAll() {
        assertThat(Main.resolveAppHttp(Option.none()).managementSecurityEnabled()).isTrue();
    }

    @Test
    void resolveAppHttp_discardsAppNone_whenAppHttpIsDisabled_andManagementInherits() {
        var resolved = Main.resolveAppHttp(load("""

            [app-http]
            enabled = "false"
            security_mode = "none"
            """));

        assertThat(resolved.managementSecurityEnabled()).as("#573: a disabled [app-http] none is discarded whole").isTrue();
    }

    @Test
    void resolveAppHttp_keepsEnabledAppHttp_andItsManagementOverride() {
        var resolved = Main.resolveAppHttp(load("""

            [app-http]
            enabled = "true"
            security_mode = "api-key"

            [management]
            security_mode = "none"
            """));

        assertThat(resolved.securityEnabled()).isTrue();
        assertThat(resolved.managementSecurityEnabled()).isFalse();
        assertThat(resolved.port()).isEqualTo(AppHttpConfig.DEFAULT_APP_HTTP_PORT);
    }

    /// #962: `Main.run` reaches `resolveAppHttp` only through [Main#nodeConfig]. A node config assembled from a file with
    /// `[management] security_mode = "none"` and an api-key app plane must carry both resolved modes.
    @Test
    void nodeConfig_carriesTheResolvedManagementMode() {
        var config = nodeConfigFrom(load("""

            [app-http]
            enabled = "true"
            security_mode = "api-key"

            [management]
            security_mode = "none"
            """));

        assertThat(config.appHttp().securityEnabled()).isTrue();
        assertThat(config.appHttp().managementSecurityEnabled()).isFalse();
    }

    @Test
    void nodeConfig_failsClosed_whenThereIsNoConfigFile() {
        assertThat(nodeConfigFrom(Option.none()).appHttp().managementSecurityEnabled()).isTrue();
    }

    private static AetherNodeConfig nodeConfigFrom(Option<AetherConfig> aetherConfig) {
        var self = NodeId.nodeId("main-962").unwrap();
        var selfInfo = NodeInfo.nodeInfo(self, NodeAddress.nodeAddress("localhost", 8090).unwrap());

        return Main.nodeConfig(self,
                               List.of(selfInfo),
                               AetherNodeConfig.MANAGEMENT_DISABLED,
                               SliceConfig.sliceConfig(),
                               1,
                               aetherConfig,
                               Option.none(),
                               new Main.TlsBundle(TlsConfig.selfSignedMutual(), mock(CertificateProvider.class)),
                               Option.none(),
                               Option.none());
    }
}
