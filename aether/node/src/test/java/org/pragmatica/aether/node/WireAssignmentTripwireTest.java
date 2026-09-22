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
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// #964: makes a change to the WIRE ASSIGNMENT — a type's tag, an enum's constant ordering, or a
/// record's component shape — impossible to make by accident.
///
/// The failure this exists for wears the syntax of a one-line edit. Appending a constant to a `@Codec`
/// enum, renaming a type so its hash-derived tag moves, or adding a component to a `@Codec` record,
/// changes what every other node in the cluster decodes; nothing in the compiler, the test suite, or
/// review says so. `SystemCodecPinningTest` does NOT cover this and must not be cited for it: it
/// asserts registry MEMBERSHIP and hot-tag bounds, names no type, and pins no byte format at all.
///
/// **The set is DERIVED, not listed.** Every type comes from the live registries via
/// `SliceCodec.registeredTypes()`, so an enum somebody forgets to mention is still pinned. A
/// hand-maintained list would be silent about exactly the case a tripwire is for.
///
/// ## Why `SHAPE` exists, and what the generator actually does (#1450)
///
/// Until #1450 this test recorded tags and enum ordinals ONLY. A change to the SHAPE of an
/// already-shipped record, under an unchanged tag, passed green — which is how
/// `CommunityMetricsSnapshot` could gain two components under shipped tag 86 with the gate reporting
/// success. A green tripwire meant "no tag or ordinal moved", never "the wire is compatible", and
/// nothing in its output said so.
///
/// The ruling on which shape changes fail is READ OFF THE GENERATOR, not assumed.
/// `CodecClassGenerator.generateRecordCodec` walks `getRecordComponents()` in declaration order and
/// emits a **bare positional concatenation** — there is no field count, no field-name table, no
/// per-field length, and no skip metadata. The per-field type byte that IS written is **discarded**
/// by the reader (`buf.readByte();` with the value unused), so it cannot support a tolerant or
/// skipping decode. `SliceCodec.write`/`read` add only the compact type tag ahead of that body.
/// Therefore:
///
/// - **ADDING a component FAILS.** There is no "unknown field" path to fall into. A reader built
///   against the old shape stops N components early: at the top of a length-delimited frame the
///   remainder is silently dropped, and NESTED — inside a list element or an enclosing record — the
///   stream desynchronises and the ENCLOSING decode misparses without raising anything. The reverse
///   direction (new reader, old writer) reads past the body into whatever follows. This is the case
///   worth stating explicitly, because an appended field is tolerable under a tag-length-value or
///   field-numbered codec and this codec is neither.
/// - **REMOVING, REORDERING, or CHANGING THE TYPE of a component FAILS** for the same reason: the
///   layout is positional, so every byte after the edit shifts or is reinterpreted.
/// - **RENAMING a component is byte-neutral** — component names never reach the wire. It is recorded
///   and fails the tripwire anyway, because swapping the names of two same-typed components inverts
///   their meaning while leaving an IDENTICAL byte layout, and a types-only shape column cannot see
///   it. The diff is the only place that change can surface. The failure message says which kind you
///   are looking at so a rename is not mistaken for a break.
///
/// For the five hand-written `FrameworkCodecs` entries that happen to be records (`Unit`,
/// `Option.None`, `Option.Some`, `Result.Success`, `Result.Failure`) the recorded shape is a PROXY —
/// their bodies are hand-written rather than component-derived — but a change to those components is
/// still drift worth reading. No exclusion list: the set stays derived.
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

    /// The types this tripwire CANNOT pin structurally, named rather than left as an unwritten
    /// limitation (#1450).
    ///
    /// A `SHAPE` line is derivable only from a record's components. The 16 types below are neither
    /// records nor enums — JDK types and two Pragmatica classes — so their bodies live in
    /// hand-written `FrameworkCodecs`/`@CodecFor` codecs that reflection cannot read. For them this
    /// test pins the TAG and nothing else, and saying so here is the point: a limitation a reader
    /// has to infer from a MISSING line is a limitation that gets skipped.
    ///
    /// Encoded as an assertion rather than prose so that the blind spot cannot GROW quietly. A new
    /// non-record registered type reddens `theStructuralBlindSpot_didNotGrow` by name, instead of
    /// joining the set silently behind a tripwire that still reports success.
    private static final List<String> STRUCTURALLY_UNPINNABLE =
        List.of("[B",
                "java.lang.Boolean", "java.lang.Byte", "java.lang.Character", "java.lang.Double",
                "java.lang.Float", "java.lang.Integer", "java.lang.Long", "java.lang.Short",
                "java.lang.String",
                "java.net.InetSocketAddress", "java.util.List", "java.util.Map", "java.util.Set",
                "org.pragmatica.lang.Unit", "org.pragmatica.lang.io.TimeSpan");

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
    /// `SHAPE` lines carry a record's component names and types in DECLARATION ORDER, which is the
    /// #1450 surface: the record body is a positional concatenation, so the component list IS the
    /// layout and any edit to it changes what a peer decodes under an unchanged tag.
    static List<String> currentAssignment() {
        return allCodecs().flatMap(codec -> describe(codec.type(), codec.tag()))
                          .distinct()
                          .sorted()
                          .toList();
    }

    /// Every codec whose byte format this repository owns.
    ///
    /// The node registry is the bulk of it (the orphaned `WorkerCodecs` was deleted in #503). `DelegationCodecsSlice` and `StreamCodecsSliceApi`
    /// are added EXPLICITLY because deriving from the node registries alone silently missed them:
    /// their codecs are generated from `@Codec` but composed into no node registry, so `TaskGroup` and
    /// `StreamRegistryEntry.RegisteredByKind` — 2 of the 26 generated enum codecs — had no pin at all
    /// while the derivation looked exhaustive. That is the tripwire's own version of a check whose
    /// reachable space is smaller than the claim it supports, and it was caught by counting the
    /// derived set against the generated files rather than by reading this method.
    private static Stream<SliceCodec.TypeCodec<?>> allCodecs() {
        return Stream.of(NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs()).registeredTypes().values().stream(),
                         org.pragmatica.aether.slice.delegation.DelegationCodecsSlice.CODECS.stream(),
                         org.pragmatica.aether.slice.stream.StreamCodecsSliceApi.CODECS.stream())
                     .flatMap(stream -> stream);
    }

    private static Stream<String> describe(Class<?> type, int tag) {
        var name = type.getName();
        var tagLine = "TAG  " + name + " " + tag;

        if (type.isEnum()) {
            return Stream.of(tagLine, "ENUM " + name + " " + constants(type));
        }

        if (type.isRecord()) {
            return Stream.of(tagLine, "SHAPE " + name + " " + shape(type));
        }

        return Stream.of(tagLine);
    }

    private static String constants(Class<?> type) {
        return Stream.of(type.getEnumConstants())
                     .map(Enum.class::cast)
                     .map(constant -> constant.name() + "=" + constant.ordinal())
                     .collect(Collectors.joining(","));
    }

    /// The component list, in the order `getRecordComponents()` returns it — which the JLS fixes as
    /// DECLARATION ORDER, the same order `CodecClassGenerator` walks when it emits the body. That is
    /// what makes this column deterministic: nothing here sorts, hashes or otherwise re-derives the
    /// order, so the only thing that moves a component is somebody moving it in the record header.
    /// A shape column with an unstable order would redden on a clean tree and be switched off within
    /// a week, which is worse than not having one.
    ///
    /// Types are rendered generically (`java.util.List<...Foo>`, not `java.util.List`) because a
    /// change of element type leaves the framing intact and the decoded value wrong — an unchecked
    /// cast in the generated `readBody` is all that stands between the two.
    private static String shape(Class<?> type) {
        return Stream.of(type.getRecordComponents())
                     .map(component -> component.getName() + ":" + component.getGenericType().getTypeName())
                     .collect(Collectors.joining(",", "(", ")"));
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
                     build decodes differently — a tag decides which codec reads a message, an ordinal IS \
                     an enum's encoding, so inserting a constant anywhere but last silently remaps every \
                     value after it (#964), and a SHAPE line IS a record's byte layout, because the \
                     generated body is a positional concatenation with no field count and no skip \
                     metadata (#1450).

                     Reading a changed SHAPE line:
                       * a component ADDED, REMOVED, REORDERED, or RETYPED is a WIRE BREAK. There is no
                         unknown-field path in this codec; an old reader stops early and, nested inside a
                         list or an enclosing record, desynchronises the stream rather than failing.
                       * a component RENAMED with its type and position unchanged is BYTE-NEUTRAL — names
                         never reach the wire. It is pinned anyway because swapping the names of two
                         same-typed components inverts their meaning behind an identical layout, and this
                         diff is the only place that shows up.

                     Pre-GA this is allowed, so re-recording is the normal remedy — but re-recording is \
                     ALSO exactly how a real regression gets silenced, and the two are indistinguishable \
                     once the baseline is rewritten. So, in this order:

                       1. READ the diff above line by line, BEFORE re-recording.
                          Every changed line must be a change you meant to make.
                       2. If a line moved that you did NOT intend — a tag you never touched, an
                          ordinal in an enum you never edited, or a component in a record you never
                          opened — STOP. Do not re-record. A merge or rebase has altered a wire
                          assignment that no change intended, which is the defect this test exists to
                          catch, arriving through the mechanism built to catch it.
                       3. Only then re-record with the WireAssignmentBaselineWriter test in this
                          package (it needs -Dwire.baseline.record=true), and say in the commit
                          message which change the accepted delta belongs to.

                     "The build was red so I re-recorded the baseline" is not a reason. Naming the change \
                     that the delta belongs to is.
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

        // The number that caught the gap: 26 enum codecs were generated across the repository, and
        // deriving from the node registries alone pinned 24. 25 since #722 deleted `GenerationReason`
        // (counted as `*Codec.java` under every module's generated-sources carrying the enum
        // sentinel). Stated as a floor with the space named, because a count with no stated space
        // is not checkable.
        var pinnedEnums = current.stream().filter(line -> line.startsWith("ENUM ")).count();

        assertTrue(pinnedEnums >= 25,
                   ("Only %d enums are pinned. 25 enum codecs are generated under aether/ and integrations/;"
                    + " an enum with a generated codec and no pin is exactly what this test exists to catch.")
                   .formatted(pinnedEnums));

        // #1450's floor, stated in the same shape. The space is the registered set derived by
        // `allCodecs()`: 313 tagged types on the branch that added this, of which 271 are records,
        // 26 enums and 16 neither (see `theStructuralBlindSpot_didNotGrow` for that last group by
        // name). Floor rather than equality because adding a record is routine.
        var pinnedShapes = current.stream().filter(line -> line.startsWith("SHAPE ")).count();

        assertTrue(pinnedShapes >= 265,
                   ("Only %d record shapes are pinned out of 271 registered records. A record with a"
                    + " generated codec and no SHAPE line is the #1450 blind spot reopening.")
                   .formatted(pinnedShapes));

        // The shape-specific green-on-zero mode: `getRecordComponents()` yielding nothing would emit
        // 271 lines reading `()`, comparing equal to a baseline recorded the same way and pinning no
        // layout at all. 5 registered records are genuinely component-less (marker keys and
        // `Option.None`), so the discriminating assertion is that the empty ones stay a handful.
        var emptyShapes = current.stream().filter(line -> line.startsWith("SHAPE ") && line.endsWith(" ()")).count();

        assertTrue(emptyShapes <= 20,
                   ("%d of %d pinned shapes have no components. 5 do legitimately; a jump means the"
                    + " component derivation returned nothing and the SHAPE column is pinning air.")
                   .formatted(emptyShapes, pinnedShapes));
    }

    @Test
    void theStructuralBlindSpot_didNotGrow() {
        var unpinnable = allCodecs().filter(codec -> structurallyUnpinnable(codec.type()))
                                    .map(codec -> codec.type().getName())
                                    .distinct()
                                    .sorted()
                                    .toList();

        assertEquals(String.join("\n", STRUCTURALLY_UNPINNABLE),
                     String.join("\n", unpinnable),
                     """
                     The set of registered types whose byte layout this tripwire cannot derive has changed.

                     A type that is neither a record nor an enum gets a TAG line and nothing more: its body \
                     is hand-written, so reflection cannot state its shape and a green result here says \
                     NOTHING about it. If you added one, the wire format it carries is pinned by no \
                     automated check at all — add a hand-written pin, or accept the gap deliberately by \
                     naming it in STRUCTURALLY_UNPINNABLE with the reason.""");
    }

    /// A type whose byte layout reflection cannot state: neither a record (components) nor an enum
    /// (constants), so its codec body is hand-written and nothing here can pin it.
    private static boolean structurallyUnpinnable(Class<?> type) {
        return !type.isEnum() && !type.isRecord();
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
