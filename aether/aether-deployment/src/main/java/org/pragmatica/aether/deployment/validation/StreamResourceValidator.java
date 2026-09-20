// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.validation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.blueprint.BlueprintNamespace;
import org.pragmatica.aether.slice.blueprint.StreamConfigParser;
import org.pragmatica.aether.slice.blueprint.StreamConfigParser.PartitionedStreamResources;
import org.pragmatica.aether.slice.blueprint.StreamConfigParser.RefusedSection;
import org.pragmatica.aether.slice.blueprint.StreamDeclarationError;
import org.pragmatica.aether.slice.blueprint.StreamSourceError;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.resource.ResourceAddress.ResourceAddressError;
import org.pragmatica.aether.slice.resource.ResourceVersion.ResourceVersionError;
import org.pragmatica.aether.slice.stream.StreamResource;
import org.pragmatica.aether.slice.stream.StreamVersionSpec;
import org.pragmatica.aether.slice.stream.StreamVersionSpec.StreamVersionSpecError;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes.CompositeCause;

import static org.pragmatica.aether.slice.resource.ResourceAddress.ResourceAddressError.General.*;


/// Deploy-time validator that enforces spec §15.1 runtime gates over a blueprint's stream
/// resources, aggregating **all** failures via [Result#allOf] (spec §15.1.1) instead of
/// short-circuiting at the first error.
///
/// Reusable from both the artifact-deploy path ([org.pragmatica.aether.deployment.cluster.BlueprintService#publishFromArtifact])
/// and the validate route. Returns:
///  - [Result#success] with [ValidatedStreamResources] (parsed map + non-blocking warnings) on
///    pass.
///  - [Result#failure] carrying [StreamValidationFailures] (a [CompositeCause] of
///    [StreamValidationFailure] entries + the warnings collected up to the failure point) on
///    rejection.
///
/// The HTTP route handler is responsible for translating that failure into a 422 response —
/// the validator itself does not depend on `http-routing` so it stays reusable from the CLI / audit
/// paths too (spec §15.1.2).
@SuppressWarnings("JBCT-SEQ-01")
public sealed interface StreamResourceValidator {
    String RULE_NAMESPACE_RESERVED = "namespace-reserved";
    /// #1282: a blueprint `External` source naming a stream kind only the runtime provisions.
    String RULE_SOURCE_RESERVED_KIND = "source-reserved-kind";
    String RULE_RESOURCES_PARSE = "resources-toml-parse";
    /// Per-section rule ids, one per parser cause TYPE ([#ruleFor]).
    String RULE_VERSION_AND_SOURCE_EXCLUSIVE = "version-and-source-mutually-exclusive";
    String RULE_PRODUCER_VERSION_EXACT = "producer-version-must-be-exact";
    String RULE_PARTITIONS_OVER_CEILING = "partitions-over-ceiling";
    String RULE_REPLICATION_INVALID = "replication-invalid";
    String RULE_SOURCE_ADDRESS_INVALID = "source-address-invalid";
    String RULE_NAMESPACE_INVALID = "namespace-invalid";
    String RULE_STREAM_NAME_INVALID = "stream-name-invalid";
    String RULE_VERSION_FORMAT_INVALID = "version-format-invalid";
    String RULE_STREAM_RESOURCE_INVALID = "stream-resource-invalid";
    String RULE_BLUEPRINT_NAMESPACE = "blueprint-namespace-invalid";
    String RULE_VERSION_PIN_RECOMMENDED = "version-pin-recommended";
    String RULE_INERT_STREAM_CONFIG = "inert-stream-config-key";
    String RULE_INERT_CONSUMER_CONFIG = "inert-consumer-config-key";
    /// #1262: a STRONG declaration fails the deploy through [#ensureHonourableConsistency], which runs
    /// before the bindings are derived. Its own id, so a runbook, a UI or a test keying on the rule can
    /// tell the refusal from the per-section class (#1336: see [#partition] for which other rules gate).
    String RULE_UNSUPPORTED_CONSISTENCY = "unsupported-stream-consistency";
    String STREAMS_SECTION_PREFIX = "streams.";
    String STRONG = "strong";
    List<String> CONSISTENCY_KEYS = List.of("consistency_mode", "consistency");
    /// #1336: per-section rules whose violation refuses the deploy instead of dropping the one alias —
    /// see [#partition]. Data, so a ruling flips one line. [#RULE_UNSUPPORTED_CONSISTENCY] is not listed
    /// because [#ensureHonourableConsistency] refuses it before any partition runs.
    Set<String> GATING_SECTION_RULES = Set.of(RULE_SOURCE_RESERVED_KIND);

