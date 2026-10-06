// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.ExecutionMode;
import org.pragmatica.aether.slice.SliceLoadingFailure;
import org.pragmatica.aether.slice.SliceLoadingFailure.Unrecognised;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.stream.StreamRegistryEntry;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.RewindEpoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.cluster.state.kvstore.AssignmentTokenBearing;
import org.pragmatica.cluster.state.kvstore.CommunityFenced;
import org.pragmatica.cluster.state.kvstore.IncarnationFenced;
import org.pragmatica.cluster.state.kvstore.EpochBearing;
import org.pragmatica.cluster.state.kvstore.GrowOnlyMergeable;
import org.pragmatica.cluster.state.kvstore.OwnerFenced;
import org.pragmatica.cluster.state.kvstore.LeaderAuthorized;
import org.pragmatica.cluster.state.kvstore.VersionFenced;
import org.pragmatica.cluster.state.kvstore.WitnessedRemoval;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Codec;
import org.pragmatica.serialization.CodecFor;

import static org.pragmatica.lang.Option.none;


@Codec
@CodecFor(ExecutionMode.class)
@SuppressWarnings("JBCT-NAM-01")
public sealed interface AetherValue {
    /// The digest (size, MD5, SHA-1, SHA-256) of the content bound to one file of one coordinate, keyed by `AetherKey.ArtifactContentKey`
    /// (#1778). The applier keeps the FIRST committed value ([GrowOnlyMergeable]: a later write merges into the
    /// committed one and yields it unchanged), so the binding is decided once, by the consensus log.
    record ArtifactContentValue(long size, String md5, String sha1, String sha256, long deployedAt) implements AetherValue, GrowOnlyMergeable<ArtifactContentValue> {
        /// Whether `other` describes the same CONTENT: size, MD5, SHA-1 and SHA-256, not the deploy time. The deploy
        /// time is the FIRST proposer's, kept with the binding so that completing an interrupted write rewrites the
        /// file's metadata with its original time instead of a new one.
        public boolean sameDigest(ArtifactContentValue other) {
            return size == other.size
                   && md5.equals(other.md5)
                   && sha1.equals(other.sha1)
                   && sha256.equals(other.sha256);
        }

        @Override
        public ArtifactContentValue mergeInto(ArtifactContentValue committed) {
            return committed;
        }
    }

    /// One version of an artifact and whether it was archived (#1778).
    record ArtifactVersionEntry(String version, boolean archived) {}

    /// The versions of one artifact in the built-in artifact store, keyed by `AetherKey.ArtifactVersionsKey`
    /// (#1778). A grow-only set: the applier MERGES a written value into the committed one
    /// ([GrowOnlyMergeable]), the merge is a union that takes the higher state per version
    /// (`present < archived`), so concurrent publishes are folded in consensus order and no writer can lose
    /// another's version or un-archive one. A writer sends only what it adds: [#added] or [#archived].
    record ArtifactVersionsValue(List<ArtifactVersionEntry> entries, int maxLive) implements AetherValue, GrowOnlyMergeable<ArtifactVersionsValue> {
        /// The default bound on the PRESENT (not archived) versions of one artifact; `[slice] artifact_max_versions`.
        public static final int DEFAULT_MAX_LIVE = 10_000;

        public ArtifactVersionsValue {
            entries = entries.stream().sorted(Comparator.comparing(ArtifactVersionEntry::version)).toList();
        }

        public static ArtifactVersionsValue added(String version) {
            return added(version, DEFAULT_MAX_LIVE);
        }

        /// `maxLive` is the writer's bound; the applier enforces it on THIS command, so every replica decides alike.
        public static ArtifactVersionsValue added(String version, int maxLive) {
            return new ArtifactVersionsValue(List.of(new ArtifactVersionEntry(version, false)), maxLive);
        }

        public static ArtifactVersionsValue archived(String version) {
            return archived(version, DEFAULT_MAX_LIVE);
        }

        public static ArtifactVersionsValue archived(String version, int maxLive) {
            return new ArtifactVersionsValue(List.of(new ArtifactVersionEntry(version, true)), maxLive);
        }

        public static ArtifactVersionsValue empty() {
            return new ArtifactVersionsValue(List.of(), DEFAULT_MAX_LIVE);
        }

        /// Monotone merge. A version already in the set only moves up (`present < archived`); an ARCHIVED entry is
        /// always accepted (it never adds a present version); a NEW present version is accepted only while the
        /// committed set holds fewer than `maxLive` present versions, otherwise it is NOT added. A present version is
        /// never dropped and an archived one never un-archived, so the bound only ever refuses growth, loudly: the
        /// writer re-reads the committed set and reports the refusal.
        @Override
        public ArtifactVersionsValue mergeInto(ArtifactVersionsValue committed) {
            var merged = new TreeMap<String, Boolean>();

            committed.entries().forEach(entry -> merged.put(entry.version(), entry.archived()));
            entries.forEach(entry -> mergeEntry(merged, entry));

            return new ArtifactVersionsValue(merged.entrySet()
                                                   .stream()
                                                   .map(entry -> new ArtifactVersionEntry(entry.getKey(),
                                                                                          entry.getValue()))
                                                   .toList(),
                                             maxLive);
        }

        private void mergeEntry(TreeMap<String, Boolean> merged, ArtifactVersionEntry entry) {
            if (merged.containsKey(entry.version()) || entry.archived() || liveIn(merged) < maxLive) {
                merged.merge(entry.version(), entry.archived(), Boolean::logicalOr);
            }
        }

        private static long liveIn(TreeMap<String, Boolean> merged) {
            return merged.values()
                         .stream()
                         .filter(archived -> !archived)
                         .count();
        }

        /// Whether `version` is in the set, archived or not.
        public boolean contains(String version) {
            return entries.stream()
                          .anyMatch(entry -> entry.version()
                                                  .equals(version));
        }

        /// Whether a NEW present version could be added under `maxLive`.
        public boolean hasRoom(int maxLive) {
            return live().size() < maxLive;
        }

        /// Whether `version` is in the set and flagged archived.
        public boolean isArchived(String version) {
            return entries.stream()
                          .anyMatch(entry -> entry.archived() && entry.version()
                                                                      .equals(version));
        }

        /// The versions that are present and not archived, in version-string order.
        public List<String> live() {
            return entries.stream()
                          .filter(entry -> !entry.archived())
                          .map(ArtifactVersionEntry::version)
                          .toList();
        }
    }

    record SliceTargetValue(Version currentVersion,
                            int targetInstances,
                            int minInstances,
                            Option<BlueprintId> owningBlueprint,
                            String placement,
                            long updatedAt,
                            Option<Integer> maxInstances,
                            Option<Double> scaleUpThreshold,
                            Option<Double> scaleDownThreshold) implements AetherValue {
        private static final String DEFAULT_PLACEMENT = "CORE_ONLY";

        public SliceTargetValue {
            if (placement == null || placement.isEmpty()) {
                placement = DEFAULT_PLACEMENT;
            }

            if (maxInstances == null) {
                maxInstances = none();
            }

            if (scaleUpThreshold == null) {
                scaleUpThreshold = none();
            }

            if (scaleDownThreshold == null) {
                scaleDownThreshold = none();
            }
        }

        /// Backward-compatible constructor — pre-existing call sites pass the six historical fields;
        /// the per-slice autoscaler overrides (#424) default to `none()`. Mirrors the trailing-field
        /// backward-compat idiom used across `AetherValue` (cf. `AppBlueprintValue`,
        /// `ProvisioningSlotValue`).
        public SliceTargetValue(Version currentVersion,
                                int targetInstances,
                                int minInstances,
                                Option<BlueprintId> owningBlueprint,
                                String placement,
                                long updatedAt) {
            this(currentVersion,
                 targetInstances,
                 minInstances,
                 owningBlueprint,
                 placement,
                 updatedAt,
                 none(),
                 none(),
                 none());
        }

        /// Creation factories. Every overload below that takes no explicit `placement` fixes it to
        /// [#DEFAULT_PLACEMENT], so they are usable only where there is no existing placement to
        /// carry — the first write for a slice nothing has placed yet.
        ///
        /// A producer rebuilding a value it has already observed must not use them. Resetting an
        /// operator's placement is not merely a bookkeeping loss: the reset value is acted on, and
        /// the slice is re-allocated under the default (#937). Rebuild through the `with*` methods
        /// instead — they thread every unchanged component through by construction, which is what
        /// makes this class of loss inexpressible rather than merely absent. #698 (owner), #936
        /// (`minInstances`) and #937 (`placement`) were three instances of it at one such producer;
        /// `ClusterDeploymentState.handleAppBlueprintChange` was a second (#983) and now rebuilds through
        /// [#withBlueprintDeclaration].
        public static SliceTargetValue sliceTargetValue(Version version, int instances, Option<BlueprintId> owner) {
            return new SliceTargetValue(version,
                                        instances,
                                        defaultMinInstances(instances),
                                        owner,
                                        DEFAULT_PLACEMENT,
                                        System.currentTimeMillis(),
                                        none(),
                                        none(),
                                        none());
        }

        public static SliceTargetValue sliceTargetValue(Version version, int instances) {
            return new SliceTargetValue(version,
                                        instances,
                                        defaultMinInstances(instances),
                                        none(),
                                        DEFAULT_PLACEMENT,
                                        System.currentTimeMillis(),
                                        none(),
                                        none(),
                                        none());
        }

        public static SliceTargetValue sliceTargetValue(Version version, int instances, int minInstances) {
            return new SliceTargetValue(version,
                                        instances,
                                        minInstances,
                                        none(),
                                        DEFAULT_PLACEMENT,
                                        System.currentTimeMillis(),
                                        none(),
                                        none(),
                                        none());
        }

        public static SliceTargetValue sliceTargetValue(Version version,
                                                        int instances,
                                                        int minInstances,
                                                        Option<BlueprintId> owner) {
            return new SliceTargetValue(version,
                                        instances,
                                        minInstances,
                                        owner,
                                        DEFAULT_PLACEMENT,
                                        System.currentTimeMillis(),
                                        none(),
                                        none(),
                                        none());
        }

        public static SliceTargetValue sliceTargetValue(Version version,
                                                        int instances,
                                                        int minInstances,
                                                        String placement) {
            return new SliceTargetValue(version,
                                        instances,
                                        minInstances,
                                        none(),
                                        placement,
                                        System.currentTimeMillis(),
                                        none(),
                                        none(),
                                        none());
        }

        /// Deploy-time factory (#424) carrying the per-slice autoscaler overrides —
        /// `maxInstances` bounds scale-up; the threshold overrides win over the cluster tier.
        public static SliceTargetValue sliceTargetValue(Version version,
                                                        int instances,
                                                        int minInstances,
                                                        Option<BlueprintId> owner,
                                                        Option<Integer> maxInstances,
                                                        Option<Double> scaleUpThreshold,
                                                        Option<Double> scaleDownThreshold) {
            return new SliceTargetValue(version,
                                        instances,
                                        minInstances,
                                        owner,
                                        DEFAULT_PLACEMENT,
                                        System.currentTimeMillis(),
                                        maxInstances,
                                        scaleUpThreshold,
                                        scaleDownThreshold);
        }

        /// #1497 — the one availability floor for every writer that has no explicit value: `ceil(n/2)`, the
        /// blueprint default. CLI/REST deploy, `addSliceTargetCommand`, A/B tests and rollback used to write
        /// `minInstances == instances`; the #1488 drain guard then could never drain an owner of such a slice,
        /// so a surplus drain deferred forever. `minInstances` also feeds the autoscaler's floor, but the
        /// autoscaler never scales a slice below 3 (#1495: its floor is `max(minInstances, 3)`), so a slice
        /// deployed with 3 instances stays at 3.
        public static int defaultMinInstances(int instances) {
            return Math.ceilDiv(instances, 2);
        }

        public int effectiveMinInstances() {
            return Math.max(1, minInstances);
        }

        public String effectivePlacement() {
            return placement;
        }

        public SliceTargetValue withInstances(int newCount) {
            return new SliceTargetValue(currentVersion,
                                        newCount,
                                        minInstances,
                                        owningBlueprint,
                                        placement,
                                        System.currentTimeMillis(),
                                        maxInstances,
                                        scaleUpThreshold,
                                        scaleDownThreshold);
        }

        public SliceTargetValue withPlacement(String newPlacement) {
            return new SliceTargetValue(currentVersion,
                                        targetInstances,
                                        minInstances,
                                        owningBlueprint,
                                        newPlacement,
                                        System.currentTimeMillis(),
                                        maxInstances,
                                        scaleUpThreshold,
                                        scaleDownThreshold);
        }

        /// Re-applies a republished blueprint onto this committed value: version, `minInstances`, owner and the
        /// autoscaler overrides come from the blueprint, while `placement`, which a blueprint cannot express, and
        /// `targetInstances`, the scale the slice is running at, are carried (#983). Both are carried by
        /// construction rather than by a caller remembering to.
        ///
        /// The carried count is clamped into the NEW bounds: `[minimumInstances, maxInstancesOverride]`. A count
        /// the new bounds exclude moves to the nearest bound; where the bounds contradict each other
        /// (`min > max`) the minimum wins. The declared instance count applies only to a first deploy, which has
        /// no committed value and so does not come through here.
        public SliceTargetValue withBlueprintDeclaration(Version version,
                                                         int minimumInstances,
                                                         Option<BlueprintId> owner,
                                                         Option<Integer> maxInstancesOverride,
                                                         Option<Double> scaleUpOverride,
                                                         Option<Double> scaleDownOverride) {
            var cappedAtMax = maxInstancesOverride.map(max -> Math.min(targetInstances, max)).or(targetInstances);

            return new SliceTargetValue(version,
                                        Math.max(minimumInstances, cappedAtMax),
                                        minimumInstances,
                                        owner,
                                        placement,
                                        System.currentTimeMillis(),
                                        maxInstancesOverride,
                                        scaleUpOverride,
                                        scaleDownOverride);
        }

        public SliceTargetValue withVersion(Version newVersion) {
            return new SliceTargetValue(newVersion,
                                        targetInstances,
                                        minInstances,
                                        owningBlueprint,
                                        placement,
                                        System.currentTimeMillis(),
                                        maxInstances,
                                        scaleUpThreshold,
                                        scaleDownThreshold);
        }
    }

    /// `registerOnly` semantically signals that this blueprint was registered via
    /// `/api/blueprints/publish` (not `/api/blueprints/deploy`). The publish endpoint
    /// stores the blueprint definition for use by a future strategy-based deploy upgrade
    /// without immediately making it the active version. Consumed by
    /// `ClusterDeploymentState.handleAppBlueprintChange`, which suppresses the
    /// `SliceTargetValue` Put when `registerOnly && existing SliceTargetValue present`.
    record AppBlueprintValue(ExpandedBlueprint blueprint, boolean registerOnly) implements AetherValue {
        /// Backward-compat constructor — pre-existing call sites pass blueprint only;
        /// `registerOnly` defaults to `false` (the historical deploy-on-publish semantics).
        public AppBlueprintValue(ExpandedBlueprint blueprint) {
            this(blueprint, false);
        }

        public static AppBlueprintValue appBlueprintValue(ExpandedBlueprint blueprint) {
            return new AppBlueprintValue(blueprint, false);
        }

        public static AppBlueprintValue appBlueprintValue(ExpandedBlueprint blueprint, boolean registerOnly) {
            return new AppBlueprintValue(blueprint, registerOnly);
        }
    }

