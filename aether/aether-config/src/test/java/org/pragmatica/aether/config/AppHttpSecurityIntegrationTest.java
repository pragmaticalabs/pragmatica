package org.pragmatica.aether.config;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// Integration test verifying TOML -> ConfigLoader -> AppHttpConfig -> SecurityMode/JwtConfig pipeline.
class AppHttpSecurityIntegrationTest {

    private static final String MINIMAL_CLUSTER = """
        [cluster]
        environment = "docker"
        nodes = 3
        """;

    @Nested
    class SecurityModeNone {
        @Test
        void noSecurityMode_noApiKeys_defaultsToNone() {
            var toml = MINIMAL_CLUSTER + """

                [app-http]
                enabled = "true"
                """;

            ConfigLoader.loadFromString(toml)
                .onFailure(cause -> fail(cause.message()))
                .onSuccess(config -> {
                    assertThat(config.appHttp().securityMode()).isEqualTo(SecurityMode.NONE);
                    assertThat(config.appHttp().securityEnabled()).isFalse();
                    assertThat(config.appHttp().jwtConfig().isEmpty()).isTrue();
                });
        }

        @Test
        void explicitNone_parsesCorrectly() {
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
        void noAppHttpSection_defaultsToDisabledNone() {
            ConfigLoader.loadFromString(MINIMAL_CLUSTER)
                .onFailure(cause -> fail(cause.message()))
                .onSuccess(config -> {
                    assertThat(config.appHttp().enabled()).isFalse();
                    assertThat(config.appHttp().securityMode()).isEqualTo(SecurityMode.NONE);
                    assertThat(config.appHttp().securityEnabled()).isFalse();
                    assertThat(config.appHttp().jwtConfig().isEmpty()).isTrue();
                });
        }
    }

    @Nested
    class SecurityModeApiKey {
        @Test
        void explicitApiKeyMode_withSimpleKeys() {
            var toml = MINIMAL_CLUSTER + """

                [app-http]
                enabled = "true"
                security_mode = "api-key"
                api_keys = ["key-alpha", "key-beta"]
                """;

            ConfigLoader.loadFromString(toml)
                .onFailure(cause -> fail(cause.message()))
                .onSuccess(config -> {
                    assertThat(config.appHttp().securityMode()).isEqualTo(SecurityMode.API_KEY);
                    assertThat(config.appHttp().securityEnabled()).isTrue();
                    assertThat(config.appHttp().apiKeyValues()).containsExactlyInAnyOrder("key-alpha", "key-beta");
                    assertThat(config.appHttp().jwtConfig().isEmpty()).isTrue();
                });
        }

        @Test
        void autoUpgradeToApiKey_whenKeysPresent_noExplicitMode() {
            var toml = MINIMAL_CLUSTER + """

                [app-http]
                enabled = "true"
                api_keys = ["auto-key"]
                """;

            ConfigLoader.loadFromString(toml)
                .onFailure(cause -> fail(cause.message()))
                .onSuccess(config -> {
                    assertThat(config.appHttp().securityMode()).isEqualTo(SecurityMode.API_KEY);
                    assertThat(config.appHttp().securityEnabled()).isTrue();
                });
        }

        @Test
        void richApiKeys_withAuthorizationRoles() {
            var toml = MINIMAL_CLUSTER + """

                [app-http]
                enabled = "true"
                security_mode = "api-key"

                [app-http.api-keys.admin-key-value]
                name = "admin-svc"
                roles = ["admin"]
                authorization_role = "ADMIN"

                [app-http.api-keys.viewer-key-value]
                name = "dashboard"
                roles = ["service"]
                authorization_role = "VIEWER"
                """;

            ConfigLoader.loadFromString(toml)
                .onFailure(cause -> fail(cause.message()))
                .onSuccess(config -> {
                    assertThat(config.appHttp().securityMode()).isEqualTo(SecurityMode.API_KEY);
                    assertThat(config.appHttp().apiKeys()).hasSize(2);

                    var admin = config.appHttp().apiKeys().get("admin-key-value");
                    assertThat(admin.name()).isEqualTo("admin-svc");
                    assertThat(admin.authorizationRole()).isEqualTo("ADMIN");

                    var viewer = config.appHttp().apiKeys().get("viewer-key-value");
                    assertThat(viewer.name()).isEqualTo("dashboard");
                    assertThat(viewer.authorizationRole()).isEqualTo("VIEWER");
                });
        }
    }