    /// Run the full validation pass for a deploy attempt.
    ///
    /// `resourcesConfig` is the raw `resources.toml` content from the blueprint artifact (absent
    /// when the blueprint declares no resources). `blueprintArtifact` carries the Maven coords
    /// from which the namespace is derived. `roleHints` maps a stream alias to its inferred role
    /// per spec §11.1.2 — empty when no slice manifests are available (e.g., legacy DSL path).
    static Result<ValidatedStreamResources> validate(Option<String> resourcesConfig,
                                                     Artifact blueprintArtifact,
                                                     Map<String, String> roleHints) {
        var failures = new ArrayList<StreamValidationFailure>();
        var warnings = new ArrayList<StreamValidationWarning>();

        guardBlueprintNamespace(blueprintArtifact, failures);
        var resources = parseResourcesOrCollect(resourcesConfig, roleHints, failures);

        if (failures.isEmpty()) {
            guardInertConfig(resources, resourcesConfig, failures);
            resourcesConfig.onPresent(toml -> failures.addAll(consistencyFailures(toml)));
        }

        if (failures.isEmpty()) {
            collectMultiVersionWarnings(resources, resourcesConfig, roleHints, warnings);
        }

        if (failures.isEmpty()) {
            return Result.success(ValidatedStreamResources.validatedStreamResources(resources, warnings));
        }

        return StreamValidationFailures.streamValidationFailures(List.copyOf(failures),
                                                                 List.copyOf(warnings))
                                       .result();
    }

