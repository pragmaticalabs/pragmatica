package org.pragmatica.aether.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
            .onFailure(cause -> Assertions.fail(cause.message()))
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
            .onFailure(cause -> Assertions.fail(cause.message()))
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
            .onFailure(cause -> Assertions.fail(cause.message()))
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
            .onFailure(cause -> Assertions.fail(cause.message()))
            .onSuccess(config -> {
                assertThat(config.appHttp().enabled()).isTrue();
                assertThat(config.appHttp().port()).isEqualTo(9090);
            });
    }

    @Test
    void loadFromString_parsesForwardTimeoutAndRetries() {
        var toml = MINIMAL_CLUSTER + """

            [app-http]
            enabled = "true"
            forward_timeout_ms = 10000
            forward_max_retries = 5
            """;

        ConfigLoader.loadFromString(toml)
            .onFailure(cause -> Assertions.fail(cause.message()))
            .onSuccess(config -> {
                assertThat(config.appHttp().forwardTimeoutMs()).isEqualTo(10000);
                assertThat(config.appHttp().forwardMaxRetries()).isEqualTo(5);
            });
    }

    @Test
    void loadFromString_usesDefaultAppHttpWhenNotSpecified() {
        var toml = MINIMAL_CLUSTER;

        ConfigLoader.loadFromString(toml)
            .onFailure(cause -> Assertions.fail(cause.message()))
            .onSuccess(config -> {
                assertThat(config.appHttp().enabled()).isFalse();
                assertThat(config.appHttp().securityEnabled()).isFalse();
                assertThat(config.appHttp().port()).isEqualTo(AppHttpConfig.DEFAULT_APP_HTTP_PORT);
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
            .onFailure(cause -> Assertions.fail(cause.message()))
            .onSuccess(config -> {
                assertThat(config.appHttp().securityEnabled()).isTrue();
                var entry = config.appHttp().apiKeys().get("my-secret-key-value");
                assertThat(entry).isNotNull();
                // Default name is truncated key, default role is service
                assertThat(entry.name()).isEqualTo("my-secre...");
                assertThat(entry.roles()).containsExactly("service");
            });
    }
}
