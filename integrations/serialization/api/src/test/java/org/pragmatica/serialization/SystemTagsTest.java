/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.serialization;

import java.util.HashMap;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/// Pins the invariants of the hand-assigned tag table. Every one of these is enforced at class-init
/// too; the tests exist because a class-init failure names one offender and stops, while these say
/// which PROPERTY broke — and because the table is edited by hand, which is the whole risk.
class SystemTagsTest {

    /// The table loads at all. Class-init runs [SystemTags#pin] and the duplicate-tag sweep, so a
    /// double-pinned name or a double-claimed tag throws here rather than at a node's first send.
    @Test
    void table_loadsWithoutDuplicateNamesOrTags() {
        assertFalse(SystemTags.TAGS.isEmpty());
    }

    /// #834: the three pins of the SchemaVersionValue change, asserted record class -> tag straight on the table. A reverted or dropped
    /// pin must fail HERE by assertion, not only at registry init: 1638 stays the old shape's tag (decode-only), the current shape and
    /// its reach enum sit in block 2984..2985 (RQ-TAGS-3).
    @Test
    void schemaVersionValuePins_areTheAssignedTags() {
        assertEquals(1638, SystemTags.tagFor("org.pragmatica.aether.slice.kvstore.LegacySchemaVersionValue"));
        assertEquals(2984, SystemTags.tagFor("org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue"));
        assertEquals(2985, SystemTags.tagFor("org.pragmatica.aether.slice.kvstore.AetherValue.SchemaReach"));
    }

    /// The disjointness the whole split rests on. A system tag that strayed into the user range would
    /// be reachable by a slice type's hash, which is the collision this design exists to make
    /// structurally impossible.
    @Test
    void everyTag_isInsideTheSystemRange() {
        SystemTags.TAGS.forEach((name, tag) -> assertTrue(tag >= 0 && tag <= SliceCodec.SYSTEM_TAG_MAX,
                                                          "%s is pinned to %d, outside [0, %d]".formatted(name,
                                                                                                          tag,
                                                                                                          SliceCodec.SYSTEM_TAG_MAX)));
    }

    /// `0..20` belong to the framework primitives (`SliceCodec.TAG_UNIT` … `TAG_MAP`), which are
    /// registered by [FrameworkCodecs] from constants and never appear in this table. A pin down there
    /// would collide with one of them, and the failure would be a mis-decoded primitive rather than a
    /// missing codec — silent, not loud.
    @Test
    void noTag_intrudesOnTheFrameworkPrimitives() {
        SystemTags.TAGS.forEach((name, tag) -> assertTrue(tag > SliceCodec.TAG_MAP,
                                                          "%s is pinned to %d, inside the framework primitive range [0, %d]".formatted(name,
                                                                                                                                       tag,
                                                                                                                                       SliceCodec.TAG_MAP)));
    }

    /// Restates the duplicate-tag sweep as a property rather than a first-offender throw: a tag is a
    /// wire contract, and two types answering to one number is undiagnosable corruption on the wire
    /// rather than a clean failure at either end.
    @Test
    void everyTag_isClaimedByExactlyOneType() {
        var byTag = new HashMap<Integer, String>();

        SystemTags.TAGS.forEach((name, tag) -> {
            var previous = byTag.put(tag, name);

            assertTrue(previous == null,
                       "Tag %d is claimed by both %s and %s".formatted(tag, previous, name));
        });

        assertEquals(SystemTags.TAGS.size(), byTag.size());
    }

    /// The key is the name as the annotation processor spells it. The processor emits a nested type as
    /// `Outer.Inner`, so a key written in `Class#getName` form (`Outer$Inner`) would never be consulted
    /// and the type would quietly take a hashed user tag instead.
    @Test
    void everyKey_usesProcessorDottedFormNotBinaryForm() {
        SystemTags.TAGS.keySet()
                       .forEach(name -> assertFalse(name.indexOf('$') >= 0,
                                                    "%s is in binary form; the processor emits nested types dot-separated".formatted(name)));
    }

    /// A name outside the table must be distinguishable from one pinned to tag 0 — hence a sentinel
    /// rather than a boxed null or an [java.util.Optional].
    @Test
    void tagFor_unknownName_returnsNotPinned() {
        assertEquals(SystemTags.NOT_PINNED, SystemTags.tagFor("com.example.NeverPinned"));
    }

    /// #3530: the eight tags of the four deleted key types and their values. The classes are gone, so the pin is the only thing that
    /// keeps a later lane from reusing the number, and a persisted snapshot or backup naming one must still fail as an unknown tag. One
    /// dynamic test per tag, so dropping or re-pointing any single pin names that tag, and eight drops show eight reds.
    private static final List<RetiredPin> RETIRED_3530 = List.of(new RetiredPin(1098, "AetherKey.ClusterPhaseKey"),
                                                                  new RetiredPin(1104, "AetherKey.DrainDeadlineKey"),
                                                                  new RetiredPin(1111, "AetherKey.JoinDeadlineKey"),
                                                                  new RetiredPin(1137, "AetherKey.WorkerSliceDirectiveKey"),
                                                                  new RetiredPin(1612, "AetherValue.ClusterPhaseValue"),
                                                                  new RetiredPin(1618, "AetherValue.DrainDeadlineValue"),
                                                                  new RetiredPin(1625, "AetherValue.JoinDeadlineValue"),
                                                                  new RetiredPin(1658, "AetherValue.WorkerSliceDirectiveValue"));

    @TestFactory
    Stream<DynamicTest> retiredPins_stayPinnedToTheirTagAndNoOtherTypeTakesIt() {
        return RETIRED_3530.stream()
                           .map(pin -> DynamicTest.dynamicTest("tag " + pin.tag() + " " + pin.name(),
                                                               () -> assertRetired(pin)));
    }

    private static void assertRetired(RetiredPin pin) {
        var fullName = "org.pragmatica.aether.slice.kvstore." + pin.name();

        assertEquals(pin.tag(),
                     SystemTags.tagFor(fullName),
                     "retired tag %d (%s) must stay pinned: dropping the pin frees the number for reuse".formatted(pin.tag(), fullName));
        assertEquals(1L,
                     SystemTags.TAGS.values().stream().filter(tag -> tag == pin.tag()).count(),
                     "retired tag %d must be claimed by %s alone, it was reused".formatted(pin.tag(), fullName));
    }

    private record RetiredPin(int tag, String name) {}
}