    @Nested
    class SecurityModeJwt {
        @Test
        void jwtMode_fullConfig() {
            var toml = MINIMAL_CLUSTER + """

                [app-http]
                enabled = "true"
                security_mode = "jwt"
                jwks_url = "https://auth.example.com/.well-known/jwks.json"
                issuer = "https://auth.example.com/"
                audience = "my-api"
                role_claim = "permissions"
                jwks_cache_ttl_seconds = 7200
                """;

            ConfigLoader.loadFromString(toml)
                .onFailure(cause -> fail(cause.message()))
                .onSuccess(config -> {
                    assertThat(config.appHttp().securityMode()).isEqualTo(SecurityMode.JWT);
                    assertThat(config.appHttp().securityEnabled()).isTrue();

                    var jwt = config.appHttp().jwtConfig();
                    assertThat(jwt.isPresent()).isTrue();
                    jwt.onPresent(jwtConfig -> {
                        assertThat(jwtConfig.jwksUrl()).isEqualTo("https://auth.example.com/.well-known/jwks.json");
                        assertThat(jwtConfig.issuer().unwrap()).isEqualTo("https://auth.example.com/");
                        assertThat(jwtConfig.audience().unwrap()).isEqualTo("my-api");
                        assertThat(jwtConfig.roleClaim()).isEqualTo("permissions");
                        assertThat(jwtConfig.cacheTtlSeconds()).isEqualTo(7200);
                    });
                });
        }

        @Test
        void jwtMode_minimalConfig_usesDefaults() {
            var toml = MINIMAL_CLUSTER + """

                [app-http]
                enabled = "true"
                security_mode = "jwt"
                jwks_url = "https://auth.example.com/.well-known/jwks.json"
                """;

            ConfigLoader.loadFromString(toml)
                .onFailure(cause -> fail(cause.message()))
                .onSuccess(config -> {
                    assertThat(config.appHttp().securityMode()).isEqualTo(SecurityMode.JWT);

                    var jwt = config.appHttp().jwtConfig();
                    assertThat(jwt.isPresent()).isTrue();
                    jwt.onPresent(jwtConfig -> {
                        assertThat(jwtConfig.jwksUrl()).isEqualTo("https://auth.example.com/.well-known/jwks.json");
                        assertThat(jwtConfig.issuer().isEmpty()).isTrue();
                        assertThat(jwtConfig.audience().isEmpty()).isTrue();
                        assertThat(jwtConfig.roleClaim()).isEqualTo(JwtConfig.DEFAULT_ROLE_CLAIM);
                        assertThat(jwtConfig.cacheTtlSeconds()).isEqualTo(JwtConfig.DEFAULT_CACHE_TTL_SECONDS);
                    });
                });
        }

        @Test
        void jwtMode_noJwksUrl_resultsInEmptyJwtConfig() {
            var toml = MINIMAL_CLUSTER + """

                [app-http]
                enabled = "true"
                security_mode = "jwt"
                """;

            ConfigLoader.loadFromString(toml)
                .onFailure(cause -> fail(cause.message()))
                .onSuccess(config -> {
                    assertThat(config.appHttp().securityMode()).isEqualTo(SecurityMode.JWT);
                    assertThat(config.appHttp().jwtConfig().isEmpty()).isTrue();
                });
        }
    }