    /// #1336 — the deploy path's pass. [#validate] answers all-or-nothing, which is right for a refusal
    /// and wrong for a fold: `BlueprintService` took its failure as "no bindings", so one invalid
    /// `[streams.X]` section cost every valid declaration beside it. This pass keeps the two apart.
    ///
    ///  - A section that fails a rule is REJECTED: its alias gets no binding, the failure names it by
    ///    field and rule in [StreamValidationPartition#rejected], and every other section still binds.
    ///    A slice using a rejected alias fails at load naming that alias (`UnboundStreamAlias`); the
    ///    other slices are unaffected.
    ///  - A [Result#failure] is GATING: the deploy is refused with every failure found. Gating rules,
    ///    and why each leaves nothing to bind: [#RULE_RESOURCES_PARSE] — the document does not parse,
    ///    so no section has an outcome to keep; [#RULE_BLUEPRINT_NAMESPACE]/[#RULE_NAMESPACE_RESERVED]
    ///    raised on the blueprint artifact while at least one stream is declared — the namespace
    ///    prefixes every owned address, so there is no per-alias subset that survives it. With no
    ///    stream declared the namespace failure is reported and gates nothing.
    ///    [#RULE_SOURCE_RESERVED_KIND] (#1282), ruled gating for #1336: the management API refuses a
    ///    reserved stream kind with a typed 4xx on every mint path, and a deploy must refuse the same
    ///    declaration the same way — a deliberate reach into a reserved namespace, not a config slip —
    ///    rather than drop the alias and let the slice fail at load. Listed in [#GATING_SECTION_RULES].
    ///
    /// Every other rule is per-section — the parser's, one id per cause type ([#ruleFor]):
    /// [#RULE_VERSION_AND_SOURCE_EXCLUSIVE], [#RULE_PRODUCER_VERSION_EXACT], [#RULE_PARTITIONS_OVER_CEILING],
    /// [#RULE_REPLICATION_INVALID], [#RULE_SOURCE_ADDRESS_INVALID], [#RULE_NAMESPACE_INVALID],
    /// [#RULE_STREAM_NAME_INVALID], [#RULE_VERSION_FORMAT_INVALID], [#RULE_STREAM_RESOURCE_INVALID] for a cause
    /// type not named here — and #576's inert config keys ([#RULE_INERT_STREAM_CONFIG],
    /// [#RULE_INERT_CONSUMER_CONFIG]). Each names the alias it sits under, taken from the section the parser
    /// refused, never from message text. [#RULE_NAMESPACE_RESERVED] on an External source has no producer at
    /// this head: `ResourceAddress.resourceAddress(String)` accepts `system:` (spec §11.2 allows it).
    static Result<StreamValidationPartition> partition(Option<String> resourcesConfig,
                                                       Artifact blueprintArtifact,
                                                       Map<String, String> roleHints) {
        var namespaceFailures = new ArrayList<StreamValidationFailure>();

        guardBlueprintNamespace(blueprintArtifact, namespaceFailures);

        return resourcesConfig.map(toml -> StreamConfigParser.parseResourcesPartitioned(toml, roleHints))
                              .or(Result.success(PartitionedStreamResources.partitionedStreamResources(Map.of(),
                                                                                                       List.of())))
                              .mapError(cause -> gating(namespaceFailures,
                                                        List.of(documentFailure(cause))))
                              .flatMap(parsed -> partition(parsed, resourcesConfig, roleHints, namespaceFailures));
    }

    private static Result<StreamValidationPartition> partition(PartitionedStreamResources parsed,
                                                               Option<String> resourcesConfig,
                                                               Map<String, String> roleHints,
                                                               List<StreamValidationFailure> namespaceFailures) {
        var accepted = new LinkedHashMap<String, StreamResource>();
        var rejected = new ArrayList<StreamValidationFailure>();
        var warnings = new ArrayList<StreamValidationWarning>();

        parsed.rejected().forEach(cause -> rejected.add(toFailure(cause)));
        parsed.accepted()
              .forEach((alias, resource) -> acceptOrReject(alias, resource, resourcesConfig, accepted, rejected));
        var declaresStreams = !parsed.accepted().isEmpty() || !parsed.rejected().isEmpty();
        var gatesBySection = rejected.stream().anyMatch(failure -> GATING_SECTION_RULES.contains(failure.rule()));

        if ((!namespaceFailures.isEmpty() && declaresStreams) || gatesBySection) {
            return gating(namespaceFailures, rejected).result();
        }

        rejected.addAll(namespaceFailures);
        collectMultiVersionWarnings(accepted, resourcesConfig, roleHints, warnings);

        return Result.success(StreamValidationPartition.streamValidationPartition(accepted, rejected, warnings));
    }

    private static void acceptOrReject(String alias,
                                       StreamResource resource,
                                       Option<String> resourcesConfig,
                                       Map<String, StreamResource> accepted,
                                       List<StreamValidationFailure> rejected) {
        var inert = new ArrayList<StreamValidationFailure>();

        if (resource instanceof StreamResource.Owned owned) {
            guardStreamConfig(alias, owned.config(), inert);
            resourcesConfig.onPresent(toml -> guardConsumerConfigs(alias, toml, inert));
        }

        if (inert.isEmpty()) {
            accepted.put(alias, resource);
        } else {
            rejected.addAll(inert);
        }
    }

    private static StreamValidationFailures gating(List<StreamValidationFailure> first,
                                                   List<StreamValidationFailure> rest) {
        var failures = new ArrayList<>(first);

        failures.addAll(rest);

        return StreamValidationFailures.streamValidationFailures(failures, List.of());
    }

