package org.pragmatica.jbct.lint.layer;

import java.util.List;

import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    class GlobSemantics {
        private static LayerClassifier domainClassifier(String... globs) {
            return LayerClassifier.from(LayerConfig.layerConfig(List.of(globs),
                                                                List.of(),
                                                                List.of(),
                                                                List.of(),
                                                                List.of()));
        }

        @Test
        void layerOf_anyDepthGlob_classifiesBarePackage() {
            assertEquals(Option.some(Layer.DOMAIN),
                         domainClassifier("com.example.core.**").layerOf("com.example.core"));
        }

        @Test
        void layerOf_anyDepthGlob_classifiesSubpackage() {
            var classifier = domainClassifier("com.example.core.**");

            assertEquals(Option.some(Layer.DOMAIN), classifier.layerOf("com.example.core.user"));
            assertEquals(Option.some(Layer.DOMAIN), classifier.layerOf("com.example.core.user.internal"));
        }

        @Test
        void layerOf_anyDepthGlob_leavesSiblingWithSharedPrefixUnclassified() {
            var classifier = domainClassifier("com.example.core.**");

            assertEquals(Option.none(), classifier.layerOf("com.example.corex"));
            assertEquals(Option.none(), classifier.layerOf("com.example.corex.user"));
        }

        @Test
        void layerOf_anyDepthGlob_leavesParentPackageUnclassified() {
            assertEquals(Option.none(), domainClassifier("com.example.core.**").layerOf("com.example"));
        }

        @Test
        void layerOf_singleStarGlob_matchesOneSegmentOnly() {
            var classifier = domainClassifier("com.example.*");

            assertEquals(Option.some(Layer.DOMAIN), classifier.layerOf("com.example.core"));
            assertEquals(Option.none(), classifier.layerOf("com.example.core.user"));
            assertEquals(Option.none(), classifier.layerOf("com.example"));
        }

        @Test
        void layerOf_middleAnyDepthGlob_spansAnyDepthIncludingNone() {
            var classifier = domainClassifier("com.**.model");

            assertEquals(Option.some(Layer.DOMAIN), classifier.layerOf("com.model"));
            assertEquals(Option.some(Layer.DOMAIN), classifier.layerOf("com.example.model"));
            assertEquals(Option.some(Layer.DOMAIN), classifier.layerOf("com.example.core.model"));
            assertEquals(Option.none(), classifier.layerOf("com.example.core"));
        }

        @Test
        void layerOf_leadingAnyDepthGlob_classifiesBareAndNestedPackage() {
            var classifier = domainClassifier("**.model");

            assertEquals(Option.some(Layer.DOMAIN), classifier.layerOf("model"));
            assertEquals(Option.some(Layer.DOMAIN), classifier.layerOf("com.example.model"));
            assertEquals(Option.none(), classifier.layerOf("com.example.model.internal"));
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

        /// `**` spans zero segments, so a `**` slice glob also makes the grouping package itself a
        /// root — `slices` globs want `*`, as [LayerConfig] documents.
        @Test
        void sliceRootOf_anyDepthGlob_makesGroupingPackageTheRoot() {
            var config = LayerConfig.layerConfig(List.of(),
                                                 List.of(),
                                                 List.of(),
                                                 List.of(),
                                                 List.of("com.example.svc.**"));
            var classifier = LayerClassifier.from(config);

            assertEquals(Option.some("com.example.svc"),
                         classifier.sliceRootOf("com.example.svc.orders.internal.dao"));
        }
    }
}
