package org.pragmatica.config;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.config.source.MapConfigSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.config.ProviderBasedConfigService.providerBasedConfigService;
import static org.pragmatica.config.ProviderBasedConfigService.toSnakeCase;

class ProviderBasedConfigServiceTest {

    private static ConfigService serviceFrom(Map<String, String> values) {
        var source = MapConfigSource.mapConfigSource("test-source", values).unwrap();
        var provider = ConfigurationProvider.builder().withSource(source).build();
        return providerBasedConfigService(provider);
    }

    // --- Test records ---

    record SimpleConfig(String name, int port, boolean enabled) {}

    record TimeoutConfig(Duration connectionTimeout, Duration idleTimeout) {}

    record PropsConfig(String name, Map<String, String> properties) {}

    record InnerConfig(int minConnections, int maxConnections) {
        public static final InnerConfig DEFAULT = new InnerConfig(2, 10);
    }

    record OuterConfig(String name, InnerConfig innerConfig) {}

    record OptionalConfig(String name, Option<String> description) {}

    record OptionalDurationConfig(String name, Option<Duration> timeout) {}

    enum DbType { H2, POSTGRESQL, MYSQL }

    record EnumConfig(String name, DbType type) {}

    record ValidatedConfig(String host, int port) {
        private static final org.pragmatica.lang.Cause INVALID_PORT = Causes.cause("Port must be between 1 and 65535");

        public static Result<ValidatedConfig> validatedConfig(String host, int port) {
            if (port < 1 || port > 65535) {
                return INVALID_PORT.result();
            }
            return Result.success(new ValidatedConfig(host, port));
        }
    }

    // --- Tests ---

    @Nested
    class ToSnakeCase {

        @Test
        void toSnakeCase_camelCase_convertsCorrectly() {
            assertThat(toSnakeCase("jdbcUrl")).isEqualTo("jdbc_url");
        }

        @Test
        void toSnakeCase_twoWords_convertsCorrectly() {
            assertThat(toSnakeCase("poolConfig")).isEqualTo("pool_config");
        }

        @Test
        void toSnakeCase_threeWords_convertsCorrectly() {
            assertThat(toSnakeCase("minConnections")).isEqualTo("min_connections");
        }

        @Test
        void toSnakeCase_singleWord_unchanged() {
            assertThat(toSnakeCase("simple")).isEqualTo("simple");
        }

        @Test
        void toSnakeCase_emptyString_unchanged() {
            assertThat(toSnakeCase("")).isEqualTo("");
        }

        @Test
        void toSnakeCase_consecutiveUppercase_splitsSeparately() {
            assertThat(toSnakeCase("parseHTML")).isEqualTo("parse_h_t_m_l");
        }
    }

    @Nested
    class SimpleRecordBinding {

        @Test
        void config_simpleRecord_bindsAllFields() {
            var service = serviceFrom(Map.of(
                "test.name", "myapp",
                "test.port", "8080",
                "test.enabled", "true"
            ));

            var result = service.config("test", SimpleConfig.class);

            assertThat(result.isSuccess()).isTrue();
            var config = result.unwrap();
            assertThat(config.name()).isEqualTo("myapp");
            assertThat(config.port()).isEqualTo(8080);
            assertThat(config.enabled()).isTrue();
        }
    }

    @Nested
    class DurationFieldBinding {

        @Test
        void config_durationFields_bindsFromHumanFormat() {
            var service = serviceFrom(Map.of(
                "test.connection_timeout", "30s",
                "test.idle_timeout", "10m"
            ));

            var result = service.config("test", TimeoutConfig.class);

            assertThat(result.isSuccess()).isTrue();
            var config = result.unwrap();
            assertThat(config.connectionTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(config.idleTimeout()).isEqualTo(Duration.ofMinutes(10));
        }
    }

    @Nested
    class MapFieldBinding {

        @Test
        void config_mapField_collectsSubKeys() {
            var service = serviceFrom(Map.of(
                "test.name", "db",
                "test.properties.key1", "val1",
                "test.properties.key2", "val2"
            ));

            var result = service.config("test", PropsConfig.class);

            assertThat(result.isSuccess()).isTrue();
            var config = result.unwrap();
            assertThat(config.name()).isEqualTo("db");
            assertThat(config.properties()).hasSize(2);
            assertThat(config.properties()).containsEntry("key1", "val1");
            assertThat(config.properties()).containsEntry("key2", "val2");
        }

        @Test
        void config_mapField_emptyWhenNoSubKeys() {
            var service = serviceFrom(Map.of(
                "test.name", "db"
            ));

            var result = service.config("test", PropsConfig.class);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.unwrap().properties()).isEmpty();
        }
    }

    @Nested
    class NestedRecordBinding {

        @Test
        void config_nestedRecord_usesDefaultWhenAbsent() {
            var service = serviceFrom(Map.of(
                "test.name", "myapp"
            ));

            var result = service.config("test", OuterConfig.class);

            assertThat(result.isSuccess()).isTrue();
            var config = result.unwrap();
            assertThat(config.name()).isEqualTo("myapp");
            assertThat(config.innerConfig()).isEqualTo(InnerConfig.DEFAULT);
            assertThat(config.innerConfig().minConnections()).isEqualTo(2);
            assertThat(config.innerConfig().maxConnections()).isEqualTo(10);
        }

