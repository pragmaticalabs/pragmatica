package org.pragmatica.aether.config;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.source.MapConfigSource;
import org.pragmatica.lang.Option;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.config.ProviderBasedConfigService.parseDuration;
import static org.pragmatica.aether.config.ProviderBasedConfigService.providerBasedConfigService;
import static org.pragmatica.aether.config.ProviderBasedConfigService.toSnakeCase;

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

    // --- Tests ---

    @Nested
    class ParseDuration {

        @Test
        void parseDuration_seconds_returnsDuration() {
            var result = parseDuration("30s");

            assertThat(result.isPresent()).isTrue();
            assertThat(result.unwrap()).isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        void parseDuration_minutes_returnsDuration() {
            var result = parseDuration("10m");

            assertThat(result.isPresent()).isTrue();
            assertThat(result.unwrap()).isEqualTo(Duration.ofMinutes(10));
        }

        @Test
        void parseDuration_hours_returnsDuration() {
            var result = parseDuration("1h");

            assertThat(result.isPresent()).isTrue();
            assertThat(result.unwrap()).isEqualTo(Duration.ofHours(1));
        }

        @Test
        void parseDuration_millis_returnsDuration() {
            var result = parseDuration("500ms");

            assertThat(result.isPresent()).isTrue();
            assertThat(result.unwrap()).isEqualTo(Duration.ofMillis(500));
        }

        @Test
        void parseDuration_days_returnsDuration() {
            var result = parseDuration("1d");

            assertThat(result.isPresent()).isTrue();
            assertThat(result.unwrap()).isEqualTo(Duration.ofDays(1));
        }

        @Test
        void parseDuration_iso8601_returnsDuration() {
            var result = parseDuration("PT30S");

            assertThat(result.isPresent()).isTrue();
            assertThat(result.unwrap()).isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        void parseDuration_emptyString_returnsNone() {
            assertThat(parseDuration("").isEmpty()).isTrue();
        }

        @Test
        void parseDuration_whitespaceOnly_returnsNone() {
            assertThat(parseDuration("   ").isEmpty()).isTrue();
        }

        @Test
        void parseDuration_invalidText_returnsNone() {
            assertThat(parseDuration("abc").isEmpty()).isTrue();
        }

        @Test
        void parseDuration_invalidUnit_returnsNone() {
            assertThat(parseDuration("10x").isEmpty()).isTrue();
        }

        @Test
        void parseDuration_null_throwsNpe() {
            // parseDuration is only called via flatMap which guarantees non-null
            org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class, () -> parseDuration(null));
        }
    }

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
}
