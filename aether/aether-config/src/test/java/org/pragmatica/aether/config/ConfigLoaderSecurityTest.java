package org.pragmatica.aether.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class ConfigLoaderSecurityTest {

    private static final String MINIMAL_CLUSTER = """
        [cluster]
        environment = "docker"
        nodes = 3
        """;

    @Test
    void loadFromString_parsesSimpleApiKeysList() {
        var toml = MINIMAL_CLUSTER + """

            [app-http]
            enabled = "true"
            api_keys = ["key1", "key2"]
            """;

        ConfigLoader.loadFromString(toml)
            .onFailure(cause -> fail(cause.message()))
            .onSuccess(config -> {
                assertThat(config.appHttp().securityEnabled()).isTrue();
                assertThat(config.appHttp().apiKeyValues()).containsExactlyInAnyOrder("key1", "key2");
                assertThat(config.appHttp().apiKeys().get("key1").roles()).containsExactly("service");
            });
    }

    @Test
    void loadFromString_parsesRichApiKeysSections() {
        var toml = MINIMAL_CLUSTER + """

            [app-http]
            enabled = "true"

            [app-http.api-keys.secret-key-abc]
            name = "admin-service"
            roles = ["admin", "service"]

            [app-http.api-keys.secret-key-xyz]
            name = "read-only-svc"
            roles = ["service"]
            """;

        ConfigLoader.loadFromString(toml)
            .onFailure(cause -> fail(cause.message()))
            .onSuccess(config -> {
                assertThat(config.appHttp().securityEnabled()).isTrue();
                assertThat(config.appHttp().apiKeys()).hasSize(2);

                var adminEntry = config.appHttp().apiKeys().get("secret-key-abc");
                assertThat(adminEntry.name()).isEqualTo("admin-service");
                assertThat(adminEntry.roles()).containsExactlyInAnyOrder("admin", "service");

                var readOnlyEntry = config.appHttp().apiKeys().get("secret-key-xyz");
                assertThat(readOnlyEntry.name()).isEqualTo("read-only-svc");
                assertThat(readOnlyEntry.roles()).containsExactly("service");
            });
    }

    @Test
    void loadFromString_emptyApiKeysResultsInSecurityDisabled() {
        var toml = MINIMAL_CLUSTER + """

            [app-http]
            enabled = "true"
            """;

        ConfigLoader.loadFromString(toml)
            .onFailure(cause -> fail(cause.message()))
            .onSuccess(config -> {
                assertThat(config.appHttp().securityEnabled()).isFalse();
                assertThat(config.appHttp().apiKeys()).isEmpty();
            });
    }

    @Test
    void loadFromString_parsesAppHttpPortAndEnabled() {
        var toml = MINIMAL_CLUSTER + """

            [app-http]
            enabled = "true"
            port = 9090
            """;

        ConfigLoader.loadFromString(toml)
            .onFailure(cause -> fail(cause.message()))
            .onSuccess(config -> {
                assertThat(config.appHttp().enabled()).isTrue();
                assertThat(config.appHttp().port()).isEqualTo(9090);
            });
    }

    @Test
    void loadFromString_parsesForwardTimeout() {
        var toml = MINIMAL_CLUSTER + """

            [app-http]
            enabled = "true"
            forward_timeout_ms = 10000
            """;

        ConfigLoader.loadFromString(toml)
            .onFailure(cause -> fail(cause.message()))
            .onSuccess(config -> {
                assertThat(config.appHttp().forwardTimeout().millis()).isEqualTo(10000);
            });
    }

    @Test
    void loadFromString_usesDefaultAppHttpWhenNotSpecified() {
        var toml = MINIMAL_CLUSTER;

        ConfigLoader.loadFromString(toml)
            .onFailure(cause -> fail(cause.message()))
            .onSuccess(config -> {
                assertThat(config.appHttp().enabled()).isFalse();
                assertThat(config.appHttp().securityEnabled()).isFalse();
                assertThat(config.appHttp().port()).isEqualTo(AppHttpConfig.DEFAULT_APP_HTTP_PORT);
            });
    }

    @Test
    void loadFromString_parsesAuthorizationRoleFromRichApiKeys() {
        var toml = MINIMAL_CLUSTER + """

            [app-http]
            enabled = "true"

            [app-http.api-keys.admin-key]
            name = "admin-service"
            roles = ["admin"]
            authorization_role = "ADMIN"

            [app-http.api-keys.viewer-key]
            name = "read-only"
            roles = ["service"]
            authorization_role = "VIEWER"

            [app-http.api-keys.operator-key]
            name = "ops-bot"
            roles = ["service"]
            authorization_role = "OPERATOR"
            """;

        ConfigLoader.loadFromString(toml)
            .onFailure(cause -> fail(cause.message()))
            .onSuccess(config -> {
                var adminEntry = config.appHttp().apiKeys().get("admin-key");
                assertThat(adminEntry.authorizationRole()).isEqualTo("ADMIN");

                var viewerEntry = config.appHttp().apiKeys().get("viewer-key");
                assertThat(viewerEntry.authorizationRole()).isEqualTo("VIEWER");

                var operatorEntry = config.appHttp().apiKeys().get("operator-key");
                assertThat(operatorEntry.authorizationRole()).isEqualTo("OPERATOR");
            });
    }

    @Test
    void loadFromString_defaultsAuthorizationRoleToAdminWhenOmitted() {
        var toml = MINIMAL_CLUSTER + """

            [app-http]
            enabled = "true"

            [app-http.api-keys.no-role-key]
            name = "legacy-service"
            roles = ["service"]
            """;

        ConfigLoader.loadFromString(toml)
            .onFailure(cause -> fail(cause.message()))
            .onSuccess(config -> {
                var entry = config.appHttp().apiKeys().get("no-role-key");
                assertThat(entry.authorizationRole()).isEqualTo("ADMIN");
            });
    }

    @Test
    void loadFromString_normalizesLowercaseAuthorizationRole() {
        var toml = MINIMAL_CLUSTER + """

            [app-http]
            enabled = "true"

            [app-http.api-keys.mixed-case-key]
            name = "mixed"
            roles = ["service"]
            authorization_role = "viewer"
            """;

        ConfigLoader.loadFromString(toml)
            .onFailure(cause -> fail(cause.message()))
            .onSuccess(config -> {
                var entry = config.appHttp().apiKeys().get("mixed-case-key");
                assertThat(entry.authorizationRole()).isEqualTo("VIEWER");
            });
    }

    @Test
    void loadFromString_simpleApiKeysDefaultToAdminAuthorizationRole() {
        var toml = MINIMAL_CLUSTER + """

            [app-http]
            enabled = "true"
            api_keys = ["simple-key"]
            """;

        ConfigLoader.loadFromString(toml)
            .onFailure(cause -> fail(cause.message()))
            .onSuccess(config -> {
                var entry = config.appHttp().apiKeys().get("simple-key");
                assertThat(entry.authorizationRole()).isEqualTo("ADMIN");
            });
    }

    @Test
    void loadFromString_parsesMaxRequestSize() {
        var toml = MINIMAL_CLUSTER + """

            [app-http]
            enabled = "true"
            max_request_size = "5MB"
            """;

        ConfigLoader.loadFromString(toml)
            .onFailure(cause -> fail(cause.message()))
            .onSuccess(config -> {
                assertThat(config.appHttp().maxRequestSize()).isEqualTo(5 * 1024 * 1024);
            });
    }

    @Test
    void loadFromString_usesDefaultMaxRequestSize_whenNotSpecified() {
        var toml = MINIMAL_CLUSTER + """

            [app-http]
            enabled = "true"
            """;

        ConfigLoader.loadFromString(toml)
            .onFailure(cause -> fail(cause.message()))
            .onSuccess(config -> {
                assertThat(config.appHttp().maxRequestSize()).isEqualTo(AppHttpConfig.DEFAULT_MAX_REQUEST_SIZE);
            });
    }

    @Test
    void loadFromString_parsesMaxRequestSizeInKB() {
        var toml = MINIMAL_CLUSTER + """

            [app-http]
            enabled = "true"
            max_request_size = "512KB"
            """;

        ConfigLoader.loadFromString(toml)
            .onFailure(cause -> fail(cause.message()))
            .onSuccess(config -> {
                assertThat(config.appHttp().maxRequestSize()).isEqualTo(512 * 1024);
            });
    }

    @Test
    void loadFromString_parsesMaxRequestSizeInGB() {
        var toml = MINIMAL_CLUSTER + """

            [app-http]
            enabled = "true"
            max_request_size = "1GB"
            """;

        ConfigLoader.loadFromString(toml)
            .onFailure(cause -> fail(cause.message()))
            .onSuccess(config -> {
                assertThat(config.appHttp().maxRequestSize()).isEqualTo(1024 * 1024 * 1024);
            });
    }

    @Test
    void loadFromString_parsesSecurityModeApiKey() {
        var toml = MINIMAL_CLUSTER + """

            [app-http]
            enabled = "true"
            security_mode = "api-key"
            api_keys = ["key1"]
            """;

        ConfigLoader.loadFromString(toml)
            .onFailure(cause -> fail(cause.message()))
            .onSuccess(config -> {
                assertThat(config.appHttp().securityMode()).isEqualTo(SecurityMode.API_KEY);
                assertThat(config.appHttp().securityEnabled()).isTrue();
            });
    }

    @Test
    void loadFromString_parsesSecurityModeNone() {
        var toml = MINIMAL_CLUSTER + """

            [app-http]
            enabled = "true"
            security_mode = "none"
            """;

        ConfigLoader.loadFromString(toml)
            .onFailure(cause -> fail(cause.message()))
            .onSuccess(config -> {
                assertThat(config.appHttp().securityMode()).isEqualTo(SecurityMode.NONE);
                assertThat(config.appHttp().securityEnabled()).isFalse();
            });
    }

    @Test
    void loadFromString_defaultsSecurityModeToNoneWhenNotSpecified() {
        var toml = MINIMAL_CLUSTER + """

            [app-http]
            enabled = "true"
            """;

        ConfigLoader.loadFromString(toml)
            .onFailure(cause -> fail(cause.message()))
            .onSuccess(config -> {
                assertThat(config.appHttp().securityMode()).isEqualTo(SecurityMode.NONE);
            });
    }

    @Test
    void loadFromString_autoUpgradesSecurityModeWhenApiKeysPresent() {
        var toml = MINIMAL_CLUSTER + """

            [app-http]
            enabled = "true"
            api_keys = ["key1"]
            """;

        ConfigLoader.loadFromString(toml)
            .onFailure(cause -> fail(cause.message()))
            .onSuccess(config -> {
                // No explicit security_mode, but apiKeys present -> auto-upgrade to API_KEY
                assertThat(config.appHttp().securityMode()).isEqualTo(SecurityMode.API_KEY);
                assertThat(config.appHttp().securityEnabled()).isTrue();
            });
    }

    @Test
    void loadFromString_explicitNoneOverridesApiKeyAutoUpgrade() {
        var toml = MINIMAL_CLUSTER + """

            [app-http]
            enabled = "true"
            security_mode = "none"
            api_keys = ["key1"]
            """;

        ConfigLoader.loadFromString(toml)
            .onFailure(cause -> fail(cause.message()))
            .onSuccess(config -> {
                // Explicit "none" is honored even with apiKeys present
                // (keys are stored but not enforced)
                assertThat(config.appHttp().securityMode()).isEqualTo(SecurityMode.NONE);
                assertThat(config.appHttp().securityEnabled()).isFalse();
            });
    }

    @Test
    void loadFromString_richApiKeysDefaultNameAndRolesWhenOmitted() {
        var toml = MINIMAL_CLUSTER + """

            [app-http]
            enabled = "true"

            [app-http.api-keys.my-secret-key-value]
            """;

        ConfigLoader.loadFromString(toml)
            .onFailure(cause -> fail(cause.message()))
            .onSuccess(config -> {
                assertThat(config.appHttp().securityEnabled()).isTrue();
                var entry = config.appHttp().apiKeys().get("my-secret-key-value");
                assertThat(entry).isNotNull();
                // Default name is hash-derived, default role is service
                assertThat(entry.name()).isEqualTo("key-" + Integer.toHexString("my-secret-key-value".hashCode()));
                assertThat(entry.roles()).containsExactly("service");
            });
    }
}