    /// Durable terminal outcome of one blueprint's deployment attempt, keyed by
    /// `AetherKey.DeploymentOutcomeKey`. Written by `ClusterDeploymentState` at the FSM's terminal
    /// transitions (full deployment, ALL_OR_NOTHING rollback) and never removed when the blueprint's
    /// own `AppBlueprintValue` is torn down — this is the record of what happened, not part of the
    /// blueprint's active configuration, so it survives rollback and remains readable via
    /// `BlueprintService.lastOutcome` after `GET /api/blueprints/status/{id}` would otherwise 404.
    ///
    /// `timestampMs` is passed in explicitly (no internal `System.currentTimeMillis()` default,
    /// unlike `SchemaVersionValue`) so the FSM call site can supply `ClusterDeploymentContext.nowMs()`
    /// — the same test-controllable clock already used for this class's `DeploymentFailed` event
    /// timestamps, keeping both artifacts of one failure event on one clock read.
    record DeploymentOutcomeValue(DeploymentOutcomeStatus status,
                                  List<String> failingSlices,
                                  String cause,
                                  long timestampMs,
                                  long outcomeVersion) implements AetherValue, VersionFenced {
        /// The first version of a chain — the value written when no outcome record is committed yet.
        /// The applier does not fence a first write (there is no chain to fence), so this constant is
        /// what every write against an absent key carries.
        public static final long FIRST_VERSION = 1L;

        public DeploymentOutcomeValue {
            failingSlices = List.copyOf(failingSlices);
        }

        /// Lost-update fence version (RFC-0018, #570) — added for #805 item 2.
        ///
        /// `recordBestEffortFailureOutcome` merges a newly-failed slice into this record by READING
        /// the committed value and PUTTING a merged one. The read happens when the command is built;
        /// the Put applies later, after consensus. Two BEST_EFFORT slice failures that are both
        /// in flight before either applies therefore both read the same base and each Put the other's
        /// id away — and the loser is decided by SHA-256 batch-id order (`RabiaEngine.pendingBatches`
        /// is a `ConcurrentSkipListMap` keyed by a content hash and every proposal site takes
        /// `firstEntry()`), NOT by submission order, so this is a coin flip on the happy path rather
        /// than a narrow window needing a failed consensus round.
        ///
        /// Fencing the record makes the applier reject the second writer instead of letting it
        /// silently overwrite the first. Rejection alone does not preserve the id — the write is
        /// simply dropped — so the merge path pairs this with a bounded re-read-and-retry after its
        /// apply resolves, which is the confirmation protocol [VersionFenced] itself prescribes.
        ///
        /// **Every writer of this record must derive its version from the CURRENT committed value and
        /// bump by exactly one.** `ClusterDeploymentState.Active.nextOutcomeVersion` is that
        /// derivation; all four production write sites go through it.
        @Override
        public long fenceVersion() {
            return outcomeVersion;
        }

        /// First-write forms, carrying [#FIRST_VERSION]. Correct only against an absent key — a
        /// writer that may find a committed record must use the version-carrying overload, because
        /// the applier rejects any non-successor write.
        /// #963 — written by `BlueprintService` in the SAME consensus batch as the blueprint's own
        /// `AppBlueprintKey` Put, replacing the bare `Remove` that used to clear a stale terminal.
        /// It clears the stale record exactly as the `Remove` did AND records that this attempt
        /// started, so "no terminal yet" becomes a positive fact instead of an absence.
        ///
        /// `startedAtMs` is the apply's start, not a terminal's timestamp — it is what any
        /// deadline-based abandonment must measure from.
        ///
        /// **MERGE #956 (#805 item 2):** this record became [VersionFenced] while #963 was in
        /// flight, so the first-write form below is correct ONLY against an absent key. The publish
        /// paths that call it write over a POSSIBLY-COMMITTED record — clearing a stale terminal is
        /// their whole purpose — so they must use the version-carrying overload, or the applier
        /// rejects the write and the stale terminal survives. `BlueprintService.nextOutcomeVersion`
        /// is that derivation.
        public static DeploymentOutcomeValue inProgress(long startedAtMs) {
            return inProgress(startedAtMs, FIRST_VERSION);
        }

        public static DeploymentOutcomeValue inProgress(long startedAtMs, long outcomeVersion) {
            return new DeploymentOutcomeValue(DeploymentOutcomeStatus.IN_PROGRESS,
                                              List.of(),
                                              "",
                                              startedAtMs,
                                              outcomeVersion);
        }

        public static DeploymentOutcomeValue succeeded(long timestampMs) {
            return succeeded(timestampMs, FIRST_VERSION);
        }

        public static DeploymentOutcomeValue failed(List<String> failingSlices, String cause, long timestampMs) {
            return failed(failingSlices, cause, timestampMs, FIRST_VERSION);
        }

        public static DeploymentOutcomeValue rolledBack(List<String> failingSlices, String cause, long timestampMs) {
            return rolledBack(failingSlices, cause, timestampMs, FIRST_VERSION);
        }

        public static DeploymentOutcomeValue succeeded(long timestampMs, long outcomeVersion) {
            return new DeploymentOutcomeValue(DeploymentOutcomeStatus.SUCCEEDED,
                                              List.of(),
                                              "",
                                              timestampMs,
                                              outcomeVersion);
        }

        public static DeploymentOutcomeValue failed(List<String> failingSlices,
                                                    String cause,
                                                    long timestampMs,
                                                    long outcomeVersion) {
            return new DeploymentOutcomeValue(DeploymentOutcomeStatus.FAILED,
                                              failingSlices,
                                              cause,
                                              timestampMs,
                                              outcomeVersion);
        }

        public static DeploymentOutcomeValue rolledBack(List<String> failingSlices,
                                                        String cause,
                                                        long timestampMs,
                                                        long outcomeVersion) {
            return new DeploymentOutcomeValue(DeploymentOutcomeStatus.ROLLED_BACK,
                                              failingSlices,
                                              cause,
                                              timestampMs,
                                              outcomeVersion);
        }
    }

    /// `FAILED` — the blueprint's own slices never reached ACTIVE and there was no previous
    /// blueprint to fall back to (`unloadBlueprintSlices`'s path), OR a BEST_EFFORT partial deploy
    /// left one or more slices permanently failed while the rest stayed up
    /// (`recordBestEffortFailureOutcome`'s path — `failingSlices` accumulates across independent
    /// failures in the same blueprint rather than being overwritten by the last one). `ROLLED_BACK`
    /// — a previous blueprint existed and was restored in this blueprint's place
    /// (`restorePreviousBlueprint`'s path); kept distinct from `FAILED` because a caller needs to
    /// know whether the failure left the deployment empty or reverted it to a known-good prior
    /// version.
    ///
    /// #760/#724 review round 2 item g: this record is written only at the specific terminal points
    /// enumerated above and in `recordSucceededOutcome`. A blueprint deployment that never reaches
    /// any of them — the FSM host crashes mid-flight before a terminal `submitBatch`/`apply` call is
    /// even issued, or a deployment simply never resolves (no further `NodeArtifactPutReceived`
    /// events ever arrive, no deterministic or transient failure is ever reported) — leaves NO
    /// `DeploymentOutcomeKey` entry at all. Absence of a key is therefore NOT equivalent to any of
    /// the three statuses below; it means "no attempt reached a terminal write," which is
    /// indistinguishable, from this record alone, from "no attempt was ever made."
    @Codec
    enum DeploymentOutcomeStatus {
        SUCCEEDED,
        FAILED,
        ROLLED_BACK,
        /// #963 — the apply has STARTED and has not reached a terminal.
        ///
        /// Position, corrected at the #963/#964 merge: this constant is inserted BEFORE `UNKNOWN`,
        /// not appended at the end. #963 authored it as an append, which was the only safe position
        /// while the enum ended at `ROLLED_BACK`; #964 then added a trailing sentinel.
        ///
        /// The reason it must go here is MECHANICAL, not a wire-safety argument:
        /// `CodecProcessor.validateEnumSentinel` refuses to generate a codec for a `@Codec` enum whose
        /// last constant is not `UNKNOWN`, so appending past the sentinel is a BUILD ERROR (pinned by
        /// `enumCodec_failsCompilation_whenSentinelIsNotLast`). At DECODE both orderings are equally
        /// safe at one-version skew — inserted-before lands on the old sentinel's ordinal and reads as
        /// `UNKNOWN` in range; appended-after lands past `values().length` and reads as `UNKNOWN` out
        /// of range. Stated this way because two earlier drafts of this note argued from a node state
        /// instead, and both were false: the only build that reads ordinal 3 as `UNKNOWN` is #964
        /// WITHOUT #963, and #963 merged first, so that node never exists.
        ///
        /// Only this position claim changed. #963's analysis below is theirs, unaltered.
        ///
        /// This is the record that makes deployment permanence gate on PRESENCE rather than absence.
        /// The paragraph above on this class already warned that an absent key "means 'no attempt
        /// reached a terminal write,' which is indistinguishable, from this record alone, from 'no
        /// attempt was ever made.'" Five rounds of #924 each condemned a deployment on that
        /// indistinguishable absence. Written at apply START, where the writer is guaranteed to run
        /// and lands atomically with the `AppBlueprintKey` Put, rather than at completion, where it
        /// may never run at all.
        IN_PROGRESS,
        /// Wire sentinel (#964): an ordinal this node cannot name decodes here instead of throwing.
        /// Never SUCCEEDED; reported as an unreadable outcome rather than a successful one.
        /// Must stay LAST -- a new constant appended after it, or inserted before it, is read as
        /// UNKNOWN by an older node either way.
        UNKNOWN
    }

    record SliceNodeValue(SliceState state, Option<String> failureReason, boolean fatal, long transitionedAt) implements AetherValue {
        public static SliceNodeValue sliceNodeValue(SliceState state) {
            return new SliceNodeValue(state, none(), false, defaultTransitionedAt(state));
        }

        public static SliceNodeValue sliceNodeValue(SliceState state, long transitionedAt) {
            return new SliceNodeValue(state, none(), false, transitionedAt);
        }

        /// #930 — `unrecognised` is the permanence this raise site declares for a cause the
        /// classifier does not recognise. There is no single-argument form: the `fatal` flag this
        /// builds crosses consensus and decides whether the leader rolls a blueprint back, so a
        /// raise site must state its intent rather than inherit one.
        public static SliceNodeValue failedSliceNodeValue(Cause cause, Unrecognised unrecognised) {
            var classified = SliceLoadingFailure.classify(cause, unrecognised);

            return new SliceNodeValue(SliceState.FAILED,
                                      Option.option(classified.message()),
                                      classified.isFatal(),
                                      0L);
        }

        private static long defaultTransitionedAt(SliceState state) {
            return state.isTransitional()
                   ? System.currentTimeMillis()
                   : 0L;
        }
    }

    record EndpointValue(NodeId nodeId) implements AetherValue {
        public static EndpointValue endpointValue(NodeId nodeId) {
            return new EndpointValue(nodeId);
        }
    }

    record TopicSubscriptionValue(NodeId nodeId) implements AetherValue {
        public static TopicSubscriptionValue topicSubscriptionValue(NodeId nodeId) {
            return new TopicSubscriptionValue(nodeId);
        }
    }

    record ScheduledTaskValue(NodeId registeredBy, String interval, String cron, ExecutionMode executionMode) implements AetherValue {
        public static ScheduledTaskValue intervalTask(NodeId registeredBy,
                                                      String interval,
                                                      ExecutionMode executionMode) {
            return new ScheduledTaskValue(registeredBy, interval, "", executionMode);
        }

        public static ScheduledTaskValue cronTask(NodeId registeredBy, String cron, ExecutionMode executionMode) {
            return new ScheduledTaskValue(registeredBy, "", cron, executionMode);
        }

        public boolean isInterval() {
            return ! interval.isEmpty();
        }

        public boolean isCron() {
            return ! cron.isEmpty();
        }
    }

    /// The operator's pause of one scheduled task, stored under [AetherKey.ScheduledTaskPauseKey]. Its
    /// presence is the pause; `pausedAt` records when, for the operator.
    record ScheduledTaskPauseValue(long pausedAt) implements AetherValue {
        public static ScheduledTaskPauseValue scheduledTaskPauseValue(long pausedAt) {
            return new ScheduledTaskPauseValue(pausedAt);
        }
    }

    /// The recorded state of a scheduled task (one row per task; per node for an ALL-mode task).
    ///
    /// `lastOutcome` is the outcome of the NEWEST fire whose outcome is recorded: [#OUTCOME_SUCCESS] (the callee completed
    /// it), [#OUTCOME_FAILURE] (a failure response, or the callee's node departed), [#OUTCOME_UNKNOWN] (a REMOTE fire
    /// whose response did not arrive within the invocation timeout: the callee may have run it, completed it or not;
    /// nothing here can say which), or empty (no fire recorded since the field exists). An UNKNOWN outcome is neither
    /// an execution nor a failure: it does not count in `totalExecutions` and does not move `consecutiveFailures`
    /// (the failure streak is neither reset nor extended). `unknownOutcomes` is a GAUGE of the fires whose outcome is
    /// currently unknown (#1723): a fire that timed out raises it, and the response arriving LATE lowers it again by
    /// resolving that fire into the execution or the failure it was ([#lateSuccessState], [#lateFailureState]).
    ///
    /// `fireSeq` numbers the fires recorded in this row, newest highest, so a late resolution can tell whether its fire is
    /// still the newest. An older fire's late answer still resolves ITS OWN fire (counts, gauge) but never overwrites the
    /// outcome a newer fire recorded.
    record ScheduledTaskStateValue(long lastExecutionAt,
                                   long nextFireAt,
                                   int consecutiveFailures,
                                   int totalExecutions,
                                   String lastFailureMessage,
                                   long updatedAt,
                                   int skippedOverlaps,
                                   String lastOutcome,
                                   int unknownOutcomes,
                                   int fireSeq) implements AetherValue {
        public static final String OUTCOME_SUCCESS = "SUCCESS";
        public static final String OUTCOME_FAILURE = "FAILURE";
        public static final String OUTCOME_UNKNOWN = "UNKNOWN";

        /// A row with no recorded outcome (the shape every row had before outcomes were recorded).
        public ScheduledTaskStateValue(long lastExecutionAt,
                                       long nextFireAt,
                                       int consecutiveFailures,
                                       int totalExecutions,
                                       String lastFailureMessage,
                                       long updatedAt,
                                       int skippedOverlaps) {
            this(lastExecutionAt,
                 nextFireAt,
                 consecutiveFailures,
                 totalExecutions,
                 lastFailureMessage,
                 updatedAt,
                 skippedOverlaps,
                 "",
                 0,
                 0);
        }

        /// The sequence number the next recorded fire of this row takes.
        public static int nextFireSeq(Option<ScheduledTaskStateValue> prior) {
            return prior.map(ScheduledTaskStateValue::fireSeq).or(0) + 1;
        }

        public static ScheduledTaskStateValue successState(long nextFireAt,
                                                           int totalExecutions,
                                                           int skippedOverlaps,
                                                           int unknownOutcomes,
                                                           int fireSeq) {
            return new ScheduledTaskStateValue(System.currentTimeMillis(),
                                               nextFireAt,
                                               0,
                                               totalExecutions,
                                               "",
                                               System.currentTimeMillis(),
                                               skippedOverlaps,
                                               OUTCOME_SUCCESS,
                                               unknownOutcomes,
                                               fireSeq);
        }

        public static ScheduledTaskStateValue failureState(long nextFireAt,
                                                           int consecutiveFailures,
                                                           int totalExecutions,
                                                           int skippedOverlaps,
                                                           String failureMessage,
                                                           int unknownOutcomes,
                                                           int fireSeq) {
            return new ScheduledTaskStateValue(System.currentTimeMillis(),
                                               nextFireAt,
                                               consecutiveFailures,
                                               totalExecutions,
                                               failureMessage,
                                               System.currentTimeMillis(),
                                               skippedOverlaps,
                                               OUTCOME_FAILURE,
                                               unknownOutcomes,
                                               fireSeq);
        }

        /// A REMOTE fire whose outcome is unknown (no response within the invocation timeout). Everything else carries over
        /// from `prior` unchanged: not an execution, not a failure, so `totalExecutions` and `consecutiveFailures` stay as
        /// they were; only the outcome, the unknown count and the timestamps move. `lastFailureMessage` is left alone:
        /// it still describes the last real failure. The fire takes [#nextFireSeq].
        public static ScheduledTaskStateValue unknownOutcomeState(Option<ScheduledTaskStateValue> prior,
                                                                  long nextFireAt) {
            var now = System.currentTimeMillis();
            var seq = nextFireSeq(prior);

            return prior.map(p -> new ScheduledTaskStateValue(p.lastExecutionAt(),
                                                              nextFireAt,
                                                              p.consecutiveFailures(),
                                                              p.totalExecutions(),
                                                              p.lastFailureMessage(),
                                                              now,
                                                              p.skippedOverlaps(),
                                                              OUTCOME_UNKNOWN,
                                                              p.unknownOutcomes() + 1,
                                                              seq))
                        .or(new ScheduledTaskStateValue(0, nextFireAt, 0, 0, "", now, 0, OUTCOME_UNKNOWN, 1, seq));
        }

        /// The fire numbered `fireSeq`, recorded as UNKNOWN, was answered LATE with success (#1723): it was an execution
        /// after all. It is counted in `totalExecutions` and leaves `unknownOutcomes`. When it is still the NEWEST fire it
        /// also becomes the last outcome and resets the failure streak, as any success does; when a newer fire has been
        /// recorded since, that fire's outcome stands.
        public static ScheduledTaskStateValue lateSuccessState(ScheduledTaskStateValue prior, int fireSeq) {
            var now = System.currentTimeMillis();
            var newest = prior.fireSeq() == fireSeq;

            return new ScheduledTaskStateValue(newest ? now : prior.lastExecutionAt(),
                                               prior.nextFireAt(),
                                               newest ? 0 : prior.consecutiveFailures(),
                                               prior.totalExecutions() + 1,
                                               prior.lastFailureMessage(),
                                               now,
                                               prior.skippedOverlaps(),
                                               newest ? OUTCOME_SUCCESS : prior.lastOutcome(),
                                               Math.max(0, prior.unknownOutcomes() - 1),
                                               prior.fireSeq());
        }

        /// The fire numbered `fireSeq`, recorded as UNKNOWN, was answered LATE with a failure (#1723): a failure after all.
        /// It leaves `unknownOutcomes`. When it is still the NEWEST fire it also becomes the last outcome and extends the
        /// streak; when a newer fire has been recorded since, that fire's outcome and the streak stand.
        public static ScheduledTaskStateValue lateFailureState(ScheduledTaskStateValue prior,
                                                               int fireSeq,
                                                               String failureMessage) {
            var now = System.currentTimeMillis();
            var newest = prior.fireSeq() == fireSeq;

            return new ScheduledTaskStateValue(newest ? now : prior.lastExecutionAt(),
                                               prior.nextFireAt(),
                                               newest ? prior.consecutiveFailures() + 1 : prior.consecutiveFailures(),
                                               prior.totalExecutions(),
                                               newest ? failureMessage : prior.lastFailureMessage(),
                                               now,
                                               prior.skippedOverlaps(),
                                               newest ? OUTCOME_FAILURE : prior.lastOutcome(),
                                               Math.max(0, prior.unknownOutcomes() - 1),
                                               prior.fireSeq());
        }

        /// Records a skipped fixed-rate fire (previous invocation still in flight). Preserves every
        /// other field from `prior` untouched — only `updatedAt` (doubling as "last-skip timestamp")
        /// and `skippedOverlaps` advance. Absent prior state (first-ever fire skipped) starts from zero.
        public static ScheduledTaskStateValue skippedOverlapState(Option<ScheduledTaskStateValue> prior,
                                                                  long skippedAt) {
            return prior.map(p -> new ScheduledTaskStateValue(p.lastExecutionAt(),
                                                              p.nextFireAt(),
                                                              p.consecutiveFailures(),
                                                              p.totalExecutions(),
                                                              p.lastFailureMessage(),
                                                              skippedAt,
                                                              p.skippedOverlaps() + 1,
                                                              p.lastOutcome(),
                                                              p.unknownOutcomes(),
                                                              p.fireSeq()))
                        .or(new ScheduledTaskStateValue(0, 0, 0, 0, "", skippedAt, 1));
        }
    }

