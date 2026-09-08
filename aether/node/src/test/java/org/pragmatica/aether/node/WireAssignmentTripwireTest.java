// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.worker.WorkerCodecs;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// #964: makes a change to the WIRE ASSIGNMENT — a type's tag, or an enum's constant ordering —
/// impossible to make by accident.
///
/// The failure this exists for wears the syntax of a one-line edit. Appending a constant to a `@Codec`
/// enum, or renaming a type so its hash-derived tag moves, changes what every other node in the
/// cluster decodes; nothing in the compiler, the test suite, or review says so. `SystemCodecPinningTest`
/// does NOT cover this and must not be cited for it: it asserts registry MEMBERSHIP and hot-tag
/// bounds, names no type, and pins no byte format at all.
///
/// **The set is DERIVED, not listed.** Every type comes from the live registries via
/// `SliceCodec.registeredTypes()`, so an enum somebody forgets to mention is still pinned. A
/// hand-maintained list would be silent about exactly the case a tripwire is for.
///
/// **This is a TRIPWIRE, not a gate, and that is the owner's ruling rather than an oversight.**
/// Pre-GA, tags and constants may be added AND REMOVED freely. So any drift fails here, you read the
/// baseline diff, and if the change was intended you re-record it — the point is that it appears in a
/// reviewable diff, not that it is forbidden.
///
/// **To make it a hard gate at GA, change the word `TRIPWIRE` to `GATE` on the `MODE` line below.**
/// That one word switches the assertion from "the assignment is exactly the baseline" to "every
/// baseline entry is still present with the same value" — additions stay legal, removals and
/// renumberings stop being baseline-able, which is the "add only, never replace or remove" discipline.
class WireAssignmentTripwireTest {
    private enum Mode {
        /// Pre-GA: any drift fails; re-record the baseline to accept it.
        TRIPWIRE,
        /// Post-GA: additions pass; removal or renumbering fails and cannot be re-recorded away.
        GATE
    }

    /// THE ONE-WORD SWITCH. See the class docstring.
    private static final Mode MODE = Mode.TRIPWIRE;

    private static final String BASELINE_RESOURCE = "/wire-assignment-baseline.txt";