        @Test
        void config_nestedRecord_bindsExplicitValues() {
            var service = serviceFrom(Map.of(
                "test.name", "myapp",
                "test.inner_config.min_connections", "5",
                "test.inner_config.max_connections", "20"
            ));

            var result = service.config("test", OuterConfig.class);

            assertThat(result.isSuccess()).isTrue();
            var config = result.unwrap();
            assertThat(config.name()).isEqualTo("myapp");
            assertThat(config.innerConfig().minConnections()).isEqualTo(5);
            assertThat(config.innerConfig().maxConnections()).isEqualTo(20);
        }
    }

    @Nested
    class PartialNestedRecordBinding {

        @Test
        void config_partialNestedRecord_mergesWithDefault() {
            var service = serviceFrom(Map.of(
                "test.name", "myapp",
                "test.inner_config.max_connections", "50"
            ));

            var result = service.config("test", OuterConfig.class);

            assertThat(result.isSuccess()).isTrue();
            var config = result.unwrap();
            assertThat(config.name()).isEqualTo("myapp");
            assertThat(config.innerConfig().maxConnections()).isEqualTo(50);
            assertThat(config.innerConfig().minConnections()).isEqualTo(2);
        }

        @Test
        void config_partialNestedRecord_overridesOnlySpecifiedFields() {
            var service = serviceFrom(Map.of(
                "test.name", "myapp",
                "test.inner_config.min_connections", "8"
            ));

            var result = service.config("test", OuterConfig.class);

            assertThat(result.isSuccess()).isTrue();
            var config = result.unwrap();
            assertThat(config.innerConfig().minConnections()).isEqualTo(8);
            assertThat(config.innerConfig().maxConnections()).isEqualTo(10);
        }
    }

    @Nested
    class OptionFieldBinding {

        @Test
        void config_optionalString_presentWhenValueExists() {
            var service = serviceFrom(Map.of(
                "test.name", "myapp",
                "test.description", "A test application"
            ));

            var result = service.config("test", OptionalConfig.class);

            assertThat(result.isSuccess()).isTrue();
            var config = result.unwrap();
            assertThat(config.name()).isEqualTo("myapp");
            assertThat(config.description().isPresent()).isTrue();
            assertThat(config.description().unwrap()).isEqualTo("A test application");
        }

        @Test
        void config_optionalString_noneWhenValueAbsent() {
            var service = serviceFrom(Map.of(
                "test.name", "myapp"
            ));

            var result = service.config("test", OptionalConfig.class);

            assertThat(result.isSuccess()).isTrue();
            var config = result.unwrap();
            assertThat(config.name()).isEqualTo("myapp");
            assertThat(config.description().isEmpty()).isTrue();
        }
    }

    @Nested
    class OptionDurationBinding {

        @Test
        void config_optionalDuration_presentWhenValueExists() {
            var service = serviceFrom(Map.of(
                "test.name", "myapp",
                "test.timeout", "30s"
            ));

            var result = service.config("test", OptionalDurationConfig.class);

            assertThat(result.isSuccess()).isTrue();
            var config = result.unwrap();
            assertThat(config.timeout().isPresent()).isTrue();
            assertThat(config.timeout().unwrap()).isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        void config_optionalDuration_noneWhenValueAbsent() {
            var service = serviceFrom(Map.of(
                "test.name", "myapp"
            ));

            var result = service.config("test", OptionalDurationConfig.class);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.unwrap().timeout().isEmpty()).isTrue();
        }
    }

    @Nested
    class EnumBinding {

        @Test
        void config_enumField_bindsFromString() {
            var service = serviceFrom(Map.of(
                "test.name", "primary",
                "test.type", "H2"
            ));

            var result = service.config("test", EnumConfig.class);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.unwrap().type()).isEqualTo(DbType.H2);
        }
    }

    @Nested
    class ErrorCases {

        @Test
        void config_nonexistentSection_returnsFailure() {
            var service = serviceFrom(Map.of(
                "test.name", "myapp"
            ));

            var result = service.config("nonexistent", SimpleConfig.class);

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void config_nonRecordClass_returnsFailure() {
            var service = serviceFrom(Map.of(
                "test.value", "something"
            ));

            var result = service.config("test", String.class);

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void config_missingRequiredField_returnsFailure() {
            var service = serviceFrom(Map.of(
                "test.name", "myapp"
                // missing "test.port" and "test.enabled"
            ));

            var result = service.config("test", SimpleConfig.class);

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void config_invalidEnumValue_returnsFailure() {
            var service = serviceFrom(Map.of(
                "test.name", "primary",
                "test.type", "INVALID_DB"
            ));

            var result = service.config("test", EnumConfig.class);

            assertThat(result.isFailure()).isTrue();
        }
    }

    @Nested
    class FactoryMethodBinding {

        @Test
        void config_validatedRecord_usesFactoryMethod() {
            var service = serviceFrom(Map.of(
                "test.host", "localhost",
                "test.port", "8080"
            ));

            var result = service.config("test", ValidatedConfig.class);

            assertThat(result.isSuccess()).isTrue();
            var config = result.unwrap();
            assertThat(config.host()).isEqualTo("localhost");
            assertThat(config.port()).isEqualTo(8080);
        }

        @Test
        void config_validatedRecord_returnsFailureOnInvalidPort() {
            var service = serviceFrom(Map.of(
                "test.host", "localhost",
                "test.port", "99999"
            ));

            var result = service.config("test", ValidatedConfig.class);

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("Port must be between 1 and 65535"));
        }

        @Test
        void config_simpleRecord_fallsBackToConstructor() {
            var service = serviceFrom(Map.of(
                "test.name", "myapp",
                "test.port", "8080",
                "test.enabled", "true"
            ));

            var result = service.config("test", SimpleConfig.class);

            assertThat(result.isSuccess()).isTrue();
        }
    }
}
