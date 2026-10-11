package org.pragmatica.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.config.DynamicConfigurationProvider.dynamicConfigurationProvider;

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

    /// #1326 sibling: `reload()` reloaded the base, discarded the result and returned `this`, so the returned provider still read the PRE-reload base.
    @Nested
    class ReloadKeepsTheOverlayOverTheReloadedBase {
        @Test
        void reload_returnedProvider_readsTheReloadedBase() {
            var dynamic = dynamicConfigurationProvider(ConfigurationProvider.configurationProvider(new TwoGenerationSource(false)));

            var reloaded = (ConfigurationProvider) dynamic.reload().unwrap();

            assertThat(reloaded.getString("database.host").unwrap()).isEqualTo("reloaded-host");
        }

        @Test
        void reload_returnedProvider_sharesTheLiveOverlay() {
            var dynamic = dynamicConfigurationProvider(ConfigurationProvider.configurationProvider(new TwoGenerationSource(false)));

            dynamic.put("feature.flag", "on");
            var reloaded = (ConfigurationProvider) dynamic.reload().unwrap();
            dynamic.put("later.key", "seen");

            assertThat(reloaded.getString("feature.flag").unwrap()).isEqualTo("on");
            assertThat(reloaded.getString("later.key").unwrap()).as("an overlay write after the reload reaches the reloaded provider too").isEqualTo("seen");
        }
    }

    /// Serves `database.host=initial-host`, and `reloaded-host` from every reload.
    private record TwoGenerationSource(boolean reloaded) implements ConfigSource {
        @Override public Option<String> getString(String key) {return Option.option(asMap().get(key));}

        @Override public Set<String> keys() {return asMap().keySet();}

        @Override public Map<String, String> asMap() {return Map.of("database.host", reloaded ? "reloaded-host" : "initial-host");}

        @Override public String name() {return "two-generation-source";}

        @Override public Result<ConfigSource> reload() {return Result.success(new TwoGenerationSource(true));}
    }
}
