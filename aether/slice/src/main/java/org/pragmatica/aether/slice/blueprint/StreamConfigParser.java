// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Comparator;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.ConsumerConfig.ErrorStrategy;
import org.pragmatica.aether.slice.ConsumerConfig.ProcessingMode;
import org.pragmatica.aether.slice.ReadPreference;
import org.pragmatica.aether.slice.RetentionMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.stream.StreamEngineKey;
import org.pragmatica.aether.slice.stream.StreamResource;
import org.pragmatica.aether.slice.stream.StreamVersionSpec;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.utils.Causes.cause;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02", "JBCT-ZONE-03"})
public interface StreamConfigParser {
    String STREAMS_PREFIX = "streams.";
    int DEFAULT_PARTITIONS = 4;
    /// The bounds a `time`/`size`/`compound` retention form gets for anything it does not declare (#1549).
    RetentionPolicy DEFAULTS = RetentionPolicy.retentionPolicy();
    /// Per spec §7/§10: the absolute per-stream partition ceiling, enforced at BUILD time (a blueprint
    /// declaring more partitions than this fails to build) and re-checked pre-commit at runtime
    /// (`StreamPartitionManager.createFreshStream`). A fixed absolute guard — NOT the RAM-derived cap. Spec
    /// §10 presents it as the tunable `[streams.limits] max_partitions_per_stream_ceiling` node-config key
    /// (future wiring); this is its default. Mirrors Pulsar's `maxNumPartitionsPerPartitionedTopic`.
    int MAX_PARTITIONS_PER_STREAM_CEILING = 1024;
    /// Per spec §11.1.1: when `version` is omitted on a producer-role declaration (or omitted with no
    /// explicit role — current parser-only assumption pending Wave 3 slice-binding role inference),
    /// default to `"1.0.0"`. The producer assumption is the safer default for the common case where
    /// the slice writes data.
    String DEFAULT_PRODUCER_VERSION = "1.0.0";
    /// Per spec §11.1.1: when `version` is omitted on an explicit consumer-role declaration, default
    /// to `"latest"`.
    String DEFAULT_CONSUMER_VERSION = StreamVersionSpec.LATEST_TOKEN;
    String ROLE_PRODUCER = "producer";
    String ROLE_CONSUMER = "consumer";
    /// Slice declares both producer and consumer bindings for the same stream alias (spec §11.1.2).
    /// Treated as producer for version-defaulting and the producer-rejects-latest rule.
    String ROLE_BOTH = "both";

    /// Parse `[streams.*]` sections into [StreamResource] declarations.
    ///
    /// Each section is either:
    ///  - [StreamResource.Owned] — internal declaration with optional `version` (defaulted per role
    ///    per spec §11.1.1) and optional config fields.
    ///  - [StreamResource.External] — reference with `source = "namespace:stream:version"`.
    ///
    /// `version` and `source` are mutually exclusive. When both are absent the section is treated as
    /// the spec §11.1 shortcut form and `version` is defaulted from `role` (or producer-assumed when
    /// `role` is also omitted, pending Wave 3 slice-binding inference).
    static Result<Map<String, StreamResource>> parseResources(String toml) {
        return parseResources(toml, Map.of());
    }

    /// Parse `[streams.*]` sections with per-alias **inferred role hints** (spec §11.1.2).
    ///
    /// `roleHints` maps a stream alias (e.g., `orders`) to its inferred role (`producer`,
    /// `consumer`, `both`) — produced by the slice-processor from `StreamPublisher`/`StreamAccess`
    /// parameter bindings on the slice's factory method. When a `[streams.X]` section omits the
    /// explicit `role` field and `roleHints` carries an entry for `X`, the inferred role drives
    /// default version selection per §11.1.1 and the producer-with-latest rejection per §11.1.3.
    ///
    /// `roleHints` of `"both"` is treated as producer for the version-default rule (the safer
    /// choice — a stream the slice both writes and reads must pin) and the producer-rejects-latest
    /// rule applies on the producer side.
    ///
    /// Behaviourally identical to [#parseResources(String)] when `roleHints` is empty.
    static Result<Map<String, StreamResource>> parseResources(String toml, Map<String, String> roleHints) {
        return option(toml).filter(s -> !s.isBlank())
                     .map(t -> parseResourceToml(t, roleHints))
                     .or(success(Map.of()));
    }

