// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;

import static org.assertj.core.api.Assertions.assertThat;

/// #1653: [ClusterEvent#withDetail] is written per variant (no reflection in production). The compiler forces each
/// closed variant to implement it; this test pins that each implementation is RIGHT: it adds the detail, keeps the
/// existing details, and keeps every other component. Reflection here only enumerates and builds the variants, so a
/// variant added later is covered without editing this test.
class ClusterEventWithDetailTest {
    private static final HlcTimestamp AT = new HlcTimestamp(HlcTimestamp.pack(1_000L, 3), new NodeId("with-detail"));
    private static final ResourceAddress ADDRESS = ResourceAddress.resourceAddress("ns", "events", "1.2.3")
                                                                  .unwrap();

    @Test
    void withDetail_everyClosedVariant_addsTheDetail_andKeepsEverythingElse() {
        var variants = closedVariants();

        assertThat(variants).as("control: the closed variants were enumerated").hasSizeGreaterThan(30);

        for (var variant : variants) {
            var original = sample(variant);
            var enriched = original.withDetail(ClusterEventIdentity.EVENT_ID, "incarnation:7");

            assertThat(enriched).as(variant.getSimpleName() + " keeps its type").isInstanceOf(variant);
            assertThat(enriched.details()).as(variant.getSimpleName() + " details")
                                          .containsEntry(ClusterEventIdentity.EVENT_ID, "incarnation:7")
                                          .containsEntry("kept", "yes");
            assertThat(enriched.at()).as(variant.getSimpleName() + " at").isEqualTo(original.at());
            assertThat(enriched.severity()).as(variant.getSimpleName() + " severity").isEqualTo(original.severity());
            assertThat(enriched.summary()).as(variant.getSimpleName() + " summary").isEqualTo(original.summary());
            assertThat(enriched.withDetail(ClusterEventIdentity.EVENT_ID, "incarnation:7"))
                .as(variant.getSimpleName() + ": only details changed")
                .isEqualTo(sampleWithDetails(variant,
                                             Map.of("kept", "yes", ClusterEventIdentity.EVENT_ID, "incarnation:7")));
        }
    }

    /// Every permitted subclass except the `ExtendedEvent` hatch must be a record; a non-record variant would
    /// otherwise escape this test silently.
    @Test
    void permittedSubclasses_areRecords_exceptTheExtensionHatch() {
        assertThat(Arrays.stream(ClusterEvent.class.getPermittedSubclasses())
                         .filter(variant -> variant != ExtendedEvent.class)
                         .filter(variant -> !variant.isRecord())
                         .toList()).isEmpty();
    }

    private static List<Class<?>> closedVariants() {
        return Arrays.stream(ClusterEvent.class.getPermittedSubclasses())
                     .filter(Class::isRecord)
                     .toList();
    }

    private static ClusterEvent sample(Class<?> variant) {
        return sampleWithDetails(variant, Map.of("kept", "yes"));
    }

    private static ClusterEvent sampleWithDetails(Class<?> variant, Map<String, String> details) {
        var components = variant.getRecordComponents();
        var types = Arrays.stream(components).map(component -> component.getType()).toArray(Class<?>[]::new);
        var arguments = Arrays.stream(types).map(type -> argumentFor(type, details)).toArray();

        try {
            return (ClusterEvent) variant.getDeclaredConstructor(types).newInstance(arguments);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot build " + variant.getSimpleName(), e);
        }
    }

    private static Object argumentFor(Class<?> type, Map<String, String> details) {
        if (type == HlcTimestamp.class) {
            return AT;
        }
        if (type == ClusterEvent.Severity.class) {
            return ClusterEvent.Severity.WARNING;
        }
        if (type == String.class) {
            return "summary";
        }
        if (type == Map.class) {
            return details;
        }
        if (type == ResourceAddress.class) {
            return ADDRESS;
        }
        throw new AssertionError("no sample for component type " + type);
    }
}
