package org.pragmatica.config;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.config.source.MapConfigSource;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import org.pragmatica.lang.Option;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

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

    /// #904: the no-resolver counterpart. A placeholder nothing can resolve is refused up front,
    /// naming key and path; a placeholder-free provider is returned as the SAME instance.
    @Nested
    class NoResolver {

        @Test
        void withoutSecretResolution_noPlaceholder_returnsTheSameProvider() {
            var provider = providerWith(Map.of("db.host", "localhost", "db.password", "plain"));

            var result = ConfigurationProvider.withoutSecretResolution(provider);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.unwrap()).isSameAs(provider);
        }

        @Test
        void withoutSecretResolution_placeholderPresent_failsNamingKeyAndPath() {
            var provider = providerWith(Map.of("db.host", "localhost", "db.password", "${secrets:db/password}"));

            var result = ConfigurationProvider.withoutSecretResolution(provider);

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> {
                assertThat(cause).isInstanceOf(ConfigError.SecretResolutionFailed.class);
                var error = (ConfigError.SecretResolutionFailed) cause;
                assertThat(error.key()).isEqualTo("db.password");
                assertThat(error.secretPath()).isEqualTo("db/password");
                assertThat(error.message()).contains("no secrets provider");
            });
        }
    }

    @Nested
    class Reload {

        @Test
        void reload_returnedProviderStillResolvesSecrets() {
            var source = new ReloadingSource(Map.of("db.password", "${secrets:db/password}"));
            var provider = ConfigurationProvider.withSecretResolution(ConfigurationProvider.configurationProvider(source),
                path -> Promise.resolved(Result.success("s3cret"))).unwrap();

            var reloaded = (ConfigurationProvider) provider.reload().unwrap();

            assertThat(reloaded.getString("db.password").unwrap()).isEqualTo("s3cret");
        }

        @Test
        void reload_observesRotationRatherThanTheOldSnapshot() {
            var current = new AtomicReference<>("old");
            var source = new ReloadingSource(Map.of("db.password", "${secrets:db/password}"));
            var provider = ConfigurationProvider.withSecretResolution(ConfigurationProvider.configurationProvider(source),
                path -> Promise.resolved(Result.success(current.get()))).unwrap();

            current.set("rotated");
            var reloaded = (ConfigurationProvider) provider.reload().unwrap();

            assertThat(provider.getString("db.password").unwrap()).isEqualTo("old");
            assertThat(reloaded.getString("db.password").unwrap()).isEqualTo("rotated");
        }

        @Test
        void reload_repeatedReloadKeepsTheDecorator() {
            var source = new ReloadingSource(Map.of("db.password", "${secrets:db/password}"));
            ConfigSource current = ConfigurationProvider.withSecretResolution(ConfigurationProvider.configurationProvider(source),
                path -> Promise.resolved(Result.success("s3cret"))).unwrap();

            for (var i = 0; i < 3; i++) {
                current = current.reload().unwrap();
            }

            assertThat(current.getString("db.password").unwrap()).isEqualTo("s3cret");
        }

        @Test
        void reload_resolutionFailure_failsNamingKeyAndPathWithoutTheSecret() {
            var failing = new AtomicReference<>(false);
            var source = new ReloadingSource(Map.of("db.password", "${secrets:db/password}"));
            var provider = ConfigurationProvider.withSecretResolution(ConfigurationProvider.configurationProvider(source),
                path -> failing.get()
                        ? Promise.resolved(Result.failure(Causes.cause("vault down")))
                        : Promise.resolved(Result.success("s3cret"))).unwrap();

            failing.set(true);
            var reloaded = provider.reload();

            assertThat(reloaded.isFailure()).isTrue();
            reloaded.onFailure(cause -> {
                assertThat(cause).isInstanceOf(ConfigError.SecretResolutionFailed.class);
                assertThat(((ConfigError.SecretResolutionFailed) cause).key()).isEqualTo("db.password");
                assertThat(cause.message()).doesNotContain("s3cret");
            });
        }
    }

    /// A source whose reload yields a fresh copy of its raw (placeholder-bearing) values.
    private record ReloadingSource(Map<String, String> values) implements ConfigSource {
        @Override
        public Option<String> getString(String key) {
            return Option.option(values.get(key));
        }

        @Override
        public Set<String> keys() {
            return values.keySet();
        }

        @Override
        public Map<String, String> asMap() {
            return values;
        }

        @Override
        public String name() {
            return "reloading";
        }

        @Override
        public Result<ConfigSource> reload() {
            return Result.success(new ReloadingSource(values));
        }
    }

    private static ConfigurationProvider providerWith(Map<String, String> values) {
        return ConfigurationProvider.configurationProvider(
            MapConfigSource.mapConfigSource("test", values).unwrap());
    }
}
