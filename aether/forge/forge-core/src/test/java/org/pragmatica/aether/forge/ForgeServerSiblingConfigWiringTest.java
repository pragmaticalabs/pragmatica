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
                                        + "[app-http.api-keys.k-962-key]\nname = \"ops\"\nroles = [\"admin\"]\nauthorization_role = \"ADMIN\"\n";

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

    private static final String NO_KEYS_MGMT_API_KEY = "[cluster]\nenvironment = \"docker\"\nnodes = 3\n\n[management]\nsecurity_mode = \"api-key\"\n";

    /// An explicit `api-key` management mode with no key declared would leave Forge's own control calls refused (401): Forge
    /// REFUSES to start (exit 1) instead, and arms nothing.
    @Test
    void applyAppHttpSecurity_refusesManagementApiKey_whenNoKeyIsDeclared(@TempDir Path dir) throws Exception {
        var ember = EmberCluster.emberCluster();
        var exits = new ArrayList<Integer>();

        serverWithSibling(dir, NO_KEYS_MGMT_API_KEY).applyAppHttpSecurity(ember, exits::add);

        assertThat(exits).as("a refusal to start").containsExactly(1);
        assertThat(ember.appHttpConfigFor(8070).managementSecurityEnabled()).as("nothing was armed").isFalse();
    }

    @Test
    void keylessManagementRefusal_namesTheFileTheSettingAndBothFixes() {
        assertThat(ForgeAppConfig.keylessManagementRefusal("/etc/forge/aether.toml")).contains("/etc/forge/aether.toml")
                                                                                     .contains("[management] security_mode = \"api-key\"")
                                                                                     .contains("declares no API key")
                                                                                     .contains("Declare a key")
                                                                                     .contains("choose another mode");
    }

    /// `jwt` is not a management mode: the loader refuses the sibling (exit 1) before Forge's own keyless check is reached.
    @Test
    void applyAppHttpSecurity_refusesManagementJwt_asAnUnsupportedMode(@TempDir Path dir) throws Exception {
        var exits = new ArrayList<Integer>();

        serverWithSibling(dir, NO_KEYS_MGMT_API_KEY.replace("api-key", "jwt")).applyAppHttpSecurity(EmberCluster.emberCluster(), exits::add);

        assertThat(exits).containsExactly(1);
    }

    /// Controls: the refusal is only for api-key WITHOUT a key. `none` without keys, and `api-key` with a key, both start.
    @Test
    void applyAppHttpSecurity_doesNotRefuse_managementNoneWithoutKeys_orManagementApiKeyWithAKey(@TempDir Path dir) throws Exception {
        var noneEmber = EmberCluster.emberCluster();
        var keyedEmber = EmberCluster.emberCluster();
        var exits = new ArrayList<Integer>();

        serverWithSibling(dir, NO_KEYS_MGMT_API_KEY.replace("api-key", "none")).applyAppHttpSecurity(noneEmber, exits::add);
        serverWithSibling(dir, KEYED + "\n[management]\nsecurity_mode = \"api-key\"\n").applyAppHttpSecurity(keyedEmber, exits::add);

        assertThat(exits).isEmpty();
        assertThat(noneEmber.appHttpConfigFor(8070).managementSecurityEnabled()).isFalse();
        assertThat(keyedEmber.appHttpConfigFor(8070).managementSecurityEnabled()).isTrue();
    }

    /// Control: a Forge cluster with no sibling setting stays as it was, management open under the NONE app plane.
    @Test
    void emberCluster_defaultsToAnOpenManagementPlane() {
        assertThat(EmberCluster.emberCluster().appHttpConfigFor(8070).managementSecurityEnabled()).isFalse();
    }
}