    record VersionRoutingValue(Version oldVersion, Version newVersion, int newWeight, int oldWeight, long updatedAt) implements AetherValue {
        public static VersionRoutingValue versionRoutingValue(Version oldVersion, Version newVersion) {
            return new VersionRoutingValue(oldVersion, newVersion, 0, 1, System.currentTimeMillis());
        }

        public static VersionRoutingValue versionRoutingValueAllNew(Version oldVersion, Version newVersion) {
            return new VersionRoutingValue(oldVersion, newVersion, 1, 0, System.currentTimeMillis());
        }

        public VersionRoutingValue withRouting(int newWeight, int oldWeight) {
            return new VersionRoutingValue(oldVersion, newVersion, newWeight, oldWeight, System.currentTimeMillis());
        }

        public boolean isAllNew() {
            return oldWeight == 0;
        }

        public boolean isAllOld() {
            return newWeight == 0;
        }
    }

    record DeploymentValue(String deploymentId,
                           String blueprintId,
                           String oldVersion,
                           String newVersion,
                           String strategy,
                           String state,
                           String routing,
                           String strategyConfig,
                           String thresholds,
                           String cleanupPolicy,
                           String artifacts,
                           int newInstances,
                           long createdAt,
                           long updatedAt) implements AetherValue {
        public static DeploymentValue deploymentValue(String deploymentId,
                                                      String blueprintId,
                                                      String oldVersion,
                                                      String newVersion,
                                                      String strategy,
                                                      String state,
                                                      String routing,
                                                      String strategyConfig,
                                                      String thresholds,
                                                      String cleanupPolicy,
                                                      String artifacts,
                                                      int newInstances,
                                                      long createdAt,
                                                      long updatedAt) {
            return new DeploymentValue(deploymentId,
                                       blueprintId,
                                       oldVersion,
                                       newVersion,
                                       strategy,
                                       state,
                                       routing,
                                       strategyConfig,
                                       thresholds,
                                       cleanupPolicy,
                                       artifacts,
                                       newInstances,
                                       createdAt,
                                       updatedAt);
        }
    }

    /// Rollback bookkeeping for one artifact base. `updatedAt` is when `currentVersion` became the target —
    /// the anchor of the auto-rollback bake window (#1573). `rollbackCount`, `lastRollbackAt` and
    /// `failedVersions` are committed in the SAME batch as the rollback's SliceTarget put, so cooldown,
    /// maxRollbacks and the never-roll-back-to-a-failed-version rule survive a leader change.
    record PreviousVersionValue(ArtifactBase artifactBase,
                                Version previousVersion,
                                Version currentVersion,
                                long updatedAt,
                                int rollbackCount,
                                long lastRollbackAt,
                                List<Version> failedVersions) implements AetherValue {
        public PreviousVersionValue {
            failedVersions = List.copyOf(failedVersions);
        }

        public static PreviousVersionValue previousVersionValue(ArtifactBase artifactBase,
                                                                Version previousVersion,
                                                                Version currentVersion) {
            return new PreviousVersionValue(artifactBase,
                                            previousVersion,
                                            currentVersion,
                                            System.currentTimeMillis(),
                                            0,
                                            0,
                                            List.of());
        }

        /// A new target version was deployed: the rollback history carries over.
        public PreviousVersionValue withVersionChange(Version newVersion, long nowMs) {
            return new PreviousVersionValue(artifactBase,
                                            currentVersion,
                                            newVersion,
                                            nowMs,
                                            rollbackCount,
                                            lastRollbackAt,
                                            failedVersions);
        }

        /// An automatic rollback from `failedVersion` to `target` committed at `nowMs`.
        public PreviousVersionValue withRollback(Version failedVersion, Version target, long nowMs) {
            var failed = new ArrayList<>(failedVersions);

            if (!failed.contains(failedVersion)) {
                failed.add(failedVersion);
            }

            return new PreviousVersionValue(artifactBase, failedVersion, target, nowMs, rollbackCount + 1, nowMs, failed);
        }

        /// Operator reset of the rollback budget; the failed-version record is kept.
        public PreviousVersionValue withRollbackCountReset() {
            return new PreviousVersionValue(artifactBase,
                                            previousVersion,
                                            currentVersion,
                                            updatedAt,
                                            0,
                                            0,
                                            failedVersions);
        }
    }

    record HttpNodeRouteValue(String artifactCoord, String sliceMethod, String state, int weight, long registeredAt) implements AetherValue {
        public static HttpNodeRouteValue httpNodeRouteValue(String artifactCoord, String sliceMethod) {
            return new HttpNodeRouteValue(artifactCoord, sliceMethod, "ACTIVE", 100, System.currentTimeMillis());
        }

        public static HttpNodeRouteValue httpNodeRouteValue(String artifactCoord,
                                                            String sliceMethod,
                                                            String state,
                                                            int weight) {
            return new HttpNodeRouteValue(artifactCoord, sliceMethod, state, weight, System.currentTimeMillis());
        }

        public HttpNodeRouteValue withState(String newState) {
            return new HttpNodeRouteValue(artifactCoord, sliceMethod, newState, weight, registeredAt);
        }

        public HttpNodeRouteValue withWeight(int newWeight) {
            return new HttpNodeRouteValue(artifactCoord, sliceMethod, state, newWeight, registeredAt);
        }

        public boolean isRoutable() {
            return "ACTIVE".equals(state) && weight > 0;
        }
    }

    record AlertThresholdValue(String metricName, double warningThreshold, double criticalThreshold, long updatedAt) implements AetherValue {
        public static AlertThresholdValue alertThresholdValue(String metricName, double warning, double critical) {
            return new AlertThresholdValue(metricName, warning, critical, System.currentTimeMillis());
        }

        public AlertThresholdValue withThresholds(double warning, double critical) {
            return new AlertThresholdValue(metricName, warning, critical, System.currentTimeMillis());
        }
    }

    record LogLevelValue(String loggerName, String level, long updatedAt) implements AetherValue {
        public static LogLevelValue logLevelValue(String loggerName, String level) {
            return new LogLevelValue(loggerName, level, System.currentTimeMillis());
        }
    }

    record ObservabilityConfigValue(String artifactBase,
                                    String methodName,
                                    boolean logging,
                                    boolean metrics,
                                    boolean spans,
                                    boolean tracing,
                                    int depth,
                                    long updatedAt) implements AetherValue {
        public static ObservabilityConfigValue observabilityConfigValue(String artifactBase,
                                                                        String methodName,
                                                                        boolean logging,
                                                                        boolean metrics,
                                                                        boolean spans,
                                                                        boolean tracing,
                                                                        int depth) {
            return new ObservabilityConfigValue(artifactBase,
                                                methodName,
                                                logging,
                                                metrics,
                                                spans,
                                                tracing,
                                                depth,
                                                System.currentTimeMillis());
        }
    }

    record ConfigValue(String key, String value, long updatedAt) implements AetherValue {
        public static ConfigValue configValue(String key, String value) {
            return new ConfigValue(key, value, System.currentTimeMillis());
        }
    }

    record WorkerSliceDirectiveValue(Artifact artifact,
                                     int targetInstances,
                                     String placement,
                                     Option<String> targetCommunity,
                                     long updatedAt) implements AetherValue {
        public static WorkerSliceDirectiveValue workerSliceDirectiveValue(Artifact artifact,
                                                                          int targetInstances,
                                                                          String placement) {
            return new WorkerSliceDirectiveValue(artifact,
                                                 targetInstances,
                                                 placement,
                                                 none(),
                                                 System.currentTimeMillis());
        }

        public static WorkerSliceDirectiveValue workerSliceDirectiveValue(Artifact artifact,
                                                                          int targetInstances,
                                                                          String placement,
                                                                          String targetCommunity) {
            return new WorkerSliceDirectiveValue(artifact,
                                                 targetInstances,
                                                 placement,
                                                 Option.option(targetCommunity),
                                                 System.currentTimeMillis());
        }

        public WorkerSliceDirectiveValue withInstances(int newCount) {
            return new WorkerSliceDirectiveValue(artifact,
                                                 newCount,
                                                 placement,
                                                 targetCommunity,
                                                 System.currentTimeMillis());
        }
    }

    /// Worker/core activation directive (worker-membership-spec §4 line 79): the leader-authored,
    /// node-keyed role assignment. Extended additively with `communityId` + `governorHint` so a
    /// WORKER directive carries its community assignment and a governor address hint through the
    /// already-canonical directive path — community assignment happens at the same moment as role
    /// assignment. Both are empty for a CORE directive (and for a community-less WORKER).
    ///
    /// Optional-field idiom mirrors [DhtPartitionOwnershipValue.ownerCommunityId]: empty-string is
    /// the canonical "absent" form, normalized in the compact constructor so a `null` from a
    /// wire/codec edge collapses to `""` (preserving `equals` with the role-only constructors).
    record ActivationDirectiveValue(String role, String communityId, String governorHint) implements AetherValue, CommunityFenced {
        public static final String CORE = "CORE";
        public static final String WORKER = "WORKER";

        public ActivationDirectiveValue {
            if (communityId == null) {
                communityId = "";
            }

            if (governorHint == null) {
                governorHint = "";
            }
        }

        /// Backward-compatible role-only constructor — pre-existing call sites pass a bare role;
        /// `communityId`/`governorHint` default empty (CORE or community-less WORKER semantics).
        public ActivationDirectiveValue(String role) {
            this(role, "", "");
        }

        /// H11 (#1840): the committed non-empty community of a NodeId is final; see [CommunityFenced].
        /// A CORE directive carries no community, so it fences nothing.
        @Override
        public String fenceCommunity() {
            return communityId;
        }

        public static ActivationDirectiveValue core() {
            return new ActivationDirectiveValue(CORE, "", "");
        }

        public static ActivationDirectiveValue worker() {
            return new ActivationDirectiveValue(WORKER, "", "");
        }

        /// Community-assigned WORKER directive — carries the committed `communityId` and a governor
        /// address hint alongside the WORKER role (§4 line 79).
        public static ActivationDirectiveValue worker(String communityId, String governorHint) {
            return new ActivationDirectiveValue(WORKER, communityId, governorHint);
        }
    }

    /// `currentKeyId` is the lost-update fence (RFC-0018, #570), added for #683 alongside the
    /// producer: the rotation route reads the committed record and writes `prior + 1`, so two
    /// concurrent ADMIN rotations — or one CLI retry after a client-side timeout, realistic on the
    /// emergency path this route exists for — both derive the same successor id from the same base.
    /// Without the fence consensus orders them and the cluster converges, but during the window a
    /// peer holding key A under id N+1 receives a datagram encrypted with key B under the SAME id:
    /// `resolveKey` SUCCEEDS and GCM tag verification then fails, so the failure surfaces as a
    /// decryption error rather than an unknown-key miss. [VersionFenced] makes the second writer a
    /// refused write instead, and the route confirms by re-reading the committed record.
    record GossipKeyRotationValue(int currentKeyId,
                                  String currentKey,
                                  int previousKeyId,
                                  String previousKey,
                                  long rotatedAt) implements AetherValue, VersionFenced {
        @Override
        public long fenceVersion() {
            return currentKeyId;
        }

        public static GossipKeyRotationValue gossipKeyRotationValue(int currentKeyId, String currentKey) {
            return new GossipKeyRotationValue(currentKeyId, currentKey, 0, "", System.currentTimeMillis());
        }

        public static GossipKeyRotationValue gossipKeyRotationValue(int currentKeyId,
                                                                    String currentKey,
                                                                    int previousKeyId,
                                                                    String previousKey) {
            return new GossipKeyRotationValue(currentKeyId,
                                              currentKey,
                                              previousKeyId,
                                              previousKey,
                                              System.currentTimeMillis());
        }

        public boolean hasPreviousKey() {
            return ! previousKey.isEmpty();
        }
    }