    /// Aggregating variant: every per-section failure is collected via [Result#allOf] rather than
    /// short-circuiting at the first error. Used by [org.pragmatica.aether.deployment.validation.StreamResourceValidator]
    /// to satisfy spec §15.1.1 "all-failures aggregation" — operators see every error in one pass.
    ///
    /// Returns [Result#failure] carrying a [org.pragmatica.lang.utils.Causes.CompositeCause]
    /// when any section fails; [Result#success] with the parsed map otherwise.
    static Result<Map<String, StreamResource>> parseResourcesAggregating(String toml, Map<String, String> roleHints) {
        if (!Verify.Is.present(toml)) {
            return success(Map.of());
        }

        return TomlParser.parse(toml)
                         .mapError(err -> cause("Stream config parse error: " + err.message()))
                         .flatMap(doc -> aggregateStreamResources(doc, roleHints));
    }

    private static Result<Map<String, StreamResource>> aggregateStreamResources(TomlDocument doc,
                                                                                Map<String, String> roleHints) {
        return Result.allOf(parseSections(doc, roleHints).stream().map(StreamConfigParser::entryOf).toList()).map(StreamConfigParser::toOrderedMap);
    }

    /// #1336 — a `[streams.<alias>]` section the parser refused, with the alias the refusal is about, so the
    /// deploy can name the section without guessing it from the message; `cause` is the parser's own typed
    /// refusal ([StreamDeclarationError], [StreamSourceError], the address and version types' causes), from
    /// which the rule is derived. Only per-section outcomes are wrapped: a document that does not parse at
    /// all has no section and stays a bare cause.
    record RefusedSection(String alias, Cause cause) implements Cause {
        public static RefusedSection refusedSection(String alias, Cause cause) {
            return new RefusedSection(alias, cause);
        }

        @Override
        public String message() {
            return cause.message();
        }
    }

    /// One top-level `[streams.X]` section's outcome, in document order — what [#aggregateStreamResources]
    /// folds and [#parseResourcesPartitioned] keeps apart.
    record ParsedSection(String alias, Result<StreamResource> outcome) {}

    private static List<ParsedSection> parseSections(TomlDocument doc, Map<String, String> roleHints) {
        var perSection = new ArrayList<ParsedSection>();

        for (var sectionName : doc.sectionNames()) {
            if (!isStreamSection(sectionName)) {
                continue;
            }

            var streamName = sectionName.substring(STREAMS_PREFIX.length());

            if (streamName.contains(".")) {
                continue;
            }

            perSection.add(new ParsedSection(streamName, parseStreamResource(doc, sectionName, streamName, roleHints)));
        }

        return perSection;
    }

    private static Result<Map.Entry<String, StreamResource>> entryOf(ParsedSection section) {
        return section.outcome()
                      .map(res -> Map.entry(section.alias(),
                                            res))
                      .mapError(cause -> RefusedSection.refusedSection(section.alias(),
                                                                       cause));
    }

    private static Stream<RefusedSection> refusalOf(ParsedSection section) {
        return section.outcome()
                      .fold(cause -> Stream.of(RefusedSection.refusedSection(section.alias(),
                                                                             cause)),
                            _ -> Stream.empty());
    }

    /// #1336 — the per-section outcomes [#parseResourcesAggregating] folds into one `Result`, kept apart:
    /// every section that parses is in `accepted`, every section that does not
    /// is in `rejected` as a [RefusedSection] naming its alias. The deploy path binds the accepted aliases and reports the
    /// rejected ones by name, instead of losing the valid declarations to the invalid one beside them.
    /// Only a document that does not parse at all is a failure — then no section has an outcome.
    record PartitionedStreamResources(Map<String, StreamResource> accepted, List<RefusedSection> rejected) {
        public PartitionedStreamResources {
            accepted = Map.copyOf(accepted);
            rejected = List.copyOf(rejected);
        }

        public static PartitionedStreamResources partitionedStreamResources(Map<String, StreamResource> accepted,
                                                                            List<RefusedSection> rejected) {
            return new PartitionedStreamResources(accepted, rejected);
        }
    }

