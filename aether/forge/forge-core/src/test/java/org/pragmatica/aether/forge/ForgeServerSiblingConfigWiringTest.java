// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.ember.EmberConfig;
import org.pragmatica.http.JdkHttpOperations;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// #909: the CALL SITES of the sibling-config refusal. `ForgeAppConfigTest` pins the helper; these pin that `applyApiVersioning` and
/// `applyAppHttpSecurity` actually go through it, so removing either call leaves an unloadable `aether.toml` silently dropped again.
class ForgeServerSiblingConfigWiringTest {
    private static final String JWT_WITHOUT_JWKS = "[cluster]\nenvironment = \"docker\"\nnodes = 3\n\n[app-http]\nenabled = true\nsecurity_mode = \"jwt\"\n";
    private static final String VALID = "[cluster]\nenvironment = \"docker\"\nnodes = 3\n\n[app-http]\nenabled = true\nsecurity_mode = \"api_key\"\n";

    private static ForgeServer serverWithSibling(Path dir, String sibling) throws Exception {
        Files.writeString(dir.resolve("aether.toml"), sibling);
        var base = EmberConfig.DEFAULT;
        var startup = new StartupConfig(Option.some(dir.resolve("forge.toml")), Option.none(), Option.none(), false, 8888, base.nodes(), 1000);

        return new ForgeServer(startup, base, JdkHttpOperations.jdkHttpOperations());
    }

    @Test
    void applyApiVersioning_refusesAnUnloadableSibling_withExit1(@TempDir Path dir) throws Exception {
        var exits = new ArrayList<Integer>();

        serverWithSibling(dir, JWT_WITHOUT_JWKS).applyApiVersioning(EmberCluster.emberCluster(), exits::add);

        assertThat(exits).containsExactly(1);
    }

    @Test
    void applyAppHttpSecurity_refusesAnUnloadableSibling_withExit1(@TempDir Path dir) throws Exception {
        var exits = new ArrayList<Integer>();

        serverWithSibling(dir, JWT_WITHOUT_JWKS).applyAppHttpSecurity(EmberCluster.emberCluster(), exits::add);

        assertThat(exits).containsExactly(1);
    }

    @Test
    void aLoadableSibling_neverExits(@TempDir Path dir) throws Exception {
        var exits = new ArrayList<Integer>();
        var server = serverWithSibling(dir, VALID);

        server.applyApiVersioning(EmberCluster.emberCluster(), exits::add);
        server.applyAppHttpSecurity(EmberCluster.emberCluster(), exits::add);

        assertThat(exits).as("CONTROL: a valid sibling is not a refusal").isEmpty();
    }

    private static final String KEYED = "[cluster]\nenvironment = \"docker\"\nnodes = 3\n\n[app-http]\nenabled = true\nsecurity_mode = \"api-key\"\n\n"
                                        + "[app-http.api-keys.\"k-962\"]\nname = \"ops\"\nroles = [\"admin\"]\nauthorization_role = \"ADMIN\"\n";

    /// #962: `forge run` HONOURS `[management] security_mode` from the sibling `aether.toml`. A keyed api-key app plane with
    /// `[management] security_mode = "none"` yields embedded nodes whose app plane authenticates and whose management plane does not.
    @Test
    void applyAppHttpSecurity_honoursManagementNone_whileTheAppPlaneIsApiKey(@TempDir Path dir) throws Exception {
        var ember = EmberCluster.emberCluster();

        serverWithSibling(dir, KEYED + "\n[management]\nsecurity_mode = \"none\"\n").applyAppHttpSecurity(ember, code -> {});

        var appHttp = ember.appHttpConfigFor(8070);

        assertThat(appHttp.securityEnabled()).as("the app plane authenticates").isTrue();
        assertThat(appHttp.managementSecurityEnabled()).as("the management plane is open, as configured").isFalse();
    }

    /// Control: the same keyed sibling without a `[management]` section inherits, so the management plane authenticates too.
    @Test
    void applyAppHttpSecurity_withoutManagementSection_inheritsTheAppMode(@TempDir Path dir) throws Exception {
        var ember = EmberCluster.emberCluster();

        serverWithSibling(dir, KEYED).applyAppHttpSecurity(ember, code -> {});

        assertThat(ember.appHttpConfigFor(8070).managementSecurityEnabled()).isTrue();
    }

    /// The management mode is independent of the key gate: no keys leave the app plane NONE (unchanged), yet an explicit
    /// `[management] security_mode = "api-key"` still arms the management plane.
    @Test
    void applyAppHttpSecurity_honoursManagementApiKey_evenWithoutKeys(@TempDir Path dir) throws Exception {
        var ember = EmberCluster.emberCluster();

        serverWithSibling(dir, "[cluster]\nenvironment = \"docker\"\nnodes = 3\n\n[management]\nsecurity_mode = \"api-key\"\n").applyAppHttpSecurity(ember, code -> {});

        var appHttp = ember.appHttpConfigFor(8070);

        assertThat(appHttp.securityEnabled()).as("no keys: the app plane stays NONE, as before").isFalse();
        assertThat(appHttp.managementSecurityEnabled()).isTrue();
    }

    /// Control: a Forge cluster with no sibling setting stays as it was, management open under the NONE app plane.
    @Test
    void emberCluster_defaultsToAnOpenManagementPlane() {
        assertThat(EmberCluster.emberCluster().appHttpConfigFor(8070).managementSecurityEnabled()).isFalse();
    }
}