    /// #1262 deploy gate, run BEFORE [#partition] derives any binding: a stream declaring `STRONG`
    /// consistency fails the deploy. No write path can honour it (the consensus
    /// publish path has no production caller), so deploying it would produce a stream every write refuses.
    /// `BlueprintService` runs this on every `resources.toml` it deploys, on both the artifact and the body
    /// publish path, before any command is applied — and regardless of `registerOnly`, so a STRONG blueprint
    /// cannot even be registered. Reported under [#RULE_UNSUPPORTED_CONSISTENCY], not the non-gating
    /// [#RULE_INERT_STREAM_CONFIG].
    static Result<Unit> ensureHonourableConsistency(Option<String> resourcesConfig) {
        var failures = resourcesConfig.map(StreamResourceValidator::consistencyFailures).or(List.of());

        return failures.isEmpty()
               ? Result.unitResult()
               : StreamValidationFailures.streamValidationFailures(failures,
                                                                   List.of())
                                         .result();
    }

    /// A STRONG declaration under either key. `consistency_mode` is the key the provisioning config binder
    /// reads into `StreamConfig.consistencyMode` (the record component in snake case) — the one that would
    /// actually reach the write path. `consistency` is the key `StreamConfigParser` reads and nothing binds;
    /// a STRONG under it is refused too, because it asserts a guarantee the stream would silently not have.
    /// Read from the raw TOML so the check sees exactly the text the binder sees.
    private static List<StreamValidationFailure> consistencyFailures(String toml) {
        return TomlParser.parse(toml)
                         .map(StreamResourceValidator::consistencyFailures)
                         .or(List.of());
    }

    private static List<StreamValidationFailure> consistencyFailures(TomlDocument doc) {
        return doc.sectionNames()
                  .stream()
                  .filter(StreamResourceValidator::isStreamDeclarationSection)
                  .flatMap(section -> strongDeclarations(doc, section))
                  .toList();
    }

    private static Stream<StreamValidationFailure> strongDeclarations(TomlDocument doc, String section) {
        return CONSISTENCY_KEYS.stream().flatMap(key -> strongDeclaration(doc, section, key).stream());
    }

    private static Option<StreamValidationFailure> strongDeclaration(TomlDocument doc, String section, String key) {
        return doc.getString(section, key)
                  .filter(STRONG::equalsIgnoreCase)
                  .map(_ -> unsupportedConsistency(section, key));
    }

    private static boolean isStreamDeclarationSection(String section) {
        return section.startsWith(STREAMS_SECTION_PREFIX) && !section.substring(STREAMS_SECTION_PREFIX.length())
                                                                     .contains(".");
    }

    private static StreamValidationFailure unsupportedConsistency(String section, String key) {
        return StreamValidationFailure.streamValidationFailure("[" + section + "]",
                                                               RULE_UNSUPPORTED_CONSISTENCY,
                                                               key
                                                              + " 'strong' cannot be honoured — the consensus publish path is not wired in "
                                                              + "this release, so every write to the stream would be refused (#1262). Remove the "
                                                              + "key or set it to 'eventual'.");
    }

    private static void guardBlueprintNamespace(Artifact blueprintArtifact, List<StreamValidationFailure> failures) {
        BlueprintNamespace.deriveNamespace(blueprintArtifact).onFailure(cause -> failures.add(StreamValidationFailure.streamValidationFailure(blueprintArtifact.asString(),
                                                                                                                                              classifyNamespaceFailure(cause),
                                                                                                                                              cause.message())));
    }

    private static String classifyNamespaceFailure(Cause cause) {
        return cause.message()
                    .toLowerCase()
                    .contains("reserved")
               ? RULE_NAMESPACE_RESERVED
               : RULE_BLUEPRINT_NAMESPACE;
    }