    /// Held as a resource rather than as string literals in this file so that accepting an intended
    /// change is a diff a reviewer can read line by line, which is the whole mechanism.
    private static List<String> baseline() {
        try (var stream = WireAssignmentTripwireTest.class.getResourceAsStream(BASELINE_RESOURCE)) {
            assertTrue(stream != null, BASELINE_RESOURCE + " is missing — the tripwire has nothing to compare against");

            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines()
                                                                            .filter(line -> !line.isBlank())
                                                                            .filter(line -> !line.startsWith("#"))
                                                                            .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// The current assignment, rendered in the baseline's own format.
    ///
    /// `TAG` lines carry what a peer must agree with to route a message to the right codec at all.
    /// `ENUM` lines carry the constant-to-ordinal mapping, which is the #964 surface: the ordinal IS
    /// the encoding, so inserting a constant anywhere but the end silently remaps every value after it.
    static List<String> currentAssignment() {
        return allCodecs().flatMap(codec -> describe(codec.type(), codec.tag()))
                          .distinct()
                          .sorted()
                          .toList();
    }

    /// Every codec whose byte format this repository owns.
    ///
    /// The two node registries are the bulk of it. `DelegationCodecsSlice` and `StreamCodecsSliceApi`
    /// are added EXPLICITLY because deriving from the node registries alone silently missed them:
    /// their codecs are generated from `@Codec` but composed into no node registry, so `TaskGroup` and
    /// `StreamRegistryEntry.RegisteredByKind` — 2 of the 26 generated enum codecs — had no pin at all
    /// while the derivation looked exhaustive. That is the tripwire's own version of a check whose
    /// reachable space is smaller than the claim it supports, and it was caught by counting the
    /// derived set against the generated files rather than by reading this method.
    private static Stream<SliceCodec.TypeCodec<?>> allCodecs() {
        return Stream.of(NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs()).registeredTypes().values().stream(),
                         WorkerCodecs.workerCodecs(FrameworkCodecs.frameworkCodecs()).registeredTypes().values().stream(),
                         org.pragmatica.aether.slice.delegation.DelegationCodecsSlice.CODECS.stream(),
                         org.pragmatica.aether.slice.stream.StreamCodecsSliceApi.CODECS.stream())
                     .flatMap(stream -> stream);
    }

    private static Stream<String> describe(Class<?> type, int tag) {
        var name = type.getName();
        var tagLine = "TAG  " + name + " " + tag;

        if (!type.isEnum()) {
            return Stream.of(tagLine);
        }

        var constants = Stream.of(type.getEnumConstants())
                              .map(Enum.class::cast)
                              .map(constant -> constant.name() + "=" + constant.ordinal())
                              .collect(Collectors.joining(","));

        return Stream.of(tagLine, "ENUM " + name + " " + constants);
    }

    @Test
    void wireAssignment_matchesTheRecordedBaseline() {
        var current = currentAssignment();
        var recorded = baseline();

        if (MODE == Mode.GATE) {
            var removed = recorded.stream().filter(line -> !current.contains(line)).toList();

            assertTrue(removed.isEmpty(),
                       """
                       GATE mode: %d recorded wire assignment(s) are gone or changed. Post-GA the discipline is \
                       ADD ONLY — never replace or remove — so these cannot be re-recorded away:
                       %s"""
                       .formatted(removed.size(), String.join("\n", removed)));

            return;
        }

        assertEquals(String.join("\n", recorded),
                     String.join("\n", current),
                     """
                     The wire assignment changed. Every line here is something a peer running the previous \
                     build decodes differently — a tag decides which codec reads a message, and an ordinal \
                     IS an enum's encoding, so inserting a constant anywhere but last silently remaps every \
                     value after it (#964).

                     Pre-GA this is allowed. If the change was intended, re-record the baseline with the \
                     WireAssignmentBaselineWriter test in this package and commit the diff so the change is \
                     visible in review. If it was NOT intended, you have just changed the wire format.
                     """);
    }

    /// The tripwire's own instrument check. A baseline that had drifted into emptiness, or a
    /// `registeredTypes()` that returned nothing, would make the comparison above pass while examining
    /// nothing at all — the green-on-zero-files failure mode, in a different medium.
    @Test
    void tripwire_actuallyExaminesSomething() {
        var current = currentAssignment();

        assertTrue(current.size() > 100,
                   "Only %d assignment lines were derived — the registries are not being read".formatted(current.size()));
        assertTrue(current.stream().anyMatch(line -> line.startsWith("ENUM ")),
                   "No ENUM lines were derived, so the enum-ordinal surface this test exists for is unpinned");
        assertTrue(baseline().size() > 100,
                   "The recorded baseline has only %d lines — it cannot be pinning the registry".formatted(baseline().size()));

        // The number that caught the gap: 26 enum codecs are generated across the repository, and
        // deriving from the node registries alone pinned 24. Stated as a floor with the space named,
        // because a count with no stated space is not checkable.
        var pinnedEnums = current.stream().filter(line -> line.startsWith("ENUM ")).count();

        assertTrue(pinnedEnums >= 26,
                   ("Only %d enums are pinned. 26 enum codecs are generated under aether/ and integrations/;"
                    + " an enum with a generated codec and no pin is exactly what this test exists to catch.")
                   .formatted(pinnedEnums));
    }

    /// Every `@Codec` enum in the system registries must carry the sentinel, and it must be LAST.
    ///
    /// `CodecProcessor` enforces this at compile time for annotated enums, so this is the runtime
    /// confirmation that the compile-time rule actually covered the shipped registry rather than some
    /// subset of it — the two reach the set by different routes (annotations versus the built
    /// registry), which is the only reason running both is worth anything.
    @Test
    void everyRegisteredEnum_endsWithTheUnknownSentinel() {
        var offenders = allCodecs().map(SliceCodec.TypeCodec::type)
                              .filter(Class::isEnum)
                              .distinct()
                              .filter(type -> !endsWithSentinel(type))
                              .map(Class::getName)
                              .sorted()
                              .toList();

        assertTrue(offenders.isEmpty(),
                   """
                   These registered enums cannot represent a value they do not know, so an ordinal from a \
                   peer running a newer copy is dropped rather than surfaced (#964): %s"""
                   .formatted(String.join(", ", offenders)));
    }

    private static boolean endsWithSentinel(Class<?> type) {
        var constants = type.getEnumConstants();

        return constants.length > 0 && "UNKNOWN".equals(((Enum<?>) constants[constants.length - 1]).name());
    }
}