    record GovernorAnnouncementValue(NodeId governorId,
                                     int memberCount,
                                     List<NodeId> members,
                                     String tcpAddress,
                                     long announcedAt,
                                     long communityTerm,
                                     Epoch communityEpoch,
                                     Epoch observedCoreEpoch,
                                     HlcTimestamp transitionedAt,
                                     boolean dissolved) implements AetherValue, OwnerFenced<Epoch, NodeId>, LeaderAuthorized {
        @Override
        public NodeId fenceOwner() {
            return governorId;
        }

        /// Ownership fence (#345 piece 1a): the governor's `communityEpoch` is the fencing token, so
        /// the Rabia applier rejects a deposed governor's strictly-older-epoch announcement. Same-epoch
        /// re-writes (reannounce / dissolve) require the same governor identity.
        /// Only `withGovernorChange` bumps the epoch.
        @Override
        public Epoch fenceEpoch() {
            return communityEpoch;
        }

        public GovernorAnnouncementValue {
            members = members == null
                      ? List.of()
                      : List.copyOf(members);
            if (tcpAddress == null) {
                tcpAddress = "";
            }

            if (communityEpoch == null) {
                communityEpoch = Epoch.ZERO;
            }

            if (observedCoreEpoch == null) {
                observedCoreEpoch = Epoch.ZERO;
            }

            if (transitionedAt == null) {
                transitionedAt = HlcTimestamp.ZERO;
            }
        }

        public static GovernorAnnouncementValue governorAnnouncementValue(NodeId governorId, int memberCount) {
            return new GovernorAnnouncementValue(governorId,
                                                 memberCount,
                                                 List.of(),
                                                 "",
                                                 System.currentTimeMillis(),
                                                 0L,
                                                 Epoch.ZERO,
                                                 Epoch.ZERO,
                                                 HlcTimestamp.ZERO,
                                                 false);
        }

        public static GovernorAnnouncementValue governorAnnouncementValue(NodeId governorId,
                                                                          int memberCount,
                                                                          long announcedAt) {
            return new GovernorAnnouncementValue(governorId,
                                                 memberCount,
                                                 List.of(),
                                                 "",
                                                 announcedAt,
                                                 0L,
                                                 Epoch.ZERO,
                                                 Epoch.ZERO,
                                                 HlcTimestamp.ZERO,
                                                 false);
        }

        public static GovernorAnnouncementValue governorAnnouncementValue(NodeId governorId,
                                                                          List<NodeId> members,
                                                                          String tcpAddress) {
            return new GovernorAnnouncementValue(governorId,
                                                 members.size(),
                                                 List.copyOf(members),
                                                 tcpAddress,
                                                 System.currentTimeMillis(),
                                                 0L,
                                                 Epoch.ZERO,
                                                 Epoch.ZERO,
                                                 HlcTimestamp.ZERO,
                                                 false);
        }

        public static GovernorAnnouncementValue governorAnnouncementValue(NodeId governorId,
                                                                          int memberCount,
                                                                          List<NodeId> members,
                                                                          String tcpAddress,
                                                                          long announcedAt) {
            return new GovernorAnnouncementValue(governorId,
                                                 memberCount,
                                                 members,
                                                 tcpAddress,
                                                 announcedAt,
                                                 0L,
                                                 Epoch.ZERO,
                                                 Epoch.ZERO,
                                                 HlcTimestamp.ZERO,
                                                 false);
        }

        public static GovernorAnnouncementValue governorAnnouncementValue(NodeId governorId,
                                                                          List<NodeId> members,
                                                                          String tcpAddress,
                                                                          long announcedAt,
                                                                          long communityTerm,
                                                                          Epoch communityEpoch,
                                                                          Epoch observedCoreEpoch,
                                                                          HlcTimestamp transitionedAt,
                                                                          boolean dissolved) {
            return new GovernorAnnouncementValue(governorId,
                                                 members.size(),
                                                 members,
                                                 tcpAddress,
                                                 announcedAt,
                                                 communityTerm,
                                                 communityEpoch,
                                                 observedCoreEpoch,
                                                 transitionedAt,
                                                 dissolved);
        }

        public GovernorAnnouncementValue withMemberCount(int newCount) {
            return new GovernorAnnouncementValue(governorId,
                                                 newCount,
                                                 members,
                                                 tcpAddress,
                                                 System.currentTimeMillis(),
                                                 communityTerm,
                                                 communityEpoch,
                                                 observedCoreEpoch,
                                                 transitionedAt,
                                                 dissolved);
        }

        public GovernorAnnouncementValue withMembers(List<NodeId> newMembers, String newTcpAddress, Epoch coreEpoch) {
            return new GovernorAnnouncementValue(governorId,
                                                 newMembers.size(),
                                                 List.copyOf(newMembers),
                                                 newTcpAddress,
                                                 System.currentTimeMillis(),
                                                 communityTerm,
                                                 communityEpoch,
                                                 coreEpoch,
                                                 transitionedAt,
                                                 dissolved);
        }

        public GovernorAnnouncementValue withGovernorChange(NodeId newGovernor,
                                                            List<NodeId> newMembers,
                                                            String newTcpAddress,
                                                            Epoch newObservedCoreEpoch,
                                                            HlcTimestamp newTransitionedAt) {
            var nextTerm = communityTerm + 1;

            return new GovernorAnnouncementValue(newGovernor,
                                                 newMembers.size(),
                                                 List.copyOf(newMembers),
                                                 newTcpAddress,
                                                 System.currentTimeMillis(),
                                                 nextTerm,
                                                 Epoch.epoch(communityEpoch.incarnation(), nextTerm, 0L),
                                                 newObservedCoreEpoch,
                                                 newTransitionedAt,
                                                 false);
        }

        public GovernorAnnouncementValue withDissolved() {
            return new GovernorAnnouncementValue(governorId,
                                                 memberCount,
                                                 members,
                                                 tcpAddress,
                                                 System.currentTimeMillis(),
                                                 communityTerm,
                                                 communityEpoch,
                                                 observedCoreEpoch,
                                                 transitionedAt,
                                                 true);
        }
    }

    /// Three-phase model (D.3, 2026-05-11):
    /// - `COLD_BOOT` — cluster never had quorum. SWIM suppresses `FaultyObserved` for
    ///   never-healthy peers (preserves the cold-boot-during-formation invariant).
    ///   MembershipFsm structural bootstrap-safety suppresses STOPPED/DRAINING writes.
    ///   CTM auto-heal is suspended. Transition out: first time the cluster reaches a
    ///   quorum of present peers AND a leader is elected, sustained for `stableWindowMs`.
    /// - `NORMAL` — full failure semantics. No suppression anywhere.
    /// - `RECOVERING` — cluster previously reached NORMAL but lost quorum (e.g.,
    ///   compose-restart, network partition, sustained chaos). SWIM emits FaultyObserved
    ///   with NORMAL semantics — `everSeenHealthy` gate is bypassed because the peer was
    ///   visible-and-healthy in the prior NORMAL period. the leader FSM writes lifecycle
    ///   transitions normally. CTM auto-heal stays suspended (operator-free recovery is
    ///   the goal; provisioning resumes only after stability). Transition back to NORMAL:
    ///   quorum-stable for `recoveryStableWindowMs`.
    @Codec
    enum ClusterPhase {
        COLD_BOOT,
        NORMAL,
        RECOVERING,
        /// Wire sentinel (#964): an ordinal this node cannot name decodes here instead of throwing.
        /// Never NORMAL, so the topology action gated on NORMAL stays closed.
        /// Must stay LAST -- a new constant appended after it, or inserted before it, is read as
        /// UNKNOWN by an older node either way.
        UNKNOWN
    }

    record ClusterPhaseValue(ClusterPhase phase, long updatedAt) implements AetherValue {
        public ClusterPhaseValue {
            if (phase == null) {
                phase = ClusterPhase.COLD_BOOT;
            }
        }

        public static ClusterPhaseValue clusterPhaseValue(ClusterPhase phase) {
            return new ClusterPhaseValue(phase, System.currentTimeMillis());
        }

        public static ClusterPhaseValue clusterPhaseValue(ClusterPhase phase, long updatedAt) {
            return new ClusterPhaseValue(phase, updatedAt);
        }
    }

    /// The cluster's lineage and incarnation, under [AetherKey.ClusterIncarnationKey] (#1529 part 1).
    /// `lineageId` names the cluster's history across cold restarts; `incarnation` counts those restarts
    /// and dominates any per-incarnation counter (a consensus revision restarts with the cluster).
    /// `incarnationId` is a ULID minted fresh at every genesis mint, every restore and every `declare-genesis`
    /// (#1529 part 2, #1625, #1533) — and at nothing else: a leader change keeps it (the value is replicated
    /// state). Unlike the number, it is never reused, so two histories that happen to reach the same
    /// incarnation number (a restore whose predecessor never reached the backup, two lineages, or two
    /// clusters restored from one backup at once) stay distinguishable; the KV backup refuses to let one
    /// replace the other's head (#1533, `BACKUP_FORKED`). It is an identity, compared for equality only —
    /// ordering is the number's job.
    ///
    /// [VersionFenced] on `incarnation`, which gives two per-operation guarantees and no more: a genesis
    /// mint against an absent key is first-wins, and a Put through the fence is accepted only as the
    /// immediate successor of the committed value. A Remove is NOT fenced, and a restore deliberately goes
    /// Remove-then-Put to bypass the fence; a restore's monotonicity comes from the floor in
    /// `ClusterIncarnation.restoreCommands`, not from this fence.
    record ClusterIncarnationValue(String lineageId, long incarnation, String incarnationId) implements AetherValue, VersionFenced {
        public static final long GENESIS = 1L;

        public static ClusterIncarnationValue clusterIncarnationValue(String lineageId,
                                                                      long incarnation,
                                                                      String incarnationId) {
            return new ClusterIncarnationValue(lineageId, incarnation, incarnationId);
        }

        /// A brand-new cluster: a fresh lineage at the first incarnation, with a fresh incarnation id.
        public static ClusterIncarnationValue genesis(String lineageId, String incarnationId) {
            return new ClusterIncarnationValue(lineageId, GENESIS, incarnationId);
        }

        /// The same lineage, one incarnation later, under a fresh incarnation id — what a restore commits.
        public ClusterIncarnationValue next(String freshIncarnationId) {
            return new ClusterIncarnationValue(lineageId, incarnation + 1, freshIncarnationId);
        }

        @Override
        public long fenceVersion() {
            return incarnation;
        }
    }

    /// What a restore decision concluded (#1533), under [AetherKey.BackupRestoreKey].
    @Codec
    enum BackupRestoreOutcome {
        /// A restore of `commit` has started and not finished; a new leader resumes the same commit.
        IN_PROGRESS,
        /// The backup at `commit` was restored.
        RESTORED,
        /// Nothing to restore (an empty backup, or `[backup] restore = fresh`); genesis follows.
        FRESH,
        /// The KV already held cluster state — a live cluster is never restored over.
        SKIPPED_EXISTING_STATE,
        /// The deciding leader has no `[backup]` enabled.
        DISABLED,
        /// Wire sentinel: an ordinal this node cannot name decodes here instead of throwing. Not terminal,
        /// so a node that cannot name the outcome keeps its restore gate closed. Must stay LAST.
        UNKNOWN;
        /// Whether the decision is final — the restore gate opens only on these.
        public boolean isTerminal() {
            return this == RESTORED || this == FRESH || this == SKIPPED_EXISTING_STATE || this == DISABLED;
        }
    }

    /// The committed restore decision (#1533). `lineageId`/`incarnation`/`revision` are the restored
    /// document's header and `commit` its backup commit, for [BackupRestoreOutcome#IN_PROGRESS] and
    /// [BackupRestoreOutcome#RESTORED]; empty and zero otherwise.
    record BackupRestoreValue(BackupRestoreOutcome outcome,
                              String lineageId,
                              long incarnation,
                              long revision,
                              String commit) implements AetherValue {
        public static BackupRestoreValue backupRestoreValue(BackupRestoreOutcome outcome,
                                                            String lineageId,
                                                            long incarnation,
                                                            long revision,
                                                            String commit) {
            return new BackupRestoreValue(outcome, lineageId, incarnation, revision, commit);
        }

        /// A decision that restores nothing.
        public static BackupRestoreValue decided(BackupRestoreOutcome outcome) {
            return new BackupRestoreValue(outcome, "", 0L, 0L, "");
        }

        /// The same restore, finished.
        public BackupRestoreValue restored() {
            return new BackupRestoreValue(BackupRestoreOutcome.RESTORED, lineageId, incarnation, revision, commit);
        }
    }

    /// The recovery record of one stream partition, under [AetherKey.StreamPartitionRecoveryKey] (#1596, spec #1569
    /// §7.5.3): `state` and the SET of `reasons` it was flagged for. A flagged partition has no CAUGHT_UP row, no
    /// owner and no reads until operator resolution (AD14) clears it.
    ///
    /// Raising is idempotent (R7-2): [#raised] makes `old ∪ {reason}` and moves CONSISTENT or RESOLVED to FLAGGED,
    /// and when the result equals the committed value nothing is written -- so the same evidence always yields the
    /// same record and the same digest, and a flapping raiser leaves it unchanged, while a NEW reason or storage
    /// changes it, as the operator must see new evidence. [LeaderAuthorized]: only a leader-witnessed transaction
    /// with the exact previous value may write it, so two concurrent raises cannot lose each other's reason.
    /// Candidates, source and resolution (AD7/AD14) extend this record; clearing it is AD14's.
    record StreamPartitionRecoveryValue(PartitionRecoveryState state, Set<PartitionRecoveryReason> reasons) implements AetherValue, LeaderAuthorized {
        public StreamPartitionRecoveryValue {
            reasons = Set.copyOf(reasons);
        }

        public static StreamPartitionRecoveryValue streamPartitionRecoveryValue(PartitionRecoveryState state,
                                                                                Set<PartitionRecoveryReason> reasons) {
            return new StreamPartitionRecoveryValue(state, reasons);
        }

        /// The record after raising `reason` over `committed` (absent: never flagged).
        public static StreamPartitionRecoveryValue raised(Option<StreamPartitionRecoveryValue> committed,
                                                          PartitionRecoveryReason reason) {
            return committed.map(value -> value.with(reason))
                            .or(() -> new StreamPartitionRecoveryValue(PartitionRecoveryState.FLAGGED,
                                                                       Set.of(reason)));
        }

        private StreamPartitionRecoveryValue with(PartitionRecoveryReason reason) {
            var union = new HashSet<>(reasons);

            union.add(reason);

            return new StreamPartitionRecoveryValue(PartitionRecoveryState.FLAGGED, union);
        }
    }

    /// Where a partition's recovery stands (spec #1569 §7.5.3).
    @Codec
    enum PartitionRecoveryState {
        CONSISTENT,
        FLAGGED,
        RESOLVED,
        UNKNOWN
    }

    /// One reason a partition is flagged (spec #1569 §7.5.3): its `kind`, the copy it concerns when it concerns one
    /// (`storageId`: the node id until storage ULID identity exists), and the `evidence` -- deterministic facts
    /// (an offset, a pair of epochs), never a time, so the same evidence raises the same reason.
    @Codec
    record PartitionRecoveryReason(PartitionRecoveryReasonKind kind, Option<String> storageId, String evidence) {
        public static PartitionRecoveryReason partitionRecoveryReason(PartitionRecoveryReasonKind kind,
                                                                      Option<String> storageId,
                                                                      String evidence) {
            return new PartitionRecoveryReason(kind, storageId, evidence);
        }
    }

    /// The reasons of spec #1569 §7.5.3.
    @Codec
    enum PartitionRecoveryReasonKind {
        NO_CANDIDATE,
        HISTORY_MISSING,
        HISTORY_INCOMPLETE,
        MARKED_DIVERGED,
        DIVERGED,
        LOSS_BUDGET,
        CONTESTED,
        CARRIED_OVER,
        LOCAL_MISMATCH,
        DIVERGED_LATE_JOINER,
        UNKNOWN
    }