    private static Map<String, StreamResource> parseResourcesOrCollect(Option<String> resourcesConfig,
                                                                       Map<String, String> roleHints,
                                                                       List<StreamValidationFailure> failures) {
        return resourcesConfig.map(toml -> StreamConfigParser.parseResourcesAggregating(toml, roleHints))
                              .or(Result.success(Map.<String, StreamResource> of()))
                              .fold(cause -> collectParseFailures(cause, failures),
                                    map -> map);
    }

    private static Map<String, StreamResource> collectParseFailures(Cause cause,
                                                                    List<StreamValidationFailure> failures) {
        if (cause instanceof CompositeCause composite) {
            composite.stream().forEach(inner -> failures.add(toFailure(inner)));
        } else {
            failures.add(toFailure(cause));
        }

        return Map.of();
    }

    /// #1336 review B1/B2: the field is the alias the parser refused, the rule is the cause's TYPE. Nothing
    /// is read from message text — the text quotes keys and lines (`Duplicate key … 'version'`), and a
    /// keyword guess mislabelled a document-level parse failure as `version-format-invalid` at
    /// `[streams.version]`, and an unparseable `source` as `namespace-invalid` at `[streams]`.
    private static StreamValidationFailure toFailure(Cause cause) {
        return switch (cause) {
            case RefusedSection refused -> StreamValidationFailure.streamValidationFailure("[streams." + refused.alias() + "]",
                                                                                           ruleFor(refused.cause()),
                                                                                           refused.message());
            default -> documentFailure(cause);
        };
    }

    /// A cause with no section: the document itself did not parse.
    private static StreamValidationFailure documentFailure(Cause cause) {
        return StreamValidationFailure.streamValidationFailure("[streams]", RULE_RESOURCES_PARSE, cause.message());
    }

    /// Every per-section cause the parser can raise at this head, by type. An unknown type is
    /// [#RULE_STREAM_RESOURCE_INVALID] — a new parser refusal is reported honestly as "this section is
    /// invalid" until a rule is named for it here.
    private static String ruleFor(Cause cause) {
        return switch (cause) {
            case StreamSourceError.ReservedKindSource _ -> RULE_SOURCE_RESERVED_KIND;
            case StreamDeclarationError.VersionAndSourceBothSet _ -> RULE_VERSION_AND_SOURCE_EXCLUSIVE;
            case StreamDeclarationError.ProducerVersionLatest _ -> RULE_PRODUCER_VERSION_EXACT;
            case StreamDeclarationError.PartitionsOverCeiling _ -> RULE_PARTITIONS_OVER_CEILING;
            case StreamDeclarationError.ReplicationInvalid _ -> RULE_REPLICATION_INVALID;
            case ResourceAddressError.General address -> addressRule(address);
            case ResourceVersionError _ -> RULE_VERSION_FORMAT_INVALID;
            case StreamVersionSpecError _ -> RULE_VERSION_FORMAT_INVALID;
            default -> RULE_STREAM_RESOURCE_INVALID;
        };
    }

    private static String addressRule(ResourceAddressError.General address) {
        return switch (address) {
            case NULL_VALUE, BLANK_VALUE, WRONG_FORMAT -> RULE_SOURCE_ADDRESS_INVALID;
            case NAMESPACE_INVALID -> RULE_NAMESPACE_INVALID;
            case NAMESPACE_RESERVED_FOR_APPS -> RULE_NAMESPACE_RESERVED;
            case NAME_INVALID, NAME_RESERVED -> RULE_STREAM_NAME_INVALID;
        };
    }

