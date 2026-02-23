package org.pragmatica.config;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.config.source.MapConfigSource;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SecretResolvingConfigurationProviderTest {

    @Nested
    class SuccessfulResolution {

        @Test
        void resolve_noPlaceholders_returnsOriginalValues() {
            var provider = providerWith(Map.of("db.host", "localhost", "db.port", "5432"));

            var result = ConfigurationProvider.withSecretResolution(provider, path -> Promise.resolved(Result.success("resolved")));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.unwrap().getString("db.host").unwrap()).isEqualTo("localhost");
            assertThat(result.unwrap().getString("db.port").unwrap()).isEqualTo("5432");
        }

        @Test
        void resolve_singlePlaceholder_replacesValue() {
            var provider = providerWith(Map.of("db.password", "${secrets:db/password}"));

            var result = ConfigurationProvider.withSecretResolution(provider,
                path -> Promise.resolved(Result.success("s3cret")));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.unwrap().getString("db.password").unwrap()).isEqualTo("s3cret");
        }

        @Test
        void resolve_multiplePlaceholdersInSameValue_replacesAll() {
            var provider = providerWith(Map.of("db.url", "${secrets:db/user}:${secrets:db/pass}"));

            var result = ConfigurationProvider.withSecretResolution(provider,
                path -> Promise.resolved(Result.success(path.equals("db/user") ? "admin" : "secret")));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.unwrap().getString("db.url").unwrap()).isEqualTo("admin:secret");
        }

        @Test
        void resolve_mixedPlaceholderAndLiteral_preservesLiteralParts() {
            var provider = providerWith(Map.of("db.url", "jdbc:postgresql://host/${secrets:db/name}?ssl=true"));

            var result = ConfigurationProvider.withSecretResolution(provider,
                path -> Promise.resolved(Result.success("mydb")));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.unwrap().getString("db.url").unwrap()).isEqualTo("jdbc:postgresql://host/mydb?ssl=true");
        }

        @Test
        void resolve_multipleKeysWithPlaceholders_resolvesAll() {
            var provider = providerWith(Map.of(
                "db.user", "${secrets:db/user}",
                "db.pass", "${secrets:db/pass}",
                "db.host", "localhost"
            ));

            var result = ConfigurationProvider.withSecretResolution(provider,
                path -> Promise.resolved(Result.success(path.equals("db/user") ? "admin" : "secret")));

            assertThat(result.isSuccess()).isTrue();
            var resolved = result.unwrap();
            assertThat(resolved.getString("db.user").unwrap()).isEqualTo("admin");
            assertThat(resolved.getString("db.pass").unwrap()).isEqualTo("secret");
            assertThat(resolved.getString("db.host").unwrap()).isEqualTo("localhost");
        }

        @Test
        void resolve_partialPattern_notMatched() {
            var provider = providerWith(Map.of("key", "${secret:path}"));

            var result = ConfigurationProvider.withSecretResolution(provider,
                path -> Promise.resolved(Result.success("resolved")));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.unwrap().getString("key").unwrap()).isEqualTo("${secret:path}");
        }

        @Test
        void resolve_preservesSourceMetadata() {
            var source = MapConfigSource.mapConfigSource("test-source", Map.of("key", "${secrets:path}"), 42).unwrap();
            var provider = ConfigurationProvider.configurationProvider(source);

            var result = ConfigurationProvider.withSecretResolution(provider,
                path -> Promise.resolved(Result.success("resolved")));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.unwrap().sources()).hasSize(1);
            assertThat(result.unwrap().sources().getFirst().name()).isEqualTo("test-source");
        }
    }

    @Nested
    class FailedResolution {

        @Test
        void resolve_resolverFails_returnsFailure() {
            var provider = providerWith(Map.of("db.password", "${secrets:db/password}"));

            var result = ConfigurationProvider.withSecretResolution(provider,
                path -> Promise.resolved(Result.failure(Causes.cause("Vault unavailable"))));

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> {
                assertThat(cause).isInstanceOf(ConfigError.SecretResolutionFailed.class);
                var error = (ConfigError.SecretResolutionFailed) cause;
                assertThat(error.key()).isEqualTo("db.password");
                assertThat(error.secretPath()).isEqualTo("db/password");
                assertThat(error.message()).contains("Vault unavailable");
            });
        }
    }

    private static ConfigurationProvider providerWith(Map<String, String> values) {
        return ConfigurationProvider.configurationProvider(
            MapConfigSource.mapConfigSource("test", values).unwrap());
    }
}