    static Result<PartitionedStreamResources> parseResourcesPartitioned(String toml, Map<String, String> roleHints) {
        if (!Verify.Is.present(toml)) {
            return success(PartitionedStreamResources.partitionedStreamResources(Map.of(), List.of()));
        }

        return TomlParser.parse(toml)
                         .mapError(err -> cause("Stream config parse error: " + err.message()))
                         .map(doc -> partitionStreamResources(doc, roleHints));
    }

    private static PartitionedStreamResources partitionStreamResources(TomlDocument doc,
                                                                       Map<String, String> roleHints) {
        var perSection = parseSections(doc, roleHints);
        var accepted = toOrderedMap(perSection.stream().flatMap(section -> entryOf(section).stream()).toList());
        var rejected = perSection.stream().flatMap(StreamConfigParser::refusalOf).toList();

        return PartitionedStreamResources.partitionedStreamResources(accepted, rejected);
    }

    private static Map<String, StreamResource> toOrderedMap(List<Map.Entry<String, StreamResource>> entries) {
        var ordered = new LinkedHashMap<String, StreamResource>();

        entries.forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));

        return Map.copyOf(ordered);
    }

    static Result<Map<String, ConsumerConfig>> parseConsumers(String toml, String streamName) {
        return option(toml).filter(s -> !s.isBlank())
                     .map(t -> parseConsumerToml(t, streamName))
                     .or(success(Map.of()));
    }

    private static Result<Map<String, StreamResource>> parseResourceToml(String toml, Map<String, String> roleHints) {
        return TomlParser.parse(toml)
                         .mapError(err -> cause("Stream config parse error: " + err.message()))
                         .flatMap(doc -> extractStreamResources(doc, roleHints));
    }

    private static Result<Map<String, StreamResource>> extractStreamResources(TomlDocument doc,
                                                                              Map<String, String> roleHints) {
        var result = new LinkedHashMap<String, StreamResource>();

        for (var sectionName : doc.sectionNames()) {
            if (!isStreamSection(sectionName)) {
                continue;
            }

            var streamName = sectionName.substring(STREAMS_PREFIX.length());

            if (streamName.contains(".")) {
                continue;
            }  // skip sub-sections like streams.x.consumers.y
            var parsed = parseStreamResource(doc, sectionName, streamName, roleHints);

            if (parsed.isFailure()) {
                return parsed.fold(Result::failure, _ -> success(Map.of()));
            }

            parsed.onSuccess(res -> result.put(streamName, res));
        }

        return success(Map.copyOf(result));
    }

    private static Result<StreamResource> parseStreamResource(TomlDocument doc,
                                                              String section,
                                                              String streamName,
                                                              Map<String, String> roleHints) {
        var sourceOpt = doc.getString(section, "source");
        var versionOpt = doc.getString(section, "version");
        var explicitRoleOpt = doc.getString(section, "role");
        var hintOpt = option(roleHints.get(streamName));
        var effectiveRoleOpt = explicitRoleOpt.isPresent()
                               ? explicitRoleOpt
                               : hintOpt;

        if (sourceOpt.isPresent() && versionOpt.isPresent()) {
            return new StreamDeclarationError.VersionAndSourceBothSet(streamName).result();
        }

        if (sourceOpt.isPresent()) {
            return refuseUnknownKeys(StreamSection.tomlSection(doc, section, streamName)).flatMap(_ -> sourceOpt.fold(() -> missingStreamResource(streamName),
                                                                                                                      source -> parseExternalResource(streamName,
                                                                                                                                                      source)));
        }
        // Spec §11.1.1: shortcut form — when `version` is omitted, default per role.
        // Producer (explicit, inferred from manifest, or absent → producer-assumed) → "1.0.0".
        // Consumer (explicit or inferred) → "latest". `both` inherits the producer default
        // (the safer pin since a producer-side write requires an exact triplet anyway).
        var resolvedVersion = versionOpt.or(defaultVersionForRole(effectiveRoleOpt));

        return parseOwnedResource(doc, section, streamName, resolvedVersion, effectiveRoleOpt);
    }

    private static String defaultVersionForRole(Option<String> roleOpt) {
        return roleOpt.map(String::trim)
                      .map(String::toLowerCase)
                      .filter(ROLE_CONSUMER::equals)
                      .map(_ -> DEFAULT_CONSUMER_VERSION)
                      .or(DEFAULT_PRODUCER_VERSION);
    }

    private static Result<StreamResource> missingStreamResource(String streamName) {
        return Causes.<Cause> cause("Stream resource '" + streamName
                                   + "' must specify either 'version' (owned) or 'source' (external)").result();
    }

    private static Result<StreamResource> parseExternalResource(String streamName, String source) {
        return ResourceAddress.resourceAddress(source)
                              .flatMap(addr -> refuseReservedKind(streamName, source, addr))
                              .map(addr -> StreamResource.external(streamName, addr));
    }

    /// #1282: an External source whose engine key carries a reserved stream-kind prefix is refused — see
    /// [StreamSourceError.ReservedKindSource].
    private static Result<ResourceAddress> refuseReservedKind(String streamName, String source, ResourceAddress addr) {
        return StreamEngineKey.reservedKindPrefixOf(StreamEngineKey.engineKey(addr))
                              .map(prefix -> StreamSourceError.ReservedKindSource.FACTORY.apply(streamName,
                                                                                                source,
                                                                                                prefix).<ResourceAddress> result())
                              .or(() -> success(addr));
    }

    private static Result<StreamResource> parseOwnedResource(TomlDocument doc,
                                                             String section,
                                                             String streamName,
                                                             String version,
                                                             Option<String> roleOpt) {
        return StreamVersionSpec.streamVersionSpec(version)
                                .flatMap(spec -> rejectProducerLatest(streamName, spec, roleOpt))
                                .flatMap(spec -> buildOwnedResource(doc, section, streamName, spec));
    }

    private static Result<StreamResource> buildOwnedResource(TomlDocument doc,
                                                             String section,
                                                             String streamName,
                                                             StreamVersionSpec spec) {
        return parseStreamConfig(StreamSection.tomlSection(doc, section, streamName)).map(config -> StreamResource.owned(streamName,
                                                                                                                         spec,
                                                                                                                         config));
    }

    /// Spec §7/§10: a blueprint declaring more than [#MAX_PARTITIONS_PER_STREAM_CEILING] partitions for one
    /// stream is a build-time error (the create-time admission gate's parser half). Enforced through the same
    /// [Result] failure path as the replication-knob rejections; the runtime pre-commit check re-applies the
    /// ceiling on the committing node, and the cluster-wide aggregate guard (unknowable at build time) is
    /// applied there too.
    private static Result<StreamConfig> validatePartitionCeiling(String streamName, StreamConfig config) {
        if (config.partitions() > MAX_PARTITIONS_PER_STREAM_CEILING) {
            return new StreamDeclarationError.PartitionsOverCeiling(streamName,
                                                                    config.partitions(),
                                                                    MAX_PARTITIONS_PER_STREAM_CEILING).result();
        }

        return success(config);
    }

    /// Spec §11.x: the two decoupled replication knobs must satisfy `replicas >= StreamConfig.MIN_REPLICAS`
    /// (#1547 — refused, never clamped) and `0 <= min-sync-replicas <= replicas`. `replicas` is the replication factor (total copies incl.
    /// owner); `min-sync-replicas` is the in-sync ack requirement (incl. owner). A `min-sync-replicas`
    /// exceeding `replicas` can never be met, so it is a build-time error via the same [Result] failure
    /// path used for the rest of the parser's config rejections.
    private static Result<StreamConfig> validateReplication(String streamName, StreamConfig config) {
        if (config.replicas() < StreamConfig.MIN_REPLICAS) {
            return new StreamDeclarationError.ReplicasBelowMinimum(streamName,
                                                                   config.replicas(),
                                                                   StreamConfig.MIN_REPLICAS).result();
        }

        if (config.minSyncReplicas() > config.replicas()) {
            return new StreamDeclarationError.ReplicationInvalid(streamName,
                                                                 "min-sync-replicas=" + config.minSyncReplicas()
                                                                + " > replicas=" + config.replicas()
                                                                + "; min-sync-replicas must not exceed replicas").result();
        }

        return success(config);
    }

    /// Spec §11.1.3: producer with `version = "latest"` (explicit, after defaulting) is a build-time
    /// error. Producers must pin to an exact triplet for write determinism. The `both` role is
    /// treated as a producer here — it implies the slice writes, which mandates an exact pin.
    private static Result<StreamVersionSpec> rejectProducerLatest(String streamName,
                                                                  StreamVersionSpec spec,
                                                                  Option<String> roleOpt) {
        var producesEvents = roleOpt.map(String::trim)
                                    .map(String::toLowerCase)
                                    .filter(role -> ROLE_PRODUCER.equals(role) || ROLE_BOTH.equals(role))
                                    .isPresent();

        if (producesEvents && spec.isLatest()) {
            return new StreamDeclarationError.ProducerVersionLatest(streamName, roleOpt.or("producer")).result();
        }

        return success(spec);
    }

    private static Result<Map<String, ConsumerConfig>> parseConsumerToml(String toml, String streamName) {
        return TomlParser.parse(toml)
                         .mapError(err -> cause("Stream config parse error: " + err.message()))
                         .flatMap(doc -> extractConsumerConfigs(doc, streamName));
    }

    private static boolean isStreamSection(String sectionName) {
        return sectionName.startsWith(STREAMS_PREFIX) && sectionName.length() > STREAMS_PREFIX.length();
    }

    /// The ONE parse of a `[streams.<alias>]` section into a validated [StreamConfig] (#1549). Deploy
    /// validation calls it over the blueprint's `resources.toml` only; slice activation calls it over the
    /// slice's composite configuration provider ([StreamSection]) — resources plus the slice, node, KV and
    /// environment overlays — so the same rules and typed refusals apply at both, and a value an overlay
    /// contributes is validated at activation rather than at deploy. A key it does not read is refused
    /// ([StreamDeclarationError.UnknownStreamKeys]), and so is a malformed, overflowing or below-minimum
    /// value ([StreamValues]), before anything is defaulted.
    static Result<StreamConfig> parseStreamConfig(StreamSection section) {
        return refuseUnknownKeys(section).flatMap(StreamConfigParser::parseStreamSection)
                                .flatMap(config -> validatePartitionCeiling(section.alias(),
                                                                            config))
                                .flatMap(config -> validateReplication(section.alias(),
                                                                       config));
    }

    /// Every key [#parseStreamConfig] reads directly under `[streams.<alias>]`, including the keys the
    /// resource-level parse reads (`version`, `source`, `role`). Sub-sections are not keys of the section.
    List<String> STREAM_SECTION_KEYS = List.of("version",
                                               "source",
                                               "role",
                                               "partitions",
                                               "retention",
                                               "retention-value",
                                               "retention-mode",
                                               "max-age",
                                               "max-count",
                                               "max-bytes",
                                               "auto-offset-reset",
                                               "max-event-size",
                                               "consistency",
                                               "replicas",
                                               "min-sync-replicas",
                                               "compression",
                                               "encryption-key-id");

    /// The keys of [#STREAM_SECTION_KEYS] whose values are integers.
    List<String> INTEGER_KEYS = List.of("partitions", "replicas", "min-sync-replicas");

    private static Result<StreamSection> refuseUnknownKeys(StreamSection section) {
        var unknown = section.keys().stream().filter(key -> !STREAM_SECTION_KEYS.contains(key)).sorted().toList();

        return unknown.isEmpty()
               ? success(section)
               : new StreamDeclarationError.UnknownStreamKeys(section.alias(), unknown, nearestKeys(unknown)).result();
    }

    private static Map<String, String> nearestKeys(List<String> unknown) {
        var suggestions = new LinkedHashMap<String, String>();

        unknown.forEach(key -> suggestions.put(key, nearestKey(key)));

        return suggestions;
    }

    /// The known key a stray key most plausibly meant: identical once `_` is read as `-`, else the closest
    /// by edit distance within `max(3, length / 2)`; empty when nothing is close.
    private static String nearestKey(String key) {
        var dashed = key.replace('_', '-');

        if (STREAM_SECTION_KEYS.contains(dashed)) {
            return dashed;
        }

        var threshold = Math.max(3, key.length() / 2);

        return STREAM_SECTION_KEYS.stream()
                                  .filter(known -> editDistance(dashed, known) <= threshold)
                                  .min(Comparator.comparingInt(known -> editDistance(dashed, known)))
                                  .orElse("");
    }

    private static int editDistance(String a, String b) {
        var previous = new int[b.length() + 1];
        var current = new int[b.length() + 1];

        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }

        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                var cost = a.charAt(i - 1) == b.charAt(j - 1)
                           ? 0
                           : 1;

                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }

            var swap = previous;

            previous = current;
            current = swap;
        }

        return previous[b.length()];
    }

    /// Every value is read into a typed [Result] first — [StreamValues] refuses a malformed, overflowing or
    /// out-of-range value instead of throwing or defaulting — and the config is built only from values that
    /// all parsed. The first refusal in key order is the section's cause (one typed cause, never a composite).
    private static Result<StreamConfig> parseStreamSection(StreamSection section) {
        var alias = section.alias();
        var partitions = section.integer("partitions")
                                .flatMap(value -> positive(alias, "partitions", value))
                                .map(value -> value.or(DEFAULT_PARTITIONS));
        var replicas = section.integer("replicas").map(value -> value.or(StreamConfig.DEFAULT.replicas()));
        var minSync = section.integer("min-sync-replicas")
                             .map(value -> value.or(StreamConfig.DEFAULT.minSyncReplicas()));
        var maxEventSize = optionalLong(section,
                                        "max-event-size",
                                        StreamValues::size,
                                        StreamConfig.DEFAULT.maxEventSizeBytes());
        var consistency = optionalEnum(section, "consistency", List.of("eventual", "strong"), "eventual");
        var compression = optionalEnum(section, "compression", List.of("none", "lz4", "zstd"), "none");
        var retention = parseRetention(section);

        return firstFailure(List.of(partitions, replicas, minSync, maxEventSize, consistency, compression, retention)).map(failure -> failure.<StreamConfig> map(_ -> StreamConfig.DEFAULT))
                           .or(() -> assemble(section,
                                              partitions,
                                              retention,
                                              maxEventSize,
                                              consistency,
                                              replicas,
                                              minSync,
                                              compression));
    }

    private static Result<StreamConfig> assemble(StreamSection section,
                                                 Result<Integer> partitions,
                                                 Result<RetentionPolicy> retention,
                                                 Result<Long> maxEventSize,
                                                 Result<String> consistency,
                                                 Result<Integer> replicas,
                                                 Result<Integer> minSync,
                                                 Result<String> compression) {
        return Result.all(partitions, retention, maxEventSize, consistency, replicas, minSync, compression).map((p, r, size, mode, rf, sync, codec) -> StreamConfig.streamConfig(section.alias(),
                                                                                                                                                                                 p,
                                                                                                                                                                                 r,
                                                                                                                                                                                 section.string("auto-offset-reset")
                                                                                                                                                                                        .or("earliest"),
                                                                                                                                                                                 size,
                                                                                                                                                                                 parseConsistencyMode(mode),
                                                                                                                                                                                 rf,
                                                                                                                                                                                 sync,
                                                                                                                                                                                 parseCompression(codec),
                                                                                                                                                                                 section.string("encryption-key-id")));
    }

    private static Option<Result<?>> firstFailure(List<Result<?>> results) {
        return Option.from(results.stream().filter(Result::isFailure).findFirst());
    }

    private static Result<Option<Integer>> positive(String alias, String key, Option<Integer> value) {
        return value.filter(present -> present < 1)
                    .map(present -> new StreamDeclarationError.ValueOutOfRange(alias,
                                                                               key,
                                                                               String.valueOf(present),
                                                                               1).<Option<Integer>> result())
                    .or(success(value));
    }

    private static Result<Long> optionalLong(StreamSection section,
                                             String key,
                                             Functions.Fn3<Result<Long>, String, String, String> parser,
                                             long defaultValue) {
        return section.string(key)
                      .map(raw -> parser.apply(section.alias(),
                                               key,
                                               raw))
                      .or(success(defaultValue));
    }

    private static Result<String> optionalEnum(StreamSection section,
                                               String key,
                                               List<String> allowed,
                                               String defaultValue) {
        return section.string(key)
                      .map(raw -> StreamValues.oneOf(section.alias(),
                                                     key,
                                                     raw,
                                                     allowed))
                      .or(success(defaultValue));
    }

    /// #1549: `time`, `size` and `compound` leave every bound they do not declare at the RetentionPolicy
    /// DEFAULT, never Long.MAX_VALUE. The ring's index is sized from the count, and an unbounded count cannot
    /// be allocated (stream creation threw once these forms first reached the runtime); the byte and age
    /// bounds follow the same rule so an undeclared bound means the default, uniformly. Under the default
    /// mode (ANY) eviction happens at whichever limit is hit first. `count` keeps its unbounded byte/age
    /// bounds: its count already sizes the ring. Every declared value is typed-refused when malformed,
    /// overflowing or below 1, as is an unknown `retention` or `retention-mode` spelling.
    private static Result<RetentionPolicy> parseRetention(StreamSection section) {
        var type = optionalEnum(section, "retention", List.of("count", "time", "size", "compound"), "count");
        var mode = optionalEnum(section, "retention-mode", List.of("any", "all"), "any").map(StreamConfigParser::parseRetentionMode);

        return type.flatMap(retentionType -> retentionWithMode(section, retentionType, mode));
    }

    /// Sequential, not `Result.all`: a refused spelling stays its own typed cause instead of a composite.
    private static Result<RetentionPolicy> retentionWithMode(StreamSection section,
                                                             String type,
                                                             Result<RetentionMode> mode) {
        return mode.flatMap(retentionMode -> retentionOf(section, type, retentionMode));
    }

    private static Result<RetentionPolicy> retentionOf(StreamSection section, String type, RetentionMode mode) {
        return switch (type) {
            case "compound" -> compoundRetention(section, mode);
            case "time" -> optionalLong(section, "retention-value", StreamValues::duration, DEFAULTS.maxAgeMs()).map(ageMs -> RetentionPolicy.retentionPolicy(DEFAULTS.maxCount(),
                                                                                                                                                              DEFAULTS.maxBytes(),
                                                                                                                                                              ageMs,
                                                                                                                                                              mode));
            case "size" -> optionalLong(section, "retention-value", StreamValues::size, DEFAULTS.maxBytes()).map(bytes -> RetentionPolicy.retentionPolicy(DEFAULTS.maxCount(),
                                                                                                                                                          bytes,
                                                                                                                                                          DEFAULTS.maxAgeMs(),
                                                                                                                                                          mode));
            default -> optionalLong(section, "retention-value", StreamValues::count, DEFAULTS.maxCount()).map(count -> RetentionPolicy.retentionPolicy(count,
                                                                                                                                                       Long.MAX_VALUE,
                                                                                                                                                       Long.MAX_VALUE,
                                                                                                                                                       mode));
        };
    }

    private static Result<RetentionPolicy> compoundRetention(StreamSection section, RetentionMode mode) {
        var maxCount = optionalLong(section, "max-count", StreamValues::count, DEFAULTS.maxCount());
        var maxBytes = optionalLong(section, "max-bytes", StreamValues::size, DEFAULTS.maxBytes());
        var maxAge = optionalLong(section, "max-age", StreamValues::duration, DEFAULTS.maxAgeMs());

        return firstFailure(List.of(maxCount, maxBytes, maxAge)).map(failure -> failure.<RetentionPolicy> map(_ -> DEFAULTS))
                           .or(() -> compoundPolicy(maxCount, maxBytes, maxAge, mode));
    }

    private static Result<RetentionPolicy> compoundPolicy(Result<Long> maxCount,
                                                          Result<Long> maxBytes,
                                                          Result<Long> maxAge,
                                                          RetentionMode mode) {
        return Result.all(maxCount, maxBytes, maxAge).map((count, bytes, age) -> RetentionPolicy.retentionPolicy(count,
                                                                                                                 bytes,
                                                                                                                 age,
                                                                                                                 mode));
    }

    private static Result<Map<String, ConsumerConfig>> extractConsumerConfigs(TomlDocument doc, String streamName) {
        var consumerPrefix = STREAMS_PREFIX + streamName + ".consumers.";
        var consumers = doc.sectionNames()
                           .stream()
                           .filter(sectionName -> sectionName.startsWith(consumerPrefix))
                           .filter(sectionName -> !sectionName.substring(consumerPrefix.length())
                                                              .contains("."))
                           .map(sectionName -> parseConsumerSection(doc,
                                                                    streamName,
                                                                    sectionName,
                                                                    sectionName.substring(consumerPrefix.length())))
                           .toList();

        return firstFailure(List.copyOf(consumers)).map(failure -> failure.<Map<String, ConsumerConfig>> map(_ -> Map.of()))
                           .or(() -> byGroup(consumers));
    }

    private static Result<Map<String, ConsumerConfig>> byGroup(List<Result<ConsumerConfig>> consumers) {
        return Result.allOf(consumers).map(configs -> configs.stream()
                                                             .collect(Collectors.toUnmodifiableMap(ConsumerConfig::groupId,
                                                                                                   Function.identity())));
    }

    /// `checkpoint-interval` is read as a duration of at least 1 ms, refused typed like every stream key (#1549);
    /// before, a value such as `5 min` threw `NumberFormatException` out of deploy validation.
    private static Result<ConsumerConfig> parseConsumerSection(TomlDocument doc,
                                                               String streamName,
                                                               String section,
                                                               String groupName) {
        return doc.getString(section, "checkpoint-interval")
                  .map(raw -> StreamValues.duration(streamName, "consumers." + groupName + ".checkpoint-interval", raw))
                  .or(success(1000L))
                  .map(checkpointIntervalMs -> consumerConfig(doc, section, groupName, checkpointIntervalMs));
    }

    private static ConsumerConfig consumerConfig(TomlDocument doc,
                                                 String section,
                                                 String groupName,
                                                 long checkpointIntervalMs) {
        var batchSize = doc.getInt(section, "batch-size").or(1);
        var processing = doc.getString(section, "processing")
                            .map(StreamConfigParser::parseProcessingMode)
                            .or(ProcessingMode.ORDERED);
        var onFailure = doc.getString(section, "on-failure")
                           .map(StreamConfigParser::parseErrorStrategy)
                           .or(ErrorStrategy.RETRY);
        var maxRetries = doc.getInt(section, "max-retries").or(3);
        var deadLetterStream = doc.getString(section, "dead-letter").or("");
        var readPreference = doc.getString(section, "read-preference")
                                .map(StreamConfigParser::parseReadPreference)
                                .or(ReadPreference.GOVERNOR);

        return ConsumerConfig.consumerConfig(groupName,
                                             batchSize,
                                             processing,
                                             onFailure,
                                             checkpointIntervalMs,
                                             maxRetries,
                                             deadLetterStream,
                                             readPreference);
    }

    private static ProcessingMode parseProcessingMode(String value) {
        return switch (value.toLowerCase()) {
            case "parallel" -> ProcessingMode.PARALLEL;
            default -> ProcessingMode.ORDERED;
        };
    }

    private static ConsistencyMode parseConsistencyMode(String value) {
        return switch (value.toLowerCase()) {
            case "strong" -> ConsistencyMode.STRONG;
            default -> ConsistencyMode.EVENTUAL;
        };
    }

    private static RetentionMode parseRetentionMode(String value) {
        return switch (value.toLowerCase()) {
            case "all" -> RetentionMode.ALL;
            default -> RetentionMode.ANY;
        };
    }

    private static StreamCompression parseCompression(String value) {
        return switch (value.toLowerCase()) {
            case "lz4" -> StreamCompression.LZ4;
            case "zstd" -> StreamCompression.ZSTD;
            default -> StreamCompression.NONE;
        };
    }

    private static ReadPreference parseReadPreference(String value) {
        return switch (value.toLowerCase()) {
            case "nearest" -> ReadPreference.NEAREST;
            case "any-replica", "any_replica", "any", "replica", "follower-only", "follower_only", "follower" -> ReadPreference.ANY_REPLICA;
            case "linearizable", "linearizable-read", "strong-read" -> ReadPreference.LINEARIZABLE;
            default -> ReadPreference.GOVERNOR;
        };
    }

    private static ErrorStrategy parseErrorStrategy(String value) {
        return switch (value.toLowerCase()) {
            case "skip" -> ErrorStrategy.SKIP;
            case "stall" -> ErrorStrategy.STALL;
            default -> ErrorStrategy.RETRY;
        };
    }
}