    /// #576: a blueprint's `[streams.X]`/`[streams.X.consumers.Y]` config parses and diffs cleanly,
    /// then most of it never reaches the runtime — a stream's segments always go through one
    /// unencrypted, uncompressed sink, and a declarative consumer always runs with the hardcoded
    /// [ConsumerConfig#consumerConfig(String)] defaults, because [StreamConfigParser#parseConsumers]
    /// has no production caller. Rather than accept a key that silently does nothing, reject it here
    /// — mirroring [org.pragmatica.aether.config.cluster.ClusterBootstrapConfigValidator]'s `PF-25`
    /// (#575) treatment of `[operations.auto_heal] enabled`. Only non-default values are rejected: a
    /// key that happens to equal the hardcoded default does not assert anything false, even though it
    /// is equally inert. #1262 adds `consistency = strong`: `ConsensusPublishPath` has no production
    /// caller, so the declared guarantee cannot be honoured on any write path.
    private static void guardInertConfig(Map<String, StreamResource> resources,
                                         Option<String> resourcesConfig,
                                         List<StreamValidationFailure> failures) {
        resources.forEach((alias, resource) -> {
            if (resource instanceof StreamResource.Owned owned) {
                guardStreamConfig(alias, owned.config(), failures);
                resourcesConfig.onPresent(toml -> guardConsumerConfigs(alias, toml, failures));
            }
        });
    }

    private static void guardStreamConfig(String alias, StreamConfig config, List<StreamValidationFailure> failures) {
        var field = "[streams." + alias + "]";

        config.encryptionKeyId()
              .onPresent(keyId -> failures.add(StreamValidationFailure.streamValidationFailure(field,
                                                                                               RULE_INERT_STREAM_CONFIG,
                                                                                               "encryption-key-id '" + keyId
                                                                                              + "' has no runtime effect — every stream is written through a single, shared "
                                                                                              + "segment sink with no encryptor wired to it (tracked as #253: BlockEncryptor has "
                                                                                              + "no production key source). Remove the key; it does not protect this stream's "
                                                                                              + "data at rest.")));
        if (config.compression() != StreamCompression.NONE) {
            failures.add(StreamValidationFailure.streamValidationFailure(field,
                                                                         RULE_INERT_STREAM_CONFIG,
                                                                         "compression '" + config.compression()
                                                                        + "' has no runtime effect — segments are always written uncompressed "
                                                                        + "regardless of this setting. Remove the key or set it to 'none'; stream "
                                                                        + "compression is not supported in 1.0 (descoped in #677)."));
        }

        if (!"earliest".equalsIgnoreCase(config.autoOffsetReset())) {
            failures.add(StreamValidationFailure.streamValidationFailure(field,
                                                                         RULE_INERT_STREAM_CONFIG,
                                                                         "auto-offset-reset '" + config.autoOffsetReset()
                                                                        + "' has no runtime effect — a never-committed consumer always starts at offset 0 "
                                                                        + "(earliest) per the #478 ruling, permanently, not as a gap to be closed later. "
                                                                        + "Remove the key or set it to 'earliest', its only honest value."));
        }
    }

    private static void guardConsumerConfigs(String alias, String toml, List<StreamValidationFailure> failures) {
        StreamConfigParser.parseConsumers(toml, alias).onSuccess(consumers -> consumers.forEach((group, config) -> guardConsumerConfig(alias,
                                                                                                                                       group,
                                                                                                                                       config,
                                                                                                                                       failures)));
    }

    private static void guardConsumerConfig(String alias,
                                            String group,
                                            ConsumerConfig config,
                                            List<StreamValidationFailure> failures) {
        var defaults = ConsumerConfig.consumerConfig(config.groupId());
        var field = "[streams." + alias + ".consumers." + group + "]";

        if (config.maxBatchSize() != defaults.maxBatchSize()) {
            failures.add(inertConsumerFailure(field,
                                              "batch-size",
                                              String.valueOf(config.maxBatchSize())));
        }

        if (config.processingMode() != defaults.processingMode()) {
            failures.add(inertConsumerFailure(field,
                                              "processing",
                                              config.processingMode().toString().toLowerCase()));
        }

        if (config.errorStrategy() != defaults.errorStrategy()) {
            failures.add(inertConsumerFailure(field,
                                              "on-failure",
                                              config.errorStrategy().toString().toLowerCase()));
        }

        if (!config.checkpointInterval().equals(defaults.checkpointInterval())) {
            failures.add(inertConsumerFailure(field,
                                              "checkpoint-interval",
                                              config.checkpointInterval().toString()));
        }

        if (config.maxRetries() != defaults.maxRetries()) {
            failures.add(inertConsumerFailure(field,
                                              "max-retries",
                                              String.valueOf(config.maxRetries())));
        }

        if (!config.deadLetterStream().equals(defaults.deadLetterStream())) {
            failures.add(inertConsumerFailure(field, "dead-letter", config.deadLetterStream()));
        }

        if (config.readPreference() != defaults.readPreference()) {
            failures.add(inertConsumerFailure(field,
                                              "read-preference",
                                              config.readPreference().toString().toLowerCase()));
        }
    }