    /// Durable record of the operator's cluster-wide auto-heal enable/disable flag (#685), keyed by
    /// [AetherKey.AutoHealStateKey]. A read reflects the log applied LOCALLY: the disable becomes
    /// visible on a node when that node applies the committed Put — bounded by consensus latency, not
    /// zero; a node behind on apply answers the previous value until then. Absence of a record for the
    /// singleton key means auto-heal is enabled (the pre-#685 default) — never persisted as an explicit
    /// "enabled" record.
    record AutoHealStateValue(boolean enabled, String reason, long updatedAt) implements AetherValue {
        public AutoHealStateValue {
            if (reason == null) {
                reason = "";
            }
        }

        public static AutoHealStateValue autoHealStateValue(boolean enabled, String reason) {
            return new AutoHealStateValue(enabled, reason, System.currentTimeMillis());
        }

        public static AutoHealStateValue autoHealStateValue(boolean enabled, String reason, long updatedAt) {
            return new AutoHealStateValue(enabled, reason, updatedAt);
        }
    }

    @Codec
    /// How a node came to be in the cluster. Provenance only — nothing branches on it.
    ///
    /// #964 S2: `UNRECOGNISED` and `UNKNOWN` are DELIBERATELY separate, because they have different
    /// causes and an operator acts differently on each. `UNRECOGNISED` is local and self-inflicted —
    /// a config value this node could not parse, or no value at all — and is fixed by editing
    /// configuration. `UNKNOWN` is the wire sentinel: the node that wrote this record runs a newer
    /// `ProvisioningSource`, and it is fixed by finishing the rolling upgrade.
    ///
    /// Before they were split, both produced `UNKNOWN` and were indistinguishable at the point of use.
    /// That is the same conflation `SliceState` avoids by keeping its sentinel out of `STRING_TO_STATE`
    /// — a decode artifact must not be something a config file can ask for. A diagnostic that cannot
    /// tell its two causes apart has stopped discriminating.
    enum ProvisioningSource {
        CTM,
        MANUAL,
        /// Local: unparseable or absent configuration. Never produced by decoding.
        UNRECOGNISED,
        /// Wire sentinel (#964) — see the type docstring. Must stay LAST.
        UNKNOWN
    }

    /// Phase 1 step J — replicated mirror of the in-process `JOIN_DEADLINE` scheduler
    /// entry. `deadlineMs` is wall-clock millis (epoch) when the join deadline fires;
    /// `setAt` is the HLC stamp of the originating JOINING-entry transition (provides
    /// causal ordering across leader takeover for observers reconstructing the deadline).
    /// Pure observability atom — see [AetherKey.JoinDeadlineKey] for trigger semantics.
    record JoinDeadlineValue(long deadlineMs, HlcTimestamp setAt) implements AetherValue {
        public JoinDeadlineValue {
            if (setAt == null) {
                setAt = HlcTimestamp.ZERO;
            }
        }

        public static JoinDeadlineValue joinDeadlineValue(long deadlineMs, HlcTimestamp setAt) {
            return new JoinDeadlineValue(deadlineMs, setAt);
        }
    }

    /// Phase 1 step J — replicated mirror of the in-process `DRAIN_DEADLINE` scheduler
    /// entry. `deadlineMs` is wall-clock millis (epoch) when the drain hard-deadline
    /// fires; `setAt` is the HLC stamp of the DRAINING-entry transition. Pure
    /// observability atom — see [AetherKey.DrainDeadlineKey] for trigger semantics.
    record DrainDeadlineValue(long deadlineMs, HlcTimestamp setAt) implements AetherValue {
        public DrainDeadlineValue {
            if (setAt == null) {
                setAt = HlcTimestamp.ZERO;
            }
        }

        public static DrainDeadlineValue drainDeadlineValue(long deadlineMs, HlcTimestamp setAt) {
            return new DrainDeadlineValue(deadlineMs, setAt);
        }
    }

    record NodeArtifactValue(SliceState state,
                             Option<String> failureReason,
                             boolean fatal,
                             int instanceNumber,
                             List<String> methods,
                             long transitionedAt) implements AetherValue {
        public static NodeArtifactValue nodeArtifactValue(SliceState state) {
            return new NodeArtifactValue(state, Option.none(), false, 0, List.of(), defaultTransitionedAt(state));
        }

        public static NodeArtifactValue nodeArtifactValue(SliceState state, long transitionedAt) {
            return new NodeArtifactValue(state, Option.none(), false, 0, List.of(), transitionedAt);
        }

        /// #930 — carries the same explicit `unrecognised` disposition as
        /// [SliceNodeValue#failedSliceNodeValue(Cause, Unrecognised)], for the same reason.
        ///
        /// **No production caller reaches this factory.** Both production sites that build a FAILED
        /// `NodeArtifactValue` — `NodeDeploymentState.updateSliceStateWithRetry` and
        /// `updateSliceStateWithExtraCommandsAndRetry` — construct the record directly, copying
        /// `fatal` across from the already-classified `SliceNodeValue` rather than re-classifying.
        /// So the `fatal` flag is decided exactly once per failure, at `failedSliceNodeValue`. Kept
        /// with the explicit parameter rather than deleted so that a future caller cannot reach a
        /// silent default through it either.
        public static NodeArtifactValue failedNodeArtifactValue(Cause cause, Unrecognised unrecognised) {
            var classified = SliceLoadingFailure.classify(cause, unrecognised);

            return new NodeArtifactValue(SliceState.FAILED,
                                         Option.option(classified.message()),
                                         classified.isFatal(),
                                         0,
                                         List.of(),
                                         0L);
        }

        public static NodeArtifactValue activeNodeArtifactValue(int instanceNumber, List<String> methods) {
            return new NodeArtifactValue(SliceState.ACTIVE,
                                         Option.none(),
                                         false,
                                         instanceNumber,
                                         List.copyOf(methods),
                                         0L);
        }

        public NodeArtifactValue withState(SliceState newState) {
            if (newState == SliceState.ACTIVE) {
                return new NodeArtifactValue(newState, Option.none(), false, instanceNumber, methods, 0L);
            }

            return new NodeArtifactValue(newState, Option.none(), false, 0, List.of(), defaultTransitionedAt(newState));
        }

        public boolean hasEndpoints() {
            return state == SliceState.ACTIVE && !methods.isEmpty();
        }

        private static long defaultTransitionedAt(SliceState state) {
            return state.isTransitional()
                   ? System.currentTimeMillis()
                   : 0L;
        }
    }

    record NodeRoutesValue(List<RouteEntry> routes, Epoch observedCoreEpoch) implements AetherValue {
        public NodeRoutesValue {
            routes = routes == null
                     ? List.of()
                     : List.copyOf(routes);
            if (observedCoreEpoch == null) {
                observedCoreEpoch = Epoch.ZERO;
            }
        }

        /// `security` is the policy the publishing node ENFORCES (its declared policy with the node's security
        /// overrides applied); `declaredSecurity` is the slice-declared policy BEFORE any override (#1659). An
        /// ingress node that does not host the route re-applies ITS OWN committed overrides to `declaredSecurity`,
        /// so an override takes effect -- and a relaxed one relaxes -- at every ingress without waiting for the
        /// hosting nodes to republish.
        public record RouteEntry(String httpMethod,
                                 String pathPrefix,
                                 String sliceMethod,
                                 String state,
                                 int weight,
                                 long registeredAt,
                                 String security,
                                 String declaredSecurity,
                                 int pathArity,
                                 List<String> spacers) {
            public RouteEntry {
                spacers = List.copyOf(spacers);
            }

            public static RouteEntry activeRoute(String httpMethod,
                                                 String pathPrefix,
                                                 String sliceMethod,
                                                 String security) {
                return activeRoute(httpMethod, pathPrefix, sliceMethod, security, security);
            }

            public static RouteEntry activeRoute(String httpMethod,
                                                 String pathPrefix,
                                                 String sliceMethod,
                                                 String security,
                                                 String declaredSecurity) {
                return activeRoute(httpMethod, pathPrefix, sliceMethod, security, declaredSecurity, 0, List.of());
            }

            /// #1678: `pathArity` and `spacers` are the route's SHAPE beyond its base path. Sibling routes of one
            /// slice share `pathPrefix` (`GET /orders/{id}` and `GET /orders/{id}/admin` are both `/orders/`); the
            /// shape is what lets a node that does not host the route pick the sibling a request is served by.
            public static RouteEntry activeRoute(String httpMethod,
                                                 String pathPrefix,
                                                 String sliceMethod,
                                                 String security,
                                                 String declaredSecurity,
                                                 int pathArity,
                                                 List<String> spacers) {
                return new RouteEntry(httpMethod,
                                      pathPrefix,
                                      sliceMethod,
                                      "ACTIVE",
                                      100,
                                      System.currentTimeMillis(),
                                      security,
                                      declaredSecurity,
                                      pathArity,
                                      spacers);
            }

            public static RouteEntry activeRoute(String httpMethod, String pathPrefix, String sliceMethod) {
                return activeRoute(httpMethod, pathPrefix, sliceMethod, "PUBLIC");
            }

            public boolean isRoutable() {
                return "ACTIVE".equals(state) && weight > 0;
            }
        }

        public static NodeRoutesValue empty() {
            return new NodeRoutesValue(List.of(), Epoch.ZERO);
        }

        public static NodeRoutesValue nodeRoutesValue(List<RouteEntry> routes) {
            return new NodeRoutesValue(List.copyOf(routes), Epoch.ZERO);
        }

        public static NodeRoutesValue nodeRoutesValue(List<RouteEntry> routes, Epoch observedCoreEpoch) {
            return new NodeRoutesValue(List.copyOf(routes), observedCoreEpoch);
        }

        public NodeRoutesValue withObservedCoreEpoch(Epoch newEpoch) {
            return new NodeRoutesValue(routes, newEpoch);
        }
    }

    /// Datasource names are cluster-global (`BlueprintArtifactParser` derives them from the
    /// migration script path, so two blueprints using the default layout both claim `"database"`),
    /// therefore the record must name the blueprint that owns the migration set. Ownership is
    /// REQUIRED, not optional: the deploy-time gate in `BlueprintService` refuses to write a record
    /// for a datasource another blueprint already migrates, and the activation gate in
    /// `ClusterDeploymentState.areSchemasReady` matches records to slices by this owner so one
    /// blueprint's failed migration cannot hold an unrelated blueprint's slices.
    record SchemaVersionValue(String datasourceName,
                              int currentVersion,
                              String lastMigration,
                              SchemaStatus status,
                              String artifactCoords,
                              BlueprintId owningBlueprint,
                              int attemptCount,
                              long updatedAt) implements AetherValue {
        public static SchemaVersionValue schemaVersionValue(String datasourceName,
                                                            int currentVersion,
                                                            String lastMigration,
                                                            SchemaStatus status,
                                                            String artifactCoords,
                                                            BlueprintId owningBlueprint) {
            return new SchemaVersionValue(datasourceName,
                                          currentVersion,
                                          lastMigration,
                                          status,
                                          artifactCoords,
                                          owningBlueprint,
                                          0,
                                          System.currentTimeMillis());
        }

        public static SchemaVersionValue schemaVersionValue(String datasourceName,
                                                            int currentVersion,
                                                            String lastMigration,
                                                            SchemaStatus status,
                                                            String artifactCoords,
                                                            BlueprintId owningBlueprint,
                                                            int attemptCount) {
            return new SchemaVersionValue(datasourceName,
                                          currentVersion,
                                          lastMigration,
                                          status,
                                          artifactCoords,
                                          owningBlueprint,
                                          attemptCount,
                                          System.currentTimeMillis());
        }

        /// The same record with only `status` replaced — every other field, `updatedAt` included, kept.
        public SchemaVersionValue withStatus(SchemaStatus newStatus) {
            return new SchemaVersionValue(datasourceName,
                                          currentVersion,
                                          lastMigration,
                                          newStatus,
                                          artifactCoords,
                                          owningBlueprint,
                                          attemptCount,
                                          updatedAt);
        }
    }

    /// `lockVersion` is the lost-update fence (RFC-0018, #570) added for #766: the lock claim is a
    /// compare-and-put on this chain, so two nodes that both read the lock free (absent OR expired)
    /// and both write cannot both commit — the applier accepts only the immediate successor of the
    /// committed version, and a first write against an absent key. The acquirer derives the version
    /// from the committed value ([#nextVersion]) and confirms after its apply resolves by re-reading
    /// the committed value and comparing it to the one it wrote ([VersionFenced]'s protocol).
    ///
    /// #806: the holder renews the lease while it works (each renewal is the next `lockVersion` with a
    /// later `expiresAt`), and the lock is a [WitnessedRemoval] — its release carries the value the holder
    /// last wrote, so a holder whose claim was superseded cannot delete its successor's lock.
    ///
    /// Wire-format note: this adds a record component to a committed AetherValue (generated codec and
    /// `KVStoreSerializer` text form both change), following the #805 `outcomeVersion` precedent. rc4
    /// promises no cross-rc wire compatibility; that contract is #434/#666's.
    record SchemaMigrationLockValue(String datasourceName,
                                    NodeId heldBy,
                                    long acquiredAt,
                                    long expiresAt,
                                    long lockVersion) implements AetherValue, WitnessedRemoval {
        /// The version a claim against an ABSENT key carries; the applier does not fence a first write.
        public static final long FIRST_VERSION = 1L;

        public static SchemaMigrationLockValue schemaMigrationLockValue(String datasourceName,
                                                                        NodeId heldBy,
                                                                        long ttlMs,
                                                                        long lockVersion) {
            var now = System.currentTimeMillis();

            return new SchemaMigrationLockValue(datasourceName, heldBy, now, now + ttlMs, lockVersion);
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }

        /// The version a claim that takes over THIS committed value (held-and-expired) must carry.
        public long nextVersion() {
            return lockVersion + 1;
        }

        @Override
        public long fenceVersion() {
            return lockVersion;
        }
    }

    @Codec
    enum SchemaStatus {
        PENDING,
        MIGRATING,
        COMPLETED,
        FAILED,
        /// Wire sentinel (#964): an ordinal this node cannot name decodes here instead of throwing.
        /// Never re-arms a migration -- an unreadable schema status must not start one.
        /// Must stay LAST -- a new constant appended after it, or inserted before it, is read as
        /// UNKNOWN by an older node either way.
        UNKNOWN
    }

    record AbTestValue(String testId,
                       ArtifactBase artifactBase,
                       Version baselineVersion,
                       String variantVersionsJson,
                       String state,
                       String splitRuleJson,
                       int newWeight,
                       int oldWeight,
                       String blueprintId,
                       long createdAt,
                       long updatedAt) implements AetherValue {
        public static AbTestValue abTestValue(String testId,
                                              ArtifactBase artifactBase,
                                              Version baselineVersion,
                                              String variantVersionsJson,
                                              String state,
                                              String splitRuleJson,
                                              int newWeight,
                                              int oldWeight,
                                              String blueprintId,
                                              long createdAt,
                                              long updatedAt) {
            return new AbTestValue(testId,
                                   artifactBase,
                                   baselineVersion,
                                   variantVersionsJson,
                                   state,
                                   splitRuleJson,
                                   newWeight,
                                   oldWeight,
                                   blueprintId,
                                   createdAt,
                                   updatedAt);
        }
    }

    /// Committed assignee of one consumer group's partition (#1271). Written by the leader-only
    /// `ConsumerAssignmentWriter`; read by every node's attach admission and by the applier's cross-key
    /// guard on [StreamCursorCheckpointValue] writes. `epoch` is `Epoch(rabiaTerm, assignmentTerm)`, so it
    /// advances on a leader change and on every same-term reassignment; [EpochBearing] fences a deposed
    /// leader's stale write to this record. Replaces the never-used `StreamPartitionAssignmentValue`.
    record ConsumerAssignmentValue(NodeId assignee, Epoch epoch, long assignmentTerm, HlcTimestamp assignedAt) implements AetherValue, EpochBearing<Epoch>, AssignmentTokenBearing {
        /// The token a checkpoint must carry to be admitted: this exact assignee at this exact epoch.
        /// Equality on both halves is what makes an A→B→A flap refuse A's writes from its FIRST tenure.
        @Codec
        public record AssignmentToken(NodeId assignee, Epoch epoch) {
            public static AssignmentToken assignmentToken(NodeId assignee, Epoch epoch) {
                return new AssignmentToken(assignee, epoch);
            }
        }

