// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.config;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// #962: the Management API has its own OPTIONAL `[management] security_mode`. Absent, it inherits
/// `[app-http] security_mode` (the behaviour before the key existed); set, it is independent of the app plane.
/// A value that is not `none` is read as `api-key`, so a typo can only close the plane, never open it.
class ManagementSecurityModeTest {
    private static final String MINIMAL_CLUSTER = """
        [cluster]
        environment = "docker"
        nodes = 3
        """;

    private static AppHttpConfig load(String toml) {
        return ConfigLoader.loadFromString(MINIMAL_CLUSTER + toml)
                           .onFailure(cause -> fail(cause.message()))
                           .unwrap()
                           .appHttp();
    }

    @Test
    void absent_inheritsApiKeyFromTheAppPlane() {
        var appHttp = load("""

            [app-http]
            enabled = "true"
            security_mode = "api-key"
            """);

        assertThat(appHttp.managementSecurityMode().isEmpty()).isTrue();
        assertThat(appHttp.managementSecurityEnabled()).isTrue();
    }

    @Test
    void absent_inheritsJwtFromTheAppPlane_andArmsTheManagementPlane() {
        var appHttp = load("""

            [app-http]
            enabled = "true"
            security_mode = "jwt"
            jwks_url = "https://auth.example.com/jwks.json"
            """);

        assertThat(appHttp.securityMode()).isEqualTo(SecurityMode.JWT);
        assertThat(appHttp.managementSecurityMode().isEmpty()).isTrue();
        assertThat(appHttp.managementSecurityEnabled()).as("jwt is not none: the management plane authenticates with API keys").isTrue();
    }

    @Test
    void absent_inheritsNoneFromTheAppPlane() {
        var appHttp = load("""

            [app-http]
            enabled = "true"
            security_mode = "none"
            """);

        assertThat(appHttp.managementSecurityEnabled()).isFalse();
    }

    @Test
    void setToApiKey_armsTheManagementPlaneWhileTheAppPlaneIsNone() {
        var appHttp = load("""

            [app-http]
            enabled = "true"
            security_mode = "none"

            [management]
            security_mode = "api-key"
            """);

        assertThat(appHttp.securityEnabled()).isFalse();
        assertThat(appHttp.managementSecurityEnabled()).isTrue();
    }

    @Test
    void setToNone_opensTheManagementPlaneWhileTheAppPlaneIsApiKey() {
        var appHttp = load("""

            [app-http]
            enabled = "true"
            security_mode = "api-key"

            [management]
            security_mode = "none"
            """);

        assertThat(appHttp.securityEnabled()).isTrue();
        assertThat(appHttp.managementSecurityEnabled()).isFalse();
    }

    @Test
    void unrecognisedValue_failsClosed_evenWhenTheAppPlaneIsNone() {
        var appHttp = load("""

            [app-http]
            enabled = "true"
            security_mode = "none"

            [management]
            security_mode = "nnone"
            """);

        assertThat(appHttp.managementSecurityMode().or(SecurityMode.NONE)).isEqualTo(SecurityMode.API_KEY);
        assertThat(appHttp.managementSecurityEnabled()).isTrue();
    }

    @Test
    void jwt_isNotAManagementMode_andReadsAsApiKey() {
        var appHttp = load("""

            [app-http]
            enabled = "true"
            security_mode = "none"

            [management]
            security_mode = "jwt"
            """);

        assertThat(appHttp.managementSecurityMode().or(SecurityMode.NONE)).isEqualTo(SecurityMode.API_KEY);
        assertThat(appHttp.managementSecurityEnabled()).isTrue();
    }

    @Test
    void section_isCarriedWhenTheAppPlaneIsDisabled() {
        var appHttp = load("""

            [management]
            security_mode = "none"
            """);

        assertThat(appHttp.managementSecurityMode().isPresent()).isTrue();
        assertThat(appHttp.managementSecurityEnabled()).isFalse();
    }

    @Test
    void withTls_keepsTheManagementMode() {
        var appHttp = AppHttpConfig.appHttpConfig(8070)
                                   .withManagementSecurityMode(Option.some(SecurityMode.NONE))
                                   .withTls(new AppHttpConfig.AppTls("c", "k"));

        assertThat(appHttp.managementSecurityEnabled()).isFalse();
    }

    @Test
    void programmaticBuilders_inheritTheAppPlane() {
        assertThat(AppHttpConfig.appHttpConfig(8070).managementSecurityMode().isEmpty()).isTrue();
        assertThat(AppHttpConfig.appHttpConfig(8070).managementSecurityEnabled()).isTrue();
        assertThat(AppHttpConfig.insecureAppHttpConfig(8070).managementSecurityEnabled()).isFalse();
    }
}
