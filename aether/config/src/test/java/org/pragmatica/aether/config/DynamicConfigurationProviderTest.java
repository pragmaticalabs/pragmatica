package org.pragmatica.aether.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.config.DynamicConfigurationProvider.dynamicConfigurationProvider;

class DynamicConfigurationProviderTest {

    private DynamicConfigurationProvider provider;

    @BeforeEach
    void setUp() {
        var base = ConfigurationProvider.builder()
                                        .withDefaults(Map.of(
                                            "database.host", "localhost",
                                            "database.port", "5432",
                                            "server.port", "8080"
                                        ))
                                        .build();
        provider = dynamicConfigurationProvider(base);
    }

    @Nested
    class GetString {

        @Test
        void getString_overlayValueShadowsBase() {
            provider.put("database.host", "prod-host");

            assertThat(provider.getString("database.host").unwrap()).isEqualTo("prod-host");
        }

        @Test
        void getString_fallsBackToBaseWhenNoOverlay() {
            assertThat(provider.getString("database.host").unwrap()).isEqualTo("localhost");
        }

        @Test
        void getString_returnsEmptyWhenNeitherHasKey() {
            assertThat(provider.getString("nonexistent.key").isEmpty()).isTrue();
        }
    }

    @Nested
    class PutAndRemove {

        @Test
        void put_addsToOverlay() {
            provider.put("new.key", "new-value");

            assertThat(provider.getString("new.key").unwrap()).isEqualTo("new-value");
        }

        @Test
        void remove_removesFromOverlay_fallsBackToBase() {
            provider.put("database.host", "prod-host");
            assertThat(provider.getString("database.host").unwrap()).isEqualTo("prod-host");

            provider.remove("database.host");
            assertThat(provider.getString("database.host").unwrap()).isEqualTo("localhost");
        }
    }

    @Nested
    class KeysAndMaps {

        @Test
        void keys_mergesBothSources() {
            provider.put("overlay.key", "value");

            var keys = provider.keys();
            assertThat(keys).contains("database.host", "database.port", "server.port", "overlay.key");
        }

        @Test
        void asMap_overlayWinsOnCollision() {
            provider.put("database.host", "prod-host");
            provider.put("extra.key", "extra-value");

            var map = provider.asMap();
            assertThat(map).containsEntry("database.host", "prod-host")
                           .containsEntry("database.port", "5432")
                           .containsEntry("server.port", "8080")
                           .containsEntry("extra.key", "extra-value");
        }

        @Test
        void overlayMap_returnsOnlyOverrides() {
            provider.put("database.host", "prod-host");
            provider.put("extra.key", "extra-value");

            var overlayMap = provider.overlayMap();
            assertThat(overlayMap).hasSize(2)
                                  .containsEntry("database.host", "prod-host")
                                  .containsEntry("extra.key", "extra-value");
            assertThat(overlayMap).doesNotContainKey("database.port");
        }
    }

    @Nested
    class Metadata {

        @Test
        void name_includesBaseName() {
            assertThat(provider.name()).startsWith("DynamicConfigurationProvider[");
        }

        @Test
        void sources_delegatesToBase() {
            assertThat(provider.sources()).isNotEmpty();
        }

        @Test
        void reload_succeeds() {
            var result = provider.reload();
            assertThat(result.isSuccess()).isTrue();
        }
    }
}
