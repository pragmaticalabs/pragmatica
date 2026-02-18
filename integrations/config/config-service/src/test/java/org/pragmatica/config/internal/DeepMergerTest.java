package org.pragmatica.config.internal;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeepMergerTest {

    @Nested
    class MergeFlat {

        @Test
        void merge_overridesValues_whenKeysConflict() {
            var base = Map.of("a", "1", "b", "2");
            var override = Map.of("b", "3", "c", "4");

            var result = DeepMerger.merge(base, override);

            assertThat(result).containsEntry("a", "1")
                              .containsEntry("b", "3")
                              .containsEntry("c", "4");
        }

        @Test
        void merge_preservesBaseValues_whenNoConflict() {
            var base = Map.of("a", "1", "b", "2");
            var override = Map.of("c", "3");

            var result = DeepMerger.merge(base, override);

            assertThat(result).containsEntry("a", "1")
                              .containsEntry("b", "2")
                              .containsEntry("c", "3");
        }

        @Test
        void merge_returnsEmpty_whenBothEmpty() {
            var base = Map.<String, String>of();
            var override = Map.<String, String>of();

            var result = DeepMerger.merge(base, override);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    class DeepMergeHierarchical {

        @Test
        void deepMerge_recursivelyMergesNestedMaps() {
            var base = new LinkedHashMap<String, Object>();
            var baseNested = new LinkedHashMap<String, Object>();
            baseNested.put("host", "localhost");
            baseNested.put("port", "5432");
            base.put("database", baseNested);

            var override = new LinkedHashMap<String, Object>();
            var overrideNested = new LinkedHashMap<String, Object>();
            overrideNested.put("port", "5433");
            overrideNested.put("name", "testdb");
            override.put("database", overrideNested);

            var result = DeepMerger.deepMerge(base, override);

            assertThat(result).containsKey("database");
            @SuppressWarnings("unchecked")
            var dbConfig = (Map<String, Object>) result.get("database");
            assertThat(dbConfig).containsEntry("host", "localhost")
                                .containsEntry("port", "5433")
                                .containsEntry("name", "testdb");
        }

        @Test
        void deepMerge_replacesNonMapWithMap() {
            var base = new LinkedHashMap<String, Object>();
            base.put("config", "simple-value");

            var override = new LinkedHashMap<String, Object>();
            var overrideNested = new LinkedHashMap<String, Object>();
            overrideNested.put("key", "value");
            override.put("config", overrideNested);

            var result = DeepMerger.deepMerge(base, override);

            assertThat(result).containsKey("config");
            assertThat(result.get("config")).isInstanceOf(Map.class);
        }

        @Test
        void deepMerge_replacesMapWithNonMap() {
            var base = new LinkedHashMap<String, Object>();
            var baseNested = new LinkedHashMap<String, Object>();
            baseNested.put("key", "value");
            base.put("config", baseNested);

            var override = new LinkedHashMap<String, Object>();
            override.put("config", "simple-value");

            var result = DeepMerger.deepMerge(base, override);

            assertThat(result).containsEntry("config", "simple-value");
        }
    }

    @Nested
    class ToHierarchical {

        @Test
        void toHierarchical_convertsDotNotation() {
            var flat = Map.of(
                "database.host", "localhost",
                "database.port", "5432",
                "server.port", "8080"
            );

            var result = DeepMerger.toHierarchical(flat);

            assertThat(result).containsKey("database");
            assertThat(result).containsKey("server");

            @SuppressWarnings("unchecked")
            var dbConfig = (Map<String, Object>) result.get("database");
            assertThat(dbConfig).containsEntry("host", "localhost")
                                .containsEntry("port", "5432");

            @SuppressWarnings("unchecked")
            var serverConfig = (Map<String, Object>) result.get("server");
            assertThat(serverConfig).containsEntry("port", "8080");
        }

        @Test
        void toHierarchical_handlesDeepNesting() {
            var flat = Map.of("a.b.c.d", "value");

            var result = DeepMerger.toHierarchical(flat);

            @SuppressWarnings("unchecked")
            var a = (Map<String, Object>) result.get("a");
            @SuppressWarnings("unchecked")
            var b = (Map<String, Object>) a.get("b");
            @SuppressWarnings("unchecked")
            var c = (Map<String, Object>) b.get("c");
            assertThat(c).containsEntry("d", "value");
        }

        @Test
        void toHierarchical_handlesRootLevelKeys() {
            var flat = Map.of("title", "My App");

            var result = DeepMerger.toHierarchical(flat);

            assertThat(result).containsEntry("title", "My App");
        }
    }

    @Nested
    class ToFlat {

        @Test
        void toFlat_convertsHierarchicalToDotNotation() {
            var hierarchical = new LinkedHashMap<String, Object>();
            var dbConfig = new LinkedHashMap<String, Object>();
            dbConfig.put("host", "localhost");
            dbConfig.put("port", 5432);
            hierarchical.put("database", dbConfig);
            hierarchical.put("title", "My App");

            var result = DeepMerger.toFlat(hierarchical);

            assertThat(result).containsEntry("database.host", "localhost")
                              .containsEntry("database.port", "5432")
                              .containsEntry("title", "My App");
        }

        @Test
        void toFlat_handlesDeepNesting() {
            var hierarchical = new LinkedHashMap<String, Object>();
            var a = new LinkedHashMap<String, Object>();
            var b = new LinkedHashMap<String, Object>();
            var c = new LinkedHashMap<String, Object>();
            c.put("d", "value");
            b.put("c", c);
            a.put("b", b);
            hierarchical.put("a", a);

            var result = DeepMerger.toFlat(hierarchical);

            assertThat(result).containsEntry("a.b.c.d", "value");
        }
    }

    @Nested
    class RoundTrip {

        @Test
        void roundTrip_preservesValues() {
            var original = Map.of(
                "database.host", "localhost",
                "database.port", "5432",
                "server.port", "8080",
                "title", "My App"
            );

            var hierarchical = DeepMerger.toHierarchical(original);
            var flat = DeepMerger.toFlat(hierarchical);

            assertThat(flat).containsAllEntriesOf(original);
        }
    }
}
