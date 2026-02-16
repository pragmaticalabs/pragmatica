package org.pragmatica.aether.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.source.MapConfigSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationProviderTest {

    @Nested
    class BasicMerging {

        @Test
        void builder_mergesMultipleSources() {
            var defaults = MapConfigSource.mapConfigSource("defaults",
                Map.of("database.host", "localhost", "database.port", "5432"), -1000).unwrap();
            var overrides = MapConfigSource.mapConfigSource("overrides",
                Map.of("database.port", "5433", "server.port", "8080"), 100).unwrap();

            var provider = ConfigurationProvider.builder()
                                                .withSource(defaults)
                                                .withSource(overrides)
                                                .build();

            assertThat(provider.getString("database.host").unwrap()).isEqualTo("localhost");
            assertThat(provider.getString("database.port").unwrap()).isEqualTo("5433");
            assertThat(provider.getString("server.port").unwrap()).isEqualTo("8080");
        }

        @Test
        void builder_higherPriorityWins() {
            var low = MapConfigSource.mapConfigSource("low", Map.of("key", "low-value"), 10).unwrap();
            var high = MapConfigSource.mapConfigSource("high", Map.of("key", "high-value"), 100).unwrap();

            var provider = ConfigurationProvider.builder()
                                                .withSource(low)
                                                .withSource(high)
                                                .build();

            assertThat(provider.getString("key").unwrap()).isEqualTo("high-value");
        }

        @Test
        void sources_returnsSortedByPriority() {
            var low = MapConfigSource.mapConfigSource("low", Map.of(), 10).unwrap();
            var medium = MapConfigSource.mapConfigSource("medium", Map.of(), 50).unwrap();
            var high = MapConfigSource.mapConfigSource("high", Map.of(), 100).unwrap();

            var provider = ConfigurationProvider.builder()
                                                .withSource(low)
                                                .withSource(high)
                                                .withSource(medium)
                                                .build();

            var sources = provider.sources();
            assertThat(sources.get(0).name()).isEqualTo("high");
            assertThat(sources.get(1).name()).isEqualTo("medium");
            assertThat(sources.get(2).name()).isEqualTo("low");
        }
    }

    @Nested
    class WithDefaults {

        @Test
        void withDefaults_hasLowestPriority() {
            var override = MapConfigSource.mapConfigSource("override", Map.of("key", "override-value"), 0).unwrap();

            var provider = ConfigurationProvider.builder()
                                                .withDefaults(Map.of("key", "default-value", "other", "value"))
                                                .withSource(override)
                                                .build();

            assertThat(provider.getString("key").unwrap()).isEqualTo("override-value");
            assertThat(provider.getString("other").unwrap()).isEqualTo("value");
        }
    }

    @Nested
    class WithSystemProperties {
        private static final String TEST_PREFIX = "configtest.";

        @BeforeEach
        void setUp() {
            System.setProperty(TEST_PREFIX + "prop", "sys-value");
        }

        @AfterEach
        void tearDown() {
            System.clearProperty(TEST_PREFIX + "prop");
        }

        @Test
        void withSystemProperties_addsSysPropsSource() {
            var provider = ConfigurationProvider.builder()
                                                .withDefaults(Map.of("prop", "default"))
                                                .withSystemProperties(TEST_PREFIX)
                                                .build();

            assertThat(provider.getString("prop").unwrap()).isEqualTo("sys-value");
        }
    }

    @Nested
    class SingleSource {

        @Test
        void configurationProvider_wrapsSource() {
            var source = MapConfigSource.mapConfigSource("single", Map.of("key", "value")).unwrap();

            var provider = ConfigurationProvider.configurationProvider(source);

            assertThat(provider.getString("key").unwrap()).isEqualTo("value");
            assertThat(provider.sources()).hasSize(1);
        }
    }

    @Nested
    class KeysAndMap {

        @Test
        void keys_returnsAllMergedKeys() {
            var source1 = MapConfigSource.mapConfigSource("s1", Map.of("a", "1"), 0).unwrap();
            var source2 = MapConfigSource.mapConfigSource("s2", Map.of("b", "2"), 0).unwrap();

            var provider = ConfigurationProvider.builder()
                                                .withSource(source1)
                                                .withSource(source2)
                                                .build();

            assertThat(provider.keys()).containsExactlyInAnyOrder("a", "b");
        }

        @Test
        void asMap_returnsMergedValues() {
            var source1 = MapConfigSource.mapConfigSource("s1", Map.of("a", "1"), 0).unwrap();
            var source2 = MapConfigSource.mapConfigSource("s2", Map.of("b", "2"), 0).unwrap();

            var provider = ConfigurationProvider.builder()
                                                .withSource(source1)
                                                .withSource(source2)
                                                .build();

            var map = provider.asMap();
            assertThat(map).containsEntry("a", "1")
                           .containsEntry("b", "2");
        }
    }

    @Nested
    class Reload {

        @Test
        void reload_returnsNewProvider() {
            var source = MapConfigSource.mapConfigSource("test", Map.of("key", "value")).unwrap();
            var provider = ConfigurationProvider.configurationProvider(source);

            var reloaded = provider.reload();

            assertThat(reloaded.isSuccess()).isTrue();
            assertThat(reloaded.unwrap().getString("key").unwrap()).isEqualTo("value");
        }
    }

    @Nested
    class Name {

        @Test
        void name_includesSourceCount() {
            var provider = ConfigurationProvider.builder()
                                                .withDefaults(Map.of())
                                                .withSource(MapConfigSource.mapConfigSource("test").unwrap())
                                                .build();

            assertThat(provider.name()).contains("2 sources");
        }
    }
}