    @Nested
    class RequestSizeConfig {
        @Test
        void maxRequestSize_parsesHumanReadableFormat() {
            var toml = MINIMAL_CLUSTER + """

                [app-http]
                enabled = "true"
                max_request_size = "25MB"
                """;

            ConfigLoader.loadFromString(toml)
                .onFailure(cause -> fail(cause.message()))
                .onSuccess(config -> assertThat(config.appHttp().maxRequestSize()).isEqualTo(25 * 1024 * 1024));
        }

        @Test
        void maxRequestSize_defaultsTo10MB() {
            var toml = MINIMAL_CLUSTER + """

                [app-http]
                enabled = "true"
                """;

            ConfigLoader.loadFromString(toml)
                .onFailure(cause -> fail(cause.message()))
                .onSuccess(config -> assertThat(config.appHttp().maxRequestSize()).isEqualTo(AppHttpConfig.DEFAULT_MAX_REQUEST_SIZE));
        }
    }

    @Nested
    class FullPipelineIntegration {
        @Test
        void fullConfig_allSecurityFieldsParsedThroughValidation() {
            var toml = """
                [cluster]
                environment = "docker"
                nodes = 5

                [app-http]
                enabled = "true"
                port = 9090
                security_mode = "jwt"
                jwks_url = "https://idp.corp.com/keys"
                issuer = "https://idp.corp.com"
                audience = "aether-cluster"
                role_claim = "groups"
                jwks_cache_ttl_seconds = 1800
                max_request_size = "50MB"
                forward_timeout_ms = 15000
                """;

            ConfigLoader.loadFromString(toml)
                .onFailure(cause -> fail(cause.message()))
                .onSuccess(config -> {
                    var appHttp = config.appHttp();

                    assertThat(appHttp.enabled()).isTrue();
                    assertThat(appHttp.port()).isEqualTo(9090);
                    assertThat(appHttp.securityMode()).isEqualTo(SecurityMode.JWT);
                    assertThat(appHttp.securityEnabled()).isTrue();
                    assertThat(appHttp.maxRequestSize()).isEqualTo(50 * 1024 * 1024);
                    assertThat(appHttp.forwardTimeout().millis()).isEqualTo(15000);

                    var jwt = appHttp.jwtConfig();
                    assertThat(jwt.isPresent()).isTrue();
                    jwt.onPresent(jwtConfig -> {
                        assertThat(jwtConfig.jwksUrl()).isEqualTo("https://idp.corp.com/keys");
                        assertThat(jwtConfig.issuer().unwrap()).isEqualTo("https://idp.corp.com");
                        assertThat(jwtConfig.audience().unwrap()).isEqualTo("aether-cluster");
                        assertThat(jwtConfig.roleClaim()).isEqualTo("groups");
                        assertThat(jwtConfig.cacheTtlSeconds()).isEqualTo(1800);
                    });
                });
        }

        @Test
        void fullConfig_apiKeyModeWithRichKeysAndRequestSize() {
            var toml = """
                [cluster]
                environment = "docker"
                nodes = 3

                [app-http]
                enabled = "true"
                port = 8070
                security_mode = "api-key"
                max_request_size = "2MB"

                [app-http.api-keys.prod-key-abc]
                name = "production-backend"
                roles = ["admin", "service"]
                authorization_role = "OPERATOR"
                """;

            ConfigLoader.loadFromString(toml)
                .onFailure(cause -> fail(cause.message()))
                .onSuccess(config -> {
                    var appHttp = config.appHttp();

                    assertThat(appHttp.securityMode()).isEqualTo(SecurityMode.API_KEY);
                    assertThat(appHttp.maxRequestSize()).isEqualTo(2 * 1024 * 1024);
                    assertThat(appHttp.jwtConfig().isEmpty()).isTrue();

                    var entry = appHttp.apiKeys().get("prod-key-abc");
                    assertThat(entry.name()).isEqualTo("production-backend");
                    assertThat(entry.roles()).containsExactlyInAnyOrder("admin", "service");
                    assertThat(entry.authorizationRole()).isEqualTo("OPERATOR");
                });
        }
    }
}