        @Override
        public Epoch fenceEpoch() {
            return epoch;
        }

        @Override
        public Object guardToken() {
            return token();
        }

        public AssignmentToken token() {
            return AssignmentToken.assignmentToken(assignee, epoch);
        }

        public static ConsumerAssignmentValue consumerAssignmentValue(NodeId assignee,
                                                                      Epoch epoch,
                                                                      long assignmentTerm,
                                                                      HlcTimestamp assignedAt) {
            return new ConsumerAssignmentValue(assignee, epoch, assignmentTerm, assignedAt);
        }
    }

    /// Consensus-visible consumer cursor (#488), guarded TWICE by the applier: `token` names the committed
    /// assignment it was written under (#1271 — admitted only while that token is the committed
    /// [ConsumerAssignmentValue]'s, `AssignmentGuarded`), and `rewindIncarnation`/`rewindGeneration`/
    /// `rewindSequence` are the [RewindEpoch] it was committed under (#1333 — through [EpochBearing] a put stamped with a STRICTLY
    /// older epoch is refused, so a zombie consumer's pre-rewind checkpoint cannot move the cursor forward
    /// again; `0/0` for a group never rewound). The two arms are pure predicates the applier ORs, so a
    /// checkpoint lands only when BOTH admit it, whichever is evaluated first.
    ///
    /// `rewind` marks the REWIND RECORD itself — the put a projection rebuild makes to move the group's
    /// cursor under a freshly minted epoch. It MINTS that epoch ([EpochBearing#mintsEpoch]), so the applier
    /// refuses it unless strictly newer than the committed record; a consumer's checkpoint (`rewind = false`)
    /// at the SAME epoch stays accepted. The rewind record carries the committed assignee's token like any
    /// other write to this key — a rebuild on a node the record does not name is refused by the guard.
    record StreamCursorCheckpointValue(long committedOffset,
                                       long commitTimestamp,
                                       ConsumerAssignmentValue.AssignmentToken token,
                                       long rewindIncarnation,
                                       long rewindGeneration,
                                       long rewindSequence,
                                       boolean rewind) implements AetherValue, AssignmentTokenBearing, EpochBearing<RewindEpoch> {
        @Override
        public Object guardToken() {
            return token;
        }

        public static StreamCursorCheckpointValue streamCursorCheckpointValue(long committedOffset,
                                                                              ConsumerAssignmentValue.AssignmentToken token) {
            return streamCursorCheckpointValue(committedOffset, token, RewindEpoch.NONE);
        }

        public static StreamCursorCheckpointValue streamCursorCheckpointValue(long committedOffset,
                                                                              ConsumerAssignmentValue.AssignmentToken token,
                                                                              RewindEpoch epoch) {
            return new StreamCursorCheckpointValue(committedOffset,
                                                   System.currentTimeMillis(),
                                                   token,
                                                   epoch.incarnation(),
                                                   epoch.generation(),
                                                   epoch.rewind(),
                                                   false);
        }

        /// The rewind record: the group's cursor moved to `fromOffset` under the minted `epoch`, written
        /// under the committed assignment `token`.
        public static StreamCursorCheckpointValue rewindRecord(long fromOffset,
                                                               ConsumerAssignmentValue.AssignmentToken token,
                                                               RewindEpoch epoch) {
            return new StreamCursorCheckpointValue(fromOffset,
                                                   System.currentTimeMillis(),
                                                   token,
                                                   epoch.incarnation(),
                                                   epoch.generation(),
                                                   epoch.rewind(),
                                                   true);
        }

        public RewindEpoch rewindEpoch() {
            return RewindEpoch.rewindEpoch(rewindIncarnation, rewindGeneration, rewindSequence);
        }

        @Override
        public RewindEpoch fenceEpoch() {
            return rewindEpoch();
        }

        @Override
        public boolean mintsEpoch() {
            return rewind;
        }
    }

    /// Payload of an [AetherKey.EntityKeyspaceRegistrationKey] — the fact the leader-only ownership
    /// writer cannot derive for itself: how many `(entity:<keyspace>, partition)` arcs the keyspace
    /// spreads over, so it can mint an ownership record for each. Taken from the keyspace's
    /// `DurableEntityConfig.partitionCount` at provisioning time, which is the first moment it is known
    /// (the manifest carries only the config SECTION name, not the section's contents). The OTHER fact
    /// the writer needs — which nodes host the keyspace — lives in the per-node KEY, not here: the set
    /// of committed registration keys IS the hosting set.
    record EntityKeyspaceRegistrationValue(int partitionCount) implements AetherValue {
        public static EntityKeyspaceRegistrationValue entityKeyspaceRegistrationValue(int partitionCount) {
            return new EntityKeyspaceRegistrationValue(partitionCount);
        }
    }

    record StreamRegistrationValue(NodeId nodeId, String consumerGroup, boolean batchMode, String eventType) implements AetherValue {
        public static StreamRegistrationValue streamRegistrationValue(NodeId nodeId,
                                                                      String consumerGroup,
                                                                      boolean batchMode,
                                                                      String eventType) {
            return new StreamRegistrationValue(nodeId, consumerGroup, batchMode, eventType);
        }
    }

    /// `walBytes` (#634-3): live stream-WAL bytes on the reporting node — non-zero only for the
    /// `streams` instance, whose disk footprint was otherwise under-reported by the entire WAL (the
    /// WAL is a sibling directory of the segment store, not a tier).
    record StorageStatusValue(String instanceName,
                              List<TierStatus> tiers,
                              String readinessState,
                              boolean isReadReady,
                              boolean isWriteReady,
                              long lastSnapshotEpoch,
                              long lastSnapshotTimestamp,
                              long walBytes,
                              long updatedAt) implements AetherValue {
        public record TierStatus(String level, long usedBytes, long maxBytes) {
            public static TierStatus tierStatus(String level, long usedBytes, long maxBytes) {
                return new TierStatus(level, usedBytes, maxBytes);
            }
        }

        public static StorageStatusValue storageStatusValue(String instanceName,
                                                            List<TierStatus> tiers,
                                                            String readinessState,
                                                            boolean isReadReady,
                                                            boolean isWriteReady,
                                                            long lastSnapshotEpoch,
                                                            long lastSnapshotTimestamp,
                                                            long walBytes) {
            return new StorageStatusValue(instanceName,
                                          List.copyOf(tiers),
                                          readinessState,
                                          isReadReady,
                                          isWriteReady,
                                          lastSnapshotEpoch,
                                          lastSnapshotTimestamp,
                                          walBytes,
                                          System.currentTimeMillis());
        }
    }

    /// Desired cluster shape, per source and per role (RFC-0017 C1).
    ///
    /// `desiredTopology` REPLACES the former stored `coreCount`. That field was a core-only scalar
    /// that scale operations rewrote while leaving `tomlContent` untouched, so after any scale the
    /// two representations of desired size disagreed — and it could not express
    /// "3 cores in hetzner-eu + 5 workers in aws-us" at all, which is what cores need in order to
    /// provision workers themselves.
    ///
    /// [#coreCount] is now DERIVED from this map rather than stored alongside it, so the two can
    /// never drift: there is one authoritative representation and one way to read it.
    ///
    /// The role is carried as a `String` because `aether/slice` deliberately does not depend on
    /// `aether-config` (where `NodeRole` lives); the deployment layer converts at its boundary.
    record TopologyEntry(String sourceName, String role, int count) {
        public static final String CORE_ROLE = "core";

        public static TopologyEntry topologyEntry(String sourceName, String role, int count) {
            return new TopologyEntry(sourceName, role, count);
        }

        public boolean isCore() {
            return CORE_ROLE.equalsIgnoreCase(role);
        }
    }

    /// The committed cluster configuration.
    ///
    /// `tomlContent` is TYPED by whether an operator source configuration exists (#1812): [Option#none()]
    /// is the self-bootstrap seed ([#bootstrapSeed]), written at formation before any `cluster apply`, and
    /// [Option#some] carries the committed, parseable TOML. The seed used to be the empty string, so "no
    /// config yet" and "config" shared one type and each reader distinguished them ad hoc: some guarded
    /// with `isBlank()`, others parsed unguarded and failed on the seed with "no config_version", which
    /// disabled provisioning on every seed-only cluster. A reader now cannot reach the TOML without
    /// deciding what the seed means.
    record ClusterConfigValue(Option<String> tomlContent,
                              String clusterName,
                              String version,
                              List<TopologyEntry> desiredTopology,
                              int coreMin,
                              int coreMax,
                              String deploymentType,
                              long configVersion,
                              long updatedAt) implements AetherValue, VersionFenced {
        public ClusterConfigValue {
            desiredTopology = List.copyOf(desiredTopology);
        }

        /// Lost-update fence version (RFC-0018, #570): the applier rejects a `Put` of this value
        /// unless its `configVersion` is the immediate successor of the committed one. Every write
        /// site therefore derives from the CURRENT committed value and bumps by exactly one — which
        /// all six existing sites already did ([#withDesiredCount] and friends bump; the two
        /// bootstrap seeds write against an absent key, which the fence does not guard). A rejected
        /// write is invisible in the apply result (batch merging), so writers confirm by re-reading
        /// committed state and checking their change landed.
        @Override
        public long fenceVersion() {
            return configVersion;
        }

        /// Derived — never stored. Total CORE nodes across every source.
        public int coreCount() {
            return desiredTopology.stream()
                                  .filter(TopologyEntry::isCore)
                                  .mapToInt(TopologyEntry::count)
                                  .sum();
        }

        /// Desired count for one (source, role), or 0 when the pair is not in the topology.
        public int desiredCountFor(String sourceName, String role) {
            return desiredTopology.stream()
                                  .filter(entry -> entry.sourceName()
                                                        .equals(sourceName) && entry.role()
                                                                                    .equalsIgnoreCase(role))
                                  .mapToInt(TopologyEntry::count)
                                  .findFirst()
                                  .orElse(0);
        }

        /// Sources declaring an entry for `role`, in topology order, without duplicates.
        ///
        /// This is what makes "scale cores to N" answerable without guessing: exactly one source
        /// means the request is unambiguous, several means it genuinely does not say which source
        /// absorbs the change. The former core-only scalar hid that distinction by overwriting a
        /// single number regardless.
        public List<String> sourcesWithRole(String role) {
            return desiredTopology.stream()
                                  .filter(entry -> entry.role()
                                                        .equalsIgnoreCase(role))
                                  .map(TopologyEntry::sourceName)
                                  .distinct()
                                  .toList();
        }

        /// True when the topology already declares this (source, role).
        ///
        /// [#withDesiredCount] APPENDS an absent pair, which is right for composing a topology and
        /// wrong for a scale request: a mistyped source name would silently become a new entry that
        /// provisioning then tries to satisfy. Scale callers gate on this first.
        public boolean declares(String sourceName, String role) {
            return desiredTopology.stream()
                                  .anyMatch(entry -> entry.sourceName()
                                                          .equals(sourceName) && entry.role()
                                                                                      .equalsIgnoreCase(role));
        }

        /// Replace the desired count for one (source, role), preserving every other entry, and bump
        /// the config version. Adds the pair when absent.
        public ClusterConfigValue withDesiredCount(String sourceName, String role, int count) {
            var updated = new ArrayList<TopologyEntry>();
            var replaced = false;

            for (var entry : desiredTopology) {
                if (entry.sourceName().equals(sourceName) && entry.role().equalsIgnoreCase(role)) {
                    updated.add(new TopologyEntry(sourceName, role, count));
                    replaced = true;
                } else {
                    updated.add(entry);
                }
            }

            if (!replaced) {
                updated.add(new TopologyEntry(sourceName, role, count));
            }

            return new ClusterConfigValue(tomlContent,
                                          clusterName,
                                          version,
                                          List.copyOf(updated),
                                          coreMin,
                                          coreMax,
                                          deploymentType,
                                          configVersion + 1,
                                          System.currentTimeMillis());
        }

        /// The self-bootstrap seed: desired shape only, no operator source configuration.
        public static ClusterConfigValue bootstrapSeed(String clusterName,
                                                       String version,
                                                       List<TopologyEntry> desiredTopology,
                                                       int coreMin,
                                                       int coreMax,
                                                       String deploymentType,
                                                       long configVersion) {
            return new ClusterConfigValue(Option.none(),
                                          clusterName,
                                          version,
                                          desiredTopology,
                                          coreMin,
                                          coreMax,
                                          deploymentType,
                                          configVersion,
                                          System.currentTimeMillis());
        }

        /// A committed operator configuration; `tomlContent` is the applied, parsed-and-validated TOML.
        public static ClusterConfigValue clusterConfigValue(String tomlContent,
                                                            String clusterName,
                                                            String version,
                                                            List<TopologyEntry> desiredTopology,
                                                            int coreMin,
                                                            int coreMax,
                                                            String deploymentType,
                                                            long configVersion) {
            return new ClusterConfigValue(Option.some(tomlContent),
                                          clusterName,
                                          version,
                                          desiredTopology,
                                          coreMin,
                                          coreMax,
                                          deploymentType,
                                          configVersion,
                                          System.currentTimeMillis());
        }

        public static ClusterConfigValue clusterConfigValue(String tomlContent,
                                                            String clusterName,
                                                            String version,
                                                            List<TopologyEntry> desiredTopology,
                                                            int coreMin,
                                                            int coreMax,
                                                            String deploymentType,
                                                            long configVersion,
                                                            long updatedAt) {
            return new ClusterConfigValue(Option.some(tomlContent),
                                          clusterName,
                                          version,
                                          desiredTopology,
                                          coreMin,
                                          coreMax,
                                          deploymentType,
                                          configVersion,
                                          updatedAt);
        }

        public ClusterConfigValue withIncrementedVersion() {
            return new ClusterConfigValue(tomlContent,
                                          clusterName,
                                          version,
                                          desiredTopology,
                                          coreMin,
                                          coreMax,
                                          deploymentType,
                                          configVersion + 1,
                                          System.currentTimeMillis());
        }
    }

    record ApiKeyValue(String keyId,
                       String keyHash,
                       long createdAt,
                       long expiresAt,
                       String status,
                       long revokedAt,
                       long gracePeriodMs,
                       String authorizationRole) implements AetherValue {
        static final String ACTIVE = "ACTIVE";
        static final String REVOKED = "REVOKED";
        static final String EXPIRED = "EXPIRED";
        public static final String DEFAULT_ROLE = "VIEWER";

        public static ApiKeyValue apiKeyValue(String keyId, String keyHash, long gracePeriodMs) {
            return new ApiKeyValue(keyId,
                                   keyHash,
                                   System.currentTimeMillis(),
                                   - 1,
                                   ACTIVE,
                                   - 1,
                                   gracePeriodMs,
                                   DEFAULT_ROLE);
        }

        public static ApiKeyValue apiKeyValue(String keyId,
                                              String keyHash,
                                              long gracePeriodMs,
                                              String authorizationRole) {
            return new ApiKeyValue(keyId,
                                   keyHash,
                                   System.currentTimeMillis(),
                                   - 1,
                                   ACTIVE,
                                   - 1,
                                   gracePeriodMs,
                                   authorizationRole);
        }

        public static ApiKeyValue apiKeyValue(String keyId,
                                              String keyHash,
                                              long createdAt,
                                              long expiresAt,
                                              String status,
                                              long revokedAt,
                                              long gracePeriodMs,
                                              String authorizationRole) {
            return new ApiKeyValue(keyId,
                                   keyHash,
                                   createdAt,
                                   expiresAt,
                                   status,
                                   revokedAt,
                                   gracePeriodMs,
                                   authorizationRole);
        }

        public boolean isActive() {
            return ACTIVE.equals(status);
        }

        public boolean isRevoked() {
            return REVOKED.equals(status);
        }

        public boolean isInGracePeriod() {
            return isRevoked()
                   && revokedAt > 0
                   && System.currentTimeMillis() < revokedAt + gracePeriodMs;
        }

        public boolean isValidForAuth() {
            return isActive() || isInGracePeriod();
        }

        public ApiKeyValue withRevoked(long gracePeriod) {
            return new ApiKeyValue(keyId,
                                   keyHash,
                                   createdAt,
                                   expiresAt,
                                   REVOKED,
                                   System.currentTimeMillis(),
                                   gracePeriod,
                                   authorizationRole);
        }

        public ApiKeyValue withExpired() {
            return new ApiKeyValue(keyId,
                                   keyHash,
                                   createdAt,
                                   expiresAt,
                                   EXPIRED,
                                   revokedAt,
                                   gracePeriodMs,
                                   authorizationRole);
        }
    }

