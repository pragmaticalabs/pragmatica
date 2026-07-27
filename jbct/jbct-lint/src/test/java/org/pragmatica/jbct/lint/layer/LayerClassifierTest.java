package org.pragmatica.jbct.lint.layer;

import java.util.List;

import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Unit tests for the #452 package-classification engine.
class LayerClassifierTest {
    private final LayerClassifier conventions = LayerClassifier.conventionsOnly();

    @Nested
    class ConventionLayers {
        @Test
        void layerOf_domainSegment_isDomain() {
            assertEquals(Option.some(Layer.DOMAIN), conventions.layerOf("com.example.domain.user"));
        }

        @Test
        void layerOf_usecaseSegment_isApplication() {
            assertEquals(Option.some(Layer.APPLICATION), conventions.layerOf("com.example.usecase.registeruser"));
        }

        @Test
        void layerOf_applicationSegment_isApplication() {
            assertEquals(Option.some(Layer.APPLICATION), conventions.layerOf("com.example.application.register"));
        }

        @Test
        void layerOf_adapterSegment_isAdapter() {
            assertEquals(Option.some(Layer.ADAPTER), conventions.layerOf("com.example.adapter.persistence"));
        }

        @Test
        void layerOf_integrationSegment_isAdapter() {
            assertEquals(Option.some(Layer.ADAPTER), conventions.layerOf("com.example.integration.db"));
        }

        @Test
        void layerOf_bootstrapSegment_isBootstrap() {
            assertEquals(Option.some(Layer.BOOTSTRAP), conventions.layerOf("com.example.bootstrap"));
        }

        @Test
        void layerOf_deepestSegmentWins_sliceDomainReadsAsDomain() {
            assertEquals(Option.some(Layer.DOMAIN),
                         conventions.layerOf("com.example.usecase.registeruser.domain"));
        }

        @Test
        void layerOf_noLayerKeyword_isUnclassified() {
            assertEquals(Option.none(), conventions.layerOf("com.example.util"));
        }
    }

    @Nested
    class Zones {
        @Test
        void zoneOf_adapter_isAdapterBoundary() {
            assertEquals(Option.some(Zone.ADAPTER_BOUNDARY), conventions.zoneOf("com.example.adapter.persistence"));
        }

        @Test
        void zoneOf_domain_isBusiness() {
            assertEquals(Option.some(Zone.BUSINESS), conventions.zoneOf("com.example.domain.user"));
        }

        @Test
        void zoneOf_unclassified_isNone() {
            assertEquals(Option.none(), conventions.zoneOf("com.example.util"));
        }
    }

    @Nested
    class ExplicitOverride {
        @Test
        void explicitGlob_overridesConvention() {
            var config = LayerConfig.layerConfig(List.of("com.example.core.**"),
                                                 List.of(),
                                                 List.of(),
                                                 List.of(),
                                                 List.of());
            var classifier = LayerClassifier.from(config);

            assertEquals(Option.some(Layer.DOMAIN), classifier.layerOf("com.example.core.user"));
        }

        @Test
        void explicitGlob_takesPrecedenceOverConventionKeyword() {
            var config = LayerConfig.layerConfig(List.of(),
                                                 List.of(),
                                                 List.of("com.example.domain.legacy.**"),
                                                 List.of(),
                                                 List.of());
            var classifier = LayerClassifier.from(config);

            assertEquals(Option.some(Layer.ADAPTER), classifier.layerOf("com.example.domain.legacy.db"));
        }

        @Test
        void mostSpecificGlob_winsAcrossLayerLists() {
            var config = LayerConfig.layerConfig(List.of("com.x.**"),
                                                 List.of(),
                                                 List.of("com.x.adapter.**"),
                                                 List.of(),
                                                 List.of());
            var classifier = LayerClassifier.from(config);

            assertEquals(Option.some(Layer.ADAPTER), classifier.layerOf("com.x.adapter.db"));
            assertEquals(Option.some(Layer.DOMAIN), classifier.layerOf("com.x.core.User"));
        }
    }

    @Nested
    class Provenance {
        @Test
        void isExplicitlyConfigured_conventionsOnly_isFalse() {
            assertFalse(conventions.isExplicitlyConfigured());
        }

        @Test
        void isExplicitlyConfigured_emptyConfig_isFalse() {
            assertFalse(LayerClassifier.from(LayerConfig.DEFAULT)
                                       .isExplicitlyConfigured());
        }

        @Test
        void isExplicitlyConfigured_layerGlob_isTrue() {
            var config = LayerConfig.layerConfig(List.of(),
                                                 List.of(),
                                                 List.of("com.example.cloud.**"),
                                                 List.of(),
                                                 List.of());

            assertTrue(LayerClassifier.from(config)
                                      .isExplicitlyConfigured());
        }

        @Test
        void isExplicitlyConfigured_sliceGlobOnly_isTrue() {
            var config = LayerConfig.layerConfig(List.of(),
                                                 List.of(),
                                                 List.of(),
                                                 List.of(),
                                                 List.of("com.example.svc.*"));

            assertTrue(LayerClassifier.from(config)
                                      .isExplicitlyConfigured());
        }
    }

    @Nested
    class SliceRoots {
        @Test
        void sliceRootOf_conventionUsecaseChild_isRoot() {
            assertEquals(Option.some("com.example.usecase.registeruser"),
                         conventions.sliceRootOf("com.example.usecase.registeruser"));
        }

        @Test
        void sliceRootOf_conventionInternal_resolvesToRoot() {
            assertEquals(Option.some("com.example.usecase.registeruser"),
                         conventions.sliceRootOf("com.example.usecase.registeruser.internal.token"));
        }

        @Test
        void sliceRootOf_usecaseGroupingPackage_isNone() {
            assertEquals(Option.none(), conventions.sliceRootOf("com.example.usecase"));
        }

        @Test
        void sliceRootOf_noSliceContext_isNone() {
            assertEquals(Option.none(), conventions.sliceRootOf("com.example.domain.user"));
        }

        @Test
        void sliceRootOf_explicitGlob_shortestPrefixIsRoot() {
            var config = LayerConfig.layerConfig(List.of(),
                                                 List.of(),
                                                 List.of(),
                                                 List.of(),
                                                 List.of("com.example.svc.*"));
            var classifier = LayerClassifier.from(config);

            assertEquals(Option.some("com.example.svc.orders"),
                         classifier.sliceRootOf("com.example.svc.orders.internal.dao"));
        }
    }
}
