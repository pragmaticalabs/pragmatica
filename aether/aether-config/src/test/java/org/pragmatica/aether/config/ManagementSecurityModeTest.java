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
/// A value the plane cannot honour (`jwt`, a typo) is refused by the loader, never rewritten.
class ManagementSecurityModeTest {
    private static final String MINIMAL_CLUSTER = """
        [cluster]
        environment = "docker"
        nodes = 3
        """;

    private static String refusal(String toml) {
        return ConfigLoader.loadFromString(MINIMAL_CLUSTER + toml)
                           .fold(cause -> cause.message(),
                                 config -> fail("expected a refusal but the config loaded"));
    }

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

    /// The loader REFUSES a `security_mode` it cannot honour instead of rewriting it (#1455: refuses, never drops). `jwt` is not a
    /// management mode, and a typo is not a mode at all; both used to read as `api-key` without a word.
    @Test
    void jwt_isRefused_asNotAManagementMode() {
        var message = refusal("""

            [app-http]
            enabled = "true"
            security_mode = "none"

            [management]
            security_mode = "jwt"
            """);

        assertThat(message).contains("[management] security_mode = \"jwt\"")
                           .contains("the management plane has no JWT mode")
                           .contains("Allowed values: none, api-key");
    }

    @Test
    void unrecognisedValue_isRefused_namingKeyValueAndAllowedValues() {
        var message = refusal("""

            [app-http]
            enabled = "true"
            security_mode = "none"

            [management]
            security_mode = "nnone"
            """);

        assertThat(message).contains("[management] security_mode = \"nnone\"")
                           .contains("is not a security mode")
                           .contains("Allowed values: none, api-key");
    }

    @Test
    void unrecognisedAppHttpValue_isRefused_insteadOfReadingAsTheDefault() {
        var message = refusal("""

            [app-http]
            enabled = "true"
            security_mode = "nnone"
            """);

        assertThat(message).contains("[app-http] security_mode = \"nnone\"")
                           .contains("Allowed values: none, api-key, jwt");
    }

    /// Control: the spellings the parser accepts still load, so the refusals above are not a loader that refuses everything.
    @Test
    void acceptedSpellings_stillLoad() {
        var appHttp = load("""

            [app-http]
            enabled = "true"
            security_mode = "API_KEY"

            [management]
            security_mode = " None "
            """);

        assertThat(appHttp.securityMode()).isEqualTo(SecurityMode.API_KEY);
        assertThat(appHttp.managementSecurityEnabled()).isFalse();
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