    record ApiKeyAuditValue(String keyId, String action, long timestamp, String operatorHint) implements AetherValue {
        public static final String ACTION_CREATED = "CREATED";
        public static final String ACTION_ROTATED = "ROTATED";
        public static final String ACTION_REVOKED = "REVOKED";
        public static final String ACTION_EXPIRED = "EXPIRED";

        public static ApiKeyAuditValue apiKeyAuditValue(String keyId, String action, String operatorHint) {
            return new ApiKeyAuditValue(keyId, action, System.currentTimeMillis(), operatorHint);
        }

        public static ApiKeyAuditValue apiKeyAuditValue(String keyId,
                                                        String action,
                                                        long timestamp,
                                                        String operatorHint) {
            return new ApiKeyAuditValue(keyId, action, timestamp, operatorHint);
        }
    }

    record ConsumerGroupValue(NodeId assignedTo, String consumerId, long assignedAt) implements AetherValue {
        public static ConsumerGroupValue consumerGroupValue(NodeId assignedTo, String consumerId) {
            return new ConsumerGroupValue(assignedTo, consumerId, System.currentTimeMillis());
        }
    }

    /// #1278: fenced on the stream's incarnation ([IncarnationFenced]), so while a life of the name is committed no
    /// other life can commit over it — concurrent first creates resolve first-wins, and a recreate commits only after
    /// the removal of the old life has applied.
    record StreamConfigValue(StreamConfig config, long createdAt) implements AetherValue, IncarnationFenced {
        @Override
        public long fenceIncarnation() {
            return config.incarnation();
        }

        public static StreamConfigValue streamConfigValue(StreamConfig config) {
            return new StreamConfigValue(config, System.currentTimeMillis());
        }

        public static StreamConfigValue streamConfigValue(StreamConfig config, long createdAt) {
            return new StreamConfigValue(config, createdAt);
        }
    }

    record DhtPartitionOwnershipValue(NodeId ownerNodeId,
                                      String ownerCommunityId,
                                      Epoch ownerEpoch,
                                      long ownershipTerm,
                                      HlcTimestamp transferredAt) implements AetherValue, EpochBearing<Epoch> {
        /// Ownership fence (#345 piece 1a): the owner's `ownerEpoch` is the fencing token, so the
        /// Rabia applier rejects a deposed owner's strictly-older-epoch ownership write. The writer
        /// (`BootstrapModule.buildCorePartitionCommand`) couples `ownerEpoch.localCounter ==
        /// ownershipTerm` (#345 DHT parity), so a stale-owner takeover advances the `ownerEpoch` (via
        /// the bumped `ownershipTerm` local counter) and STRICTLY dominates the deposed owner's epoch —
        /// even within the same generation term, closing the same-term-takeover fence gap.
        @Override
        public Epoch fenceEpoch() {
            return ownerEpoch;
        }

        public DhtPartitionOwnershipValue {
            if (ownerCommunityId == null) {
                ownerCommunityId = "";
            }

            if (ownerEpoch == null) {
                ownerEpoch = Epoch.ZERO;
            }

            if (transferredAt == null) {
                transferredAt = HlcTimestamp.ZERO;
            }
        }

        public static DhtPartitionOwnershipValue dhtPartitionOwnershipValue(NodeId ownerNodeId,
                                                                            String ownerCommunityId,
                                                                            Epoch ownerEpoch,
                                                                            long ownershipTerm,
                                                                            HlcTimestamp transferredAt) {
            return new DhtPartitionOwnershipValue(ownerNodeId,
                                                  ownerCommunityId,
                                                  ownerEpoch,
                                                  ownershipTerm,
                                                  transferredAt);
        }
    }

    /// Per-`(stream, partition)` ownership record (#345 item 1d-i) — the stream-side mirror of
    /// [DhtPartitionOwnershipValue], and the first persisted slice of #265's reshuffle ring.
    /// Stream-partition ownership was previously pure HRW recomputed on the fly with no persisted
    /// record and no fencing token; this record gives the partition's owner an `ownerEpoch` that the
    /// leader advances on every owner change, so the append fence (1d-ii) can reject a deposed owner.
    ///
    /// There is no `ownerCommunityId` — streams have no community arc (that field is DHT-specific). The
    /// `ownerEpoch` is sourced from the committed generation epoch (`Epoch.epoch(incarnation, rabiaTerm, 0)`); the
    /// `ownershipTerm` is a monotonic per-partition takeover counter, bumped on each owner change.
    ///
    /// #1730: the record also carries the partition's committed in-sync replica set (`isr`, the owner included) and
    /// its change counter `isrVersion`. Owner and ISR live in ONE record so an election from the ISR and the ISR it
    /// leaves behind commit atomically. A CF ≥ 2 publish is acknowledged only once every ISR member holds it, and
    /// the ISR changes only through a guarded consensus write ([KVCommand.LeaderTransaction] whose mutation expects
    /// the exact current record) — never by a node's local view.
    ///
    /// `failoverRefused` (#1730, owner ruling): the leader found the owner dead and no ISR member live, so it elected
    /// nobody (unclean failover is off). Committing that verdict — instead of only computing it — makes the refusal a
    /// single committed TRANSITION: the guarded write that sets it (and the one that clears it, when an owner is
    /// elected or returns) is accepted exactly once, and its committer is the one node that announces it.
    record StreamPartitionOwnershipValue(NodeId owner,
                                         Epoch ownerEpoch,
                                         long ownershipTerm,
                                         HlcTimestamp transferredAt,
                                         List<NodeId> isr,
                                         long isrVersion,
                                         boolean failoverRefused,
                                         List<NodeId> fenced,
                                         long failoverRefusalSeq) implements AetherValue, EpochBearing<Epoch> {
        /// Most members one record remembers as fenced. A member that left for good is never unfenced, so the list is
        /// bounded here: the oldest entry is forgotten first. A forgotten member is no longer fenced, so if it is still
        /// registered with the owner as caught up and invisible to the leader, the owner re-expands it and the leader
        /// fences it again. That fight is unreachable while the core has [#FENCED_MAX] or fewer members (the registry
        /// holds at most the replication factor, which never exceeds the core size) and perpetual beyond it.
        public static final int FENCED_MAX = 16;

        /// Ownership fence (#345 piece 1a): the owner's `ownerEpoch` is the fencing token, so the Rabia
        /// applier rejects a deposed owner's strictly-older-epoch ownership write for free (it fences
        /// ANY `EpochBearing` value). A stale-owner takeover at the same epoch (bumping only
        /// `ownershipTerm`) is accepted, mirroring `DhtPartitionOwnershipValue`.
        @Override
        public Epoch fenceEpoch() {
            return ownerEpoch;
        }

        public StreamPartitionOwnershipValue {
            if (ownerEpoch == null) {
                ownerEpoch = Epoch.ZERO;
            }

            if (transferredAt == null) {
                transferredAt = HlcTimestamp.ZERO;
            }

            isr = isr == null || isr.isEmpty()
                  ? List.of(owner)
                  : List.copyOf(isr);
            fenced = fenced == null
                     ? List.of()
                     : List.copyOf(fenced.size() > FENCED_MAX
                                   ? fenced.subList(fenced.size() - FENCED_MAX, fenced.size())
                                   : fenced);
        }

        /// A record whose ISR is the owner alone: the shape of every record written before #1730, and of a
        /// partition whose other replicas have not joined yet.
        public static StreamPartitionOwnershipValue streamPartitionOwnershipValue(NodeId owner,
                                                                                  Epoch ownerEpoch,
                                                                                  long ownershipTerm,
                                                                                  HlcTimestamp transferredAt) {
            return new StreamPartitionOwnershipValue(owner,
                                                     ownerEpoch,
                                                     ownershipTerm,
                                                     transferredAt,
                                                     List.of(owner),
                                                     0L,
                                                     false,
                                                     List.of(),
                                                     0L);
        }

        public static StreamPartitionOwnershipValue streamPartitionOwnershipValue(NodeId owner,
                                                                                  Epoch ownerEpoch,
                                                                                  long ownershipTerm,
                                                                                  HlcTimestamp transferredAt,
                                                                                  List<NodeId> isr,
                                                                                  long isrVersion) {
            return new StreamPartitionOwnershipValue(owner,
                                                     ownerEpoch,
                                                     ownershipTerm,
                                                     transferredAt,
                                                     isr,
                                                     isrVersion,
                                                     false,
                                                     List.of(),
                                                     0L);
        }

        /// A record with ISR `isr` and fenced set `fenced` (#1883).
        public static StreamPartitionOwnershipValue streamPartitionOwnershipValue(NodeId owner,
                                                                                  Epoch ownerEpoch,
                                                                                  long ownershipTerm,
                                                                                  HlcTimestamp transferredAt,
                                                                                  List<NodeId> isr,
                                                                                  long isrVersion,
                                                                                  List<NodeId> fenced) {
            return new StreamPartitionOwnershipValue(owner,
                                                     ownerEpoch,
                                                     ownershipTerm,
                                                     transferredAt,
                                                     isr,
                                                     isrVersion,
                                                     false,
                                                     fenced,
                                                     0L);
        }

        /// The same ownership with ISR `isr`, one ISR change later.
        public StreamPartitionOwnershipValue withIsr(List<NodeId> isr) {
            return new StreamPartitionOwnershipValue(owner,
                                                     ownerEpoch,
                                                     ownershipTerm,
                                                     transferredAt,
                                                     isr,
                                                     isrVersion + 1,
                                                     failoverRefused,
                                                     fenced,
                                                     failoverRefusalSeq);
        }

        /// The same ownership with ISR `isr` and fenced set `fenced`, one ISR change later (#1883). `fenced` is the set of
        /// members the leader removed from the ISR because its own liveness view does not list them; the owner never
        /// expands a fenced member, so the two writers read ONE liveness input. Bounded by [#FENCED_MAX] at construction, newest kept.
        public StreamPartitionOwnershipValue withIsrAndFenced(List<NodeId> isr, List<NodeId> fenced) {
            return new StreamPartitionOwnershipValue(owner,
                                                     ownerEpoch,
                                                     ownershipTerm,
                                                     transferredAt,
                                                     isr,
                                                     isrVersion + 1,
                                                     failoverRefused,
                                                     fenced,
                                                     failoverRefusalSeq);
        }

        /// The same ownership and ISR with the failover verdict `refused`. Each transition INTO refused counts one more
        /// in `failoverRefusalSeq` WITHIN AN OWNERSHIP TERM (a move mints a fresh record, so the count restarts at 0 and the
        /// raised term keeps ids distinct), committed with the flag, so every genuine refusal of a partition is a distinct event
        /// (a refusal that resolves by the owner returning and recurs changes nothing else in the record).
        public StreamPartitionOwnershipValue withFailoverRefused(boolean refused) {
            return new StreamPartitionOwnershipValue(owner,
                                                     ownerEpoch,
                                                     ownershipTerm,
                                                     transferredAt,
                                                     isr,
                                                     isrVersion,
                                                     refused,
                                                     fenced,
                                                     refused && !failoverRefused
                                                     ? failoverRefusalSeq + 1
                                                     : failoverRefusalSeq);
        }
    }

    @Codec
    enum SpokesmanStatus {
        ASSIGNED,
        ACTIVE,
        FAILED,
        /// Wire sentinel (#964): an ordinal this node cannot name decodes here instead of throwing.
        /// Never ACTIVE, so a spokesman whose status cannot be read is not treated as serving.
        /// Must stay LAST -- a new constant appended after it, or inserted before it, is read as
        /// UNKNOWN by an older node either way.
        UNKNOWN
    }

    /// Desired-state community record (worker-membership-spec §2 line 78): the leader-authored
    /// target for a committed [AetherKey.CommunityKey]. `state` is the per-community FSM state
    /// (leader-evaluated, §3.3). `dissolvedAt` is present only once the community reaches the
    /// `DISSOLVED` terminal fact; absent (`none()`) for every live state.
    ///
    /// Mirrors [DhtPartitionOwnershipValue] for optional-field canonicalization: empty Option /
    /// empty string are the canonical "absent" forms, normalized in the compact constructor so a
    /// `null` from a wire/codec edge collapses to the same value (and the same `equals`).
    /// One bounded, durable movement at a time per community. A created node retains its identity
    /// across leader changes; an ambiguous create is never retried with a new identity.
    record CapacityLedgerValue(int allocated, long version, boolean inventoryComplete) implements AetherValue, VersionFenced, org.pragmatica.cluster.state.kvstore.LeaderAuthorized {
        @Override
        public long fenceVersion() {
            return version;
        }
    }

    record CapacityReservationValue(String sourceName,
                                    String sourceBinding,
                                    String intendedRole,
                                    CapacityReservationPhase phase) implements AetherValue, org.pragmatica.cluster.state.kvstore.LeaderAuthorized {}

    @Codec
    enum CapacityReservationPhase {
        DISPATCHED,
        OBSERVED,
        RELEASED,
        UNKNOWN
    }

    /// A definitive no-create refusal. The operation itself is the exclusive recovery-probe token.
    record CommunityPlacementAvailabilityValue(String policyIdentity,
                                               String sourceBinding,
                                               NodeId refusedNode,
                                               long refusedAt,
                                               int attempts) implements AetherValue, org.pragmatica.cluster.state.kvstore.LeaderAuthorized {}

    record CommunityPlacementOperationValue(String operationId,
                                            String communityId,
                                            NodeId targetNode,
                                            String targetSource,
                                            Option<String> targetZone,
                                            String sourceBinding,
                                            Option<NodeId> previousNode,
                                            String previousSource,
                                            PlacementOperationPhase phase,
                                            org.pragmatica.cluster.state.kvstore.LeaderValue issuer,
                                            long startedAt,
                                            long phaseChangedAt,
                                            String detail) implements AetherValue, org.pragmatica.cluster.state.kvstore.LeaderAuthorized {
        public CommunityPlacementOperationValue withPhase(PlacementOperationPhase next,
                                                          org.pragmatica.cluster.state.kvstore.LeaderValue leader,
                                                          String reason) {
            return new CommunityPlacementOperationValue(operationId,
                                                        communityId,
                                                        targetNode,
                                                        targetSource,
                                                        targetZone,
                                                        sourceBinding,
                                                        previousNode,
                                                        previousSource,
                                                        next,
                                                        leader,
                                                        startedAt,
                                                        System.currentTimeMillis(),
                                                        reason);
        }

        public CommunityPlacementOperationValue withIssuer(org.pragmatica.cluster.state.kvstore.LeaderValue leader) {
            return new CommunityPlacementOperationValue(operationId,
                                                        communityId,
                                                        targetNode,
                                                        targetSource,
                                                        targetZone,
                                                        sourceBinding,
                                                        previousNode,
                                                        previousSource,
                                                        phase,
                                                        leader,
                                                        startedAt,
                                                        phaseChangedAt,
                                                        detail);
        }

        public boolean active() {
            return phase != PlacementOperationPhase.COMPLETE;
        }
    }

    @Codec
    enum PlacementOperationPhase {
        RESERVED,
        CREATE_REQUESTED,
        AWAITING_READY,
        DRAIN_REQUESTED,
        DRAINED,
        TERMINATING,
        COMPLETE,
        CREATE_UNCERTAIN,
        DRAIN_UNCERTAIN,
        BLOCKED,
        UNKNOWN
    }

