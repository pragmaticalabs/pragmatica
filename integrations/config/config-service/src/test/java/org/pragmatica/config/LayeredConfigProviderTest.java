package org.pragmatica.config;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.config.source.MapConfigSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LayeredConfigProviderTest {

    @Nested
    class Precedence {

        @Test
        void getString_leftLayerWinsOnConflict() {
            var top = providerFor("top", Map.of("key", "top-value", "shared", "from-top"));
            var bottom = providerFor("bottom", Map.of("key", "bottom-value", "only-bottom", "x"));

            var layered = LayeredConfigProvider.layered(List.of(top, bottom));

            assertThat(layered.getString("key").unwrap()).isEqualTo("top-value");
            assertThat(layered.getString("shared").unwrap()).isEqualTo("from-top");
            assertThat(layered.getString("only-bottom").unwrap()).isEqualTo("x");
            assertThat(layered.getString("missing").isPresent()).isFalse();
        }

        @Test
        void getString_fallsThroughWhenTopHasNoEntry() {
            var top = providerFor("top", Map.of("a", "from-top"));
            var middle = providerFor("middle", Map.of("b", "from-middle"));
            var bottom = providerFor("bottom", Map.of("c", "from-bottom"));

            var layered = LayeredConfigProvider.layered(List.of(top, middle, bottom));

            assertThat(layered.getString("a").unwrap()).isEqualTo("from-top");
            assertThat(layered.getString("b").unwrap()).isEqualTo("from-middle");
            assertThat(layered.getString("c").unwrap()).isEqualTo("from-bottom");
        }
    }

    @Nested
    class KeysUnion {

        @Test
        void keys_returnsUnionOfAllLayers() {
            var top = providerFor("top", Map.of("a", "1", "b", "2"));
            var bottom = providerFor("bottom", Map.of("b", "x", "c", "3"));

            var layered = LayeredConfigProvider.layered(List.of(top, bottom));

            assertThat(layered.keys()).containsExactlyInAnyOrder("a", "b", "c");
        }
    }

    @Nested
    class AsMapMerge {

        @Test
        void asMap_topLayerWinsOnConflict() {
            var top = providerFor("top", Map.of("key", "top-value", "a", "1"));
            var bottom = providerFor("bottom", Map.of("key", "bottom-value", "b", "2"));

            var layered = LayeredConfigProvider.layered(List.of(top, bottom));

            var merged = layered.asMap();

            assertThat(merged).containsEntry("key", "top-value")
                              .containsEntry("a", "1")
                              .containsEntry("b", "2")
                              .hasSize(3);
        }
    }

    @Nested
    class Reload {

        @Test
        void reload_propagatesToAllLayers() {
            var top = providerFor("top", Map.of("a", "1"));
            var bottom = providerFor("bottom", Map.of("b", "2"));

            var layered = LayeredConfigProvider.layered(List.of(top, bottom));

            var reloaded = layered.reload();

            assertThat(reloaded.isSuccess()).isTrue();
            assertThat(((ConfigurationProvider) reloaded.unwrap()).getString("a").unwrap()).isEqualTo("1");
            assertThat(((ConfigurationProvider) reloaded.unwrap()).getString("b").unwrap()).isEqualTo("2");
        }
    }

    @Nested
    class Naming {

        @Test
        void name_describesLayerComposition() {
            var top = IntrinsicConfigProvider.intrinsicConfigProvider("top", Map.of());
            var bottom = IntrinsicConfigProvider.intrinsicConfigProvider("bottom", Map.of());

            var layered = LayeredConfigProvider.layered(List.of(top, bottom));

            assertThat(layered.name()).contains("LayeredConfigProvider[")
                                      .contains("top")
                                      .contains("bottom");
        }
    }

    @Nested
    class Composition {

        @Test
        void layered_isAssociative() {
            var l1 = providerFor("l1", Map.of("a", "1"));
            var l2 = providerFor("l2", Map.of("b", "2"));
            var l3 = providerFor("l3", Map.of("c", "3"));

            // (l1 ⊕ l2) ⊕ l3
            var leftAssoc = LayeredConfigProvider.layered(List.of(LayeredConfigProvider.layered(List.of(l1, l2)), l3));
            // l1 ⊕ (l2 ⊕ l3)
            var rightAssoc = LayeredConfigProvider.layered(List.of(l1, LayeredConfigProvider.layered(List.of(l2, l3))));

            assertThat(leftAssoc.getString("a").unwrap()).isEqualTo("1");
            assertThat(leftAssoc.getString("b").unwrap()).isEqualTo("2");
            assertThat(leftAssoc.getString("c").unwrap()).isEqualTo("3");

            assertThat(rightAssoc.getString("a").unwrap()).isEqualTo("1");
            assertThat(rightAssoc.getString("b").unwrap()).isEqualTo("2");
            assertThat(rightAssoc.getString("c").unwrap()).isEqualTo("3");
        }
    }

    private static ConfigurationProvider providerFor(String name, Map<String, String> values) {
        var source = MapConfigSource.mapConfigSource(name, values).unwrap();
        return ConfigurationProvider.configurationProvider(source);
    }
}