    private static StreamValidationFailure inertConsumerFailure(String field, String key, String value) {
        return StreamValidationFailure.streamValidationFailure(field,
                                                               RULE_INERT_CONSUMER_CONFIG,
                                                               key
                                                              + " = '" + value
                                                              + "' has no runtime effect — per-consumer TOML config (`[streams.X.consumers.Y]`) is "
                                                              + "parsed but never reaches the runtime consumer (StreamConfigParser#parseConsumers has "
                                                              + "no production caller, per #576); every declarative consumer runs with hardcoded "
                                                              + "defaults regardless of this key. Remove it: per-consumer tuning is not supported "
                                                              + "in 1.0 (descoped in #677; post-GA wiring is its own epic).");
    }

    /// Spec §11.1.3 multi-version warning: when a blueprint declares two or more
    /// `(stream, distinct-version)` pairs sharing the same alias **and** at least one declaration
    /// resolved its version from a default (no explicit `version` field), recommend an explicit
    /// pin. Implemented at the (alias, version-spec) level since this validator runs over a single
    /// blueprint's resources.toml. Cross-blueprint multi-version is a cluster-state concern handled
    /// by the registry, not here.
    private static void collectMultiVersionWarnings(Map<String, StreamResource> resources,
                                                    Option<String> resourcesConfig,
                                                    Map<String, String> roleHints,
                                                    List<StreamValidationWarning> warnings) {
        var explicitVersionAliases = collectExplicitVersionAliases(resourcesConfig);
        var byAlias = groupByAlias(resources);

        byAlias.forEach((alias, entries) -> {
            if (entries.size() <= 1) {
                return;
            }

            var anyDefaulted = entries.stream()
                                      .anyMatch(spec -> !explicitVersionAliases.contains(alias));

            if (anyDefaulted) {
                warnings.add(StreamValidationWarning.streamValidationWarning("[streams." + alias + "]",
                                                                             RULE_VERSION_PIN_RECOMMENDED,
                                                                             "Stream '" + alias
                                                                            + "' has multiple version declarations and at least one omits "
                                                                            + "'version'; pin every declaration explicitly per spec §11.1.3"));
            }
        });
        unused(roleHints);
    }

    /// `roleHints` is reserved for future cross-namespace warnings (e.g., consumer with mismatched
    /// inferred-vs-explicit role). Currently used only by the parser; the warning pass is a
    /// no-op on it. Kept on the signature so the wiring stays stable when those rules land.
    @Contract
    private static void unused(Map<String, String> roleHints) {
        // No-op consume: roleHints is reserved for future cross-namespace warning rules; the
        // current pass deliberately does nothing with it (see method doc). Referencing it keeps
        // the parameter live without a guard that would contradict the documented no-op contract.
        Option.option(roleHints);
    }

    private static Set<String> collectExplicitVersionAliases(Option<String> resourcesConfig) {
        return resourcesConfig.map(StreamResourceValidator::scanExplicitVersionAliases)
                              .or(Set.of());
    }