    record NodePlacementValue(String sourceName, Option<String> observedZone, String providerInstanceId) implements AetherValue {}

    record CommunityValue(String sourceName,
                          String role,
                          int targetSize,
                          CommunityState state,
                          long createdAt,
                          Option<Long> dissolvedAt) implements AetherValue {
        public CommunityValue {
            if (sourceName == null) {
                sourceName = "";
            }

            if (role == null) {
                role = "";
            }

            if (state == null) {
                state = CommunityState.FORMING;
            }

            if (dissolvedAt == null) {
                dissolvedAt = Option.none();
            }
        }

        public static CommunityValue communityValue(String sourceName,
                                                    String role,
                                                    int targetSize,
                                                    CommunityState state,
                                                    long createdAt,
                                                    Option<Long> dissolvedAt) {
            return new CommunityValue(sourceName, role, targetSize, state, createdAt, dissolvedAt);
        }

        /// FORMING mint — the leader creating a fresh community (growth policy demands a new slot,
        /// §3.3): stamps `createdAt = now`, `state = FORMING`, `dissolvedAt = none()`.
        public static CommunityValue communityValue(String sourceName, String role, int targetSize) {
            return new CommunityValue(sourceName,
                                      role,
                                      targetSize,
                                      CommunityState.FORMING,
                                      System.currentTimeMillis(),
                                      none());
        }

        /// Per-community FSM transition (worker-membership-spec §3.3): the leader re-stamps only the
        /// `state` on an edge, preserving every other committed field. Mirrors
        /// [NodeArtifactValue#withState] — a copy via the canonical constructor.
        public CommunityValue withState(CommunityState newState) {
            return new CommunityValue(sourceName, role, targetSize, newState, createdAt, dissolvedAt);
        }
    }

    /// Canonical field order: `(spawnedAtMs, assignedNodeId, occupantEpoch, supersededNodeId)`.
    ///
    /// `spawnedAtMs` is the wall-clock instant (epoch millis) the slot's current FILLING/occupied
    /// generation was stamped; `0` means EMPTY/never-stamped. The FILLING-marker EXPIRY is NOT
    /// stored — it is derived at check time as `spawnedAtMs + autoHealConfig.provisioningTimeout()`
    /// (the single source of truth for the timeout is the shared auto-heal `TimeSpan`; per the
    /// project rule, derived deadline instants are not persisted). `occupantEpoch` is a monotonic,
    /// slot-local generation counter; `0` means empty/never-occupied. `supersededNodeId` records the
    /// predecessor occupant this assignment replaced; `none()` on first fill.
    ///
    /// Backward compatibility (legacy slot-based-membership convergence, §4.2; spec removed, see git history): the legacy
    /// construction sites that passed a `deadlineMs` argument still compile via the deadline-arg
    /// constructors and `provisioningSlotValue(..)` factories below, which discard the now-derived
    /// deadline. Mirrors the trailing-field backward-compat pattern used across `AetherValue`.
    record ProvisioningSlotValue(long spawnedAtMs,
                                 Option<NodeId> assignedNodeId,
                                 long occupantEpoch,
                                 Option<NodeId> supersededNodeId) implements AetherValue {
        public ProvisioningSlotValue {
            if (assignedNodeId == null) {
                assignedNodeId = Option.none();
            }

            if (supersededNodeId == null) {
                supersededNodeId = Option.none();
            }
        }

        /// Backward-compatible constructor — preserves call sites that passed the now-derived
        /// `deadlineMs` (discarded). Defaults `occupantEpoch = 0`, `supersededNodeId = none()`.
        public ProvisioningSlotValue(long spawnedAtMs, long deadlineMs, Option<NodeId> assignedNodeId) {
            this(spawnedAtMs, assignedNodeId, 0L, Option.none());
        }

        /// Backward-compatible 5-arg constructor — preserves the pre-remodel fenced-form call sites
        /// that passed `deadlineMs` at position 1 (discarded; expiry is derived now).
        public ProvisioningSlotValue(long spawnedAtMs,
                                     long deadlineMs,
                                     Option<NodeId> assignedNodeId,
                                     long occupantEpoch,
                                     Option<NodeId> supersededNodeId) {
            this(spawnedAtMs, assignedNodeId, occupantEpoch, supersededNodeId);
        }

        public static ProvisioningSlotValue provisioningSlotValue(long spawnedAtMs) {
            return new ProvisioningSlotValue(spawnedAtMs, Option.none(), 0L, Option.none());
        }

        /// Backward-compatible factory — `deadlineMs` discarded (derived).
        public static ProvisioningSlotValue provisioningSlotValue(long spawnedAtMs, long deadlineMs) {
            return new ProvisioningSlotValue(spawnedAtMs, Option.none(), 0L, Option.none());
        }

        public static ProvisioningSlotValue provisioningSlotValue(long spawnedAtMs, NodeId assignedNodeId) {
            return new ProvisioningSlotValue(spawnedAtMs, Option.option(assignedNodeId), 0L, Option.none());
        }

        /// Backward-compatible factory — `deadlineMs` discarded (derived).
        public static ProvisioningSlotValue provisioningSlotValue(long spawnedAtMs,
                                                                  long deadlineMs,
                                                                  NodeId assignedNodeId) {
            return new ProvisioningSlotValue(spawnedAtMs, Option.option(assignedNodeId), 0L, Option.none());
        }

        public ProvisioningSlotValue withAssignedNode(NodeId nodeId) {
            return new ProvisioningSlotValue(spawnedAtMs, Option.option(nodeId), occupantEpoch, supersededNodeId);
        }
    }

    record SpokesmanValue(List<String> communities,
                          Epoch assignedEpoch,
                          HlcTimestamp assignedAt,
                          long version,
                          SpokesmanStatus status,
                          String failureReason) implements AetherValue {
        public SpokesmanValue {
            communities = communities == null
                          ? List.of()
                          : List.copyOf(communities);
            if (assignedEpoch == null) {
                assignedEpoch = Epoch.ZERO;
            }

            if (assignedAt == null) {
                assignedAt = HlcTimestamp.ZERO;
            }

            if (status == null) {
                status = SpokesmanStatus.ASSIGNED;
            }

            if (failureReason == null) {
                failureReason = "";
            }
        }

        public static SpokesmanValue spokesmanValue(List<String> communities,
                                                    Epoch assignedEpoch,
                                                    HlcTimestamp assignedAt,
                                                    long version) {
            return new SpokesmanValue(communities, assignedEpoch, assignedAt, version, SpokesmanStatus.ASSIGNED, "");
        }

        public SpokesmanValue withStatus(SpokesmanStatus newStatus) {
            return new SpokesmanValue(communities, assignedEpoch, assignedAt, version, newStatus, failureReason);
        }

        public SpokesmanValue withFailure(String reason) {
            return new SpokesmanValue(communities, assignedEpoch, assignedAt, version, SpokesmanStatus.FAILED, reason);
        }
    }

    // Stage 1 (stream-namespaces) additive graft: stream-registry value. Added alongside the
    // retained ClusterEventValue (cluster-events replacement is a later stage).
    /// Consensus-replicated form of [StreamRegistryEntry].
    ///
    /// Single-key collapse of the spec's `stream-meta:{addr}` and `stream-refs:{addr}` (§7.1, §7.2)
    /// — implementation choice for atomic refcount mutation in the same consensus round as the
    /// SliceNodeValue update (§8.5). The conceptual separation in the spec is preserved at the
    /// reader/writer API but the wire form is one record.
    record StreamRegistryValue(StreamRegistryEntry entry) implements AetherValue {
        public static StreamRegistryValue streamRegistryValue(StreamRegistryEntry entry) {
            return new StreamRegistryValue(entry);
        }
    }

    // Stage 2 (stream-namespaces) additive graft: per-blueprint resolved alias->ResourceAddress map.
    /// Per-blueprint resolved alias→`ResourceAddress` map persisted at deploy time.
    ///
    /// Kept as `List<NamedAddress>` instead of `Map<String, ResourceAddress>` so the compile-time
    /// codec processor doesn't have to handle a `Map<K, V>` where `V` is a record-typed codec
    /// element (only `Map<String, String>` is exercised by the processor today; List-of-record
    /// is explicitly tested).
    ///
    /// Spec reference: event-stream-namespaces §8.5 (resolved address required for slice-time
    /// refcount accounting).
    record BlueprintStreamBindingsValue(List<NamedAddress> bindings) implements AetherValue {
        public BlueprintStreamBindingsValue {
            bindings = bindings == null
                       ? List.of()
                       : List.copyOf(bindings);
        }

        public static BlueprintStreamBindingsValue blueprintStreamBindingsValue(List<NamedAddress> bindings) {
            return new BlueprintStreamBindingsValue(bindings);
        }

        public Option<ResourceAddress> addressFor(String alias) {
            return Option.option(bindings.stream().filter(b -> b.alias()
                                                                .equals(alias)).findFirst().orElse(null)).map(NamedAddress::address);
        }

        public record NamedAddress(String alias, ResourceAddress address) {
            public static NamedAddress namedAddress(String alias, ResourceAddress address) {
                return new NamedAddress(alias, address);
            }
        }
    }

    /// Payload of an [AetherKey.EntityCheckpointKey] — where a partition's folded state lives and how far
    /// forward it accounts for (#345 I3).
    ///
    /// `blockIdHex` names a block in the node's stream storage, whose tier chain ends in a DHT tier, so
    /// any node can fetch it. `throughOffset` is the LAST log offset folded into that block: a recovering
    /// owner loads the block and then replays from `throughOffset + 1`.
    ///
    /// The offset is what makes this safe to act on. A recovering node compares `throughOffset + 1`
    /// against the earliest offset it can still read, and refuses when the two do not meet — see
    /// `EntityLogSubstrate#earliestRetainedOffset`. Storing the block id without the offset would leave a
    /// reader unable to tell a complete recovery from one silently missing every mutation in the gap.
    ///
    /// ## Why the name says "Fold" — historical, and now load-bearing for a different reason
    /// This type was named to dodge a tag collision. Codec tags were derived purely by hashing the
    /// fully-qualified type name into a 16256-slot space, and the obvious name, `EntityCheckpointValue`,
    /// hashed to 7612 — already claimed by `org.pragmatica.cluster.metrics.HealthHintWire`. Registering
    /// both threw at `NodeCodecs` static init and poisoned every test that touched it.
    ///
    /// That derivation is gone. System types now carry hand-assigned tags in
    /// `org.pragmatica.serialization.SystemTags`, so this type's tag no longer depends on its name and
    /// the collision cannot recur. The name still matters, but the reason inverted: the SystemTags key
    /// IS the fully-qualified name, so a rename leaves the entry unmatched, drops the type into the
    /// hashed user range, and fails the build at `SliceCodec#systemCodec`. Renaming is therefore a
    /// deliberate two-step — rename, then re-key — and a tag, once assigned, is never renumbered.
    ///
    /// @param throughOffset last log offset folded into the snapshot
    /// @param blockIdHex    content id of the snapshot block in stream storage
    /// @param timestamp     wall-clock ms the checkpoint was written, for operator diagnosis only —
    ///                      never for ordering, which is `throughOffset`'s job
    /// [org.pragmatica.cluster.state.kvstore.MonotonicFenced]: the checkpoint claim is a running
    /// max — the retention floor reclaims log segments below it, so the applier refuses a Put that
    /// would LOWER the committed `throughOffset` (#700; a lower honest claim landing after a higher
    /// one would leave the records between them on no reachable node). Equal offsets are accepted:
    /// a fresh snapshot at unchanged coverage replaces the block pointer harmlessly.
    record EntityFoldCheckpointValue(long throughOffset, String blockIdHex, long timestamp) implements AetherValue, org.pragmatica.cluster.state.kvstore.MonotonicFenced {
        @Override
        public long fenceWatermark() {
            return throughOffset;
        }

        public static EntityFoldCheckpointValue entityFoldCheckpointValue(long throughOffset, String blockIdHex) {
            return new EntityFoldCheckpointValue(throughOffset, blockIdHex, System.currentTimeMillis());
        }
    }

    /// The phase of a live DHT replication change (#1777, CTO ruling R1b).
    @Codec
    enum DhtReplicationStage {
        /// Committed; members are applying the new factors. Readers and writers use the transitional quorums.
        APPLYING,
        /// Every expected member reported the change applied, so every write from now on is acked at W_t or higher.
        /// Every core re-runs its catch-up for it.
        WRITERS_SWITCHED,
        /// Every core caught up after the writers switched: the new quorums apply cluster-wide.
        SETTLED,
        /// Wire sentinel: an ordinal this node cannot name decodes here. Never SETTLED, so a node that cannot read the
        /// stage keeps the transitional quorums. Must stay LAST.
        UNKNOWN
    }

    /// The cluster's latest DHT replication change, under [AetherKey.DhtReplicationChangeKey] (#1777, CTO ruling R1b).
    /// The switch to the new quorums is cluster-wide and committed: every node keeps W_t = max(W_old, W_new) and
    /// R_t = max(R_old, R_new) until this record reaches [DhtReplicationStage#SETTLED].
    ///
    /// - `version` — the committed cluster configuration version that carried the factors, which every report is
    ///   compared against: a report for an earlier version never advances this change.
    /// - `replicationFactor`, `confirmationFactor` — the factors this change installed.
    /// - `floorWriteQuorum`, `floorReadQuorum` — the strictest quorums of the factors it replaced, and of every earlier
    ///   change still unsettled when it was committed.
    /// - `sourceReplicationFactor` — the largest replication factor across those changes: the replica set at that factor
    ///   contains every replica set involved, and is what a core catches up from.
    /// - `since` — when the leader committed it (wall-clock ms), so the age survives a leader change.
    /// - `overdue` — the leader committed that the change has been unsettled for longer than the operator-attention
    ///   bound; it is the dedupe of the entering/leaving events.
    ///
    /// Leader-authorized: only a compare-and-set leader transaction writes it, so a stale leader, or a transition
    /// computed from a superseded record, is refused.
    record DhtReplicationChangeValue(long version,
                                     int replicationFactor,
                                     int confirmationFactor,
                                     int floorWriteQuorum,
                                     int floorReadQuorum,
                                     int sourceReplicationFactor,
                                     DhtReplicationStage stage,
                                     long since,
                                     boolean overdue) implements AetherValue, LeaderAuthorized {
        public DhtReplicationChangeValue {
            if (stage == null) {
                stage = DhtReplicationStage.UNKNOWN;
            }
        }

        public DhtReplicationChangeValue withStage(DhtReplicationStage next) {
            return new DhtReplicationChangeValue(version,
                                                 replicationFactor,
                                                 confirmationFactor,
                                                 floorWriteQuorum,
                                                 floorReadQuorum,
                                                 sourceReplicationFactor,
                                                 next,
                                                 since,
                                                 overdue);
        }

        public DhtReplicationChangeValue withOverdue(boolean next) {
            return new DhtReplicationChangeValue(version,
                                                 replicationFactor,
                                                 confirmationFactor,
                                                 floorWriteQuorum,
                                                 floorReadQuorum,
                                                 sourceReplicationFactor,
                                                 stage,
                                                 since,
                                                 next);
        }

        public boolean settled() {
            return stage == DhtReplicationStage.SETTLED;
        }
    }

    /// What one member reports about the latest DHT replication change, under [AetherKey.DhtReplicationReportKey]
    /// (#1777, CTO ruling R1b): `appliedVersion` is the configuration version whose factors it uses, and
    /// `caughtUpVersion` the change whose writers-switched catch-up it completed ([DhtReplicationStage#WRITERS_SWITCHED]).
    /// Either is `-1` before the first. `replica` says the member holds DHT partitions (a core): its catch-up is part of
    /// the settle. A worker holds none and reports what it applied only.
    record DhtReplicationReportValue(long appliedVersion, long caughtUpVersion, boolean replica) implements AetherValue {}
}