    private static Set<String> scanExplicitVersionAliases(String toml) {
        var aliases = new LinkedHashSet<String>();
        var currentAlias = new StringBuilder();
        var aliasActive = false;

        for (var rawLine : toml.split("\n")) {
            var line = rawLine.trim();

            if (line.startsWith("[streams.")) {
                aliasActive = parseAliasHeader(line, currentAlias);
                continue;
            }

            if (line.startsWith("[")) {
                aliasActive = false;
                continue;
            }

            if (aliasActive && line.startsWith("version")) {
                aliases.add(currentAlias.toString());
            }
        }

        return Set.copyOf(aliases);
    }

    private static boolean parseAliasHeader(String line, StringBuilder out) {
        var inner = line.substring(1, line.length() - 1).trim();

        if (!inner.startsWith("streams.")) {
            return false;
        }

        var alias = inner.substring("streams.".length());

        if (alias.contains(".")) {
            return false;
        }

        out.setLength(0);
        out.append(alias);

        return true;
    }

    private static Map<String, List<StreamVersionSpec>> groupByAlias(Map<String, StreamResource> resources) {
        var byAlias = new HashMap<String, List<StreamVersionSpec>>();

        resources.forEach((alias, resource) -> versionSpecOf(resource).onPresent(spec -> byAlias.computeIfAbsent(alias,
                                                                                                                 _ -> new ArrayList<>())
                                                                                                .add(spec)));

        return byAlias;
    }

    private static Option<StreamVersionSpec> versionSpecOf(StreamResource resource) {
        return switch (resource) {
            case StreamResource.Owned owned -> Option.some(owned.version());
            case StreamResource.External _ -> Option.none();
        };
    }

    /// Defensive double-check of the spec §4.3 reserved-namespace rule. The address parser already
    /// enforces this; this hook keeps the runtime gate explicit per spec §15.1.
    static boolean isReservedNamespace(String namespace) {
        return ResourceAddress.isReservedNamespace(namespace);
    }

    record unused() implements StreamResourceValidator {}

    /// Convenience constructor for the no-role-hints case (legacy DSL path / tests).
    static Result<ValidatedStreamResources> validate(Option<String> resourcesConfig, Artifact blueprintArtifact) {
        return validate(resourcesConfig, blueprintArtifact, Map.of());
    }

    /// Convenience: project resources only when the validator is being used purely for the parsed
    /// map (validate route).
    static Result<Map<String, StreamResource>> validateResources(Option<String> resourcesConfig,
                                                                 Artifact blueprintArtifact,
                                                                 Map<String, String> roleHints) {
        return validate(resourcesConfig, blueprintArtifact, roleHints).map(ValidatedStreamResources::resources);
    }

    /// Mutating helper retained for the per-warning-aggregation contract used by the orchestrator.
    /// Kept package-private so test code can exercise the warning collector in isolation if needed.
    static List<StreamValidationWarning> emptyWarnings() {
        return new ArrayList<>();
    }

    /// Helper for tests that need to flatten a CompositeCause for assertion convenience without
    /// reaching into Pragmatica internals.
    static List<StreamValidationFailure> flattenFailures(Cause cause) {
        var collected = new LinkedHashMap<String, StreamValidationFailure>();

        flattenInto(cause, collected);

        return List.copyOf(collected.values());
    }

    private static void flattenInto(Cause cause, Map<String, StreamValidationFailure> sink) {
        switch (cause) {
            case StreamValidationFailures composite -> composite.failures().forEach(failure -> sink.putIfAbsent(failure.field()
                                                                                                               + "::" + failure.rule(),
                                                                                                                failure));
            case StreamValidationFailure failure -> sink.putIfAbsent(failure.field() + "::" + failure.rule(),
                                                                     failure);
            case CompositeCause composite -> composite.stream().forEach(inner -> flattenInto(inner, sink));
            default -> {
                var fallback = StreamValidationFailure.streamValidationFailure("[streams]",
                                                                               "stream-resource-invalid",
                                                                               cause.message());

                sink.putIfAbsent(fallback.field() + "::" + fallback.rule(),
                                 fallback);
            }
        }
    }
}
