// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.node.machine.LegCommand;
import org.pragmatica.aether.deployment.node.machine.LegCommand.LegKind;
import org.pragmatica.aether.invoke.CronExpression;
import org.pragmatica.aether.invoke.ScheduledTaskManager;
import org.pragmatica.aether.resource.ScheduleConfig;
import org.pragmatica.aether.resource.TopicConfig;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.OwningBlueprintResolver;
import org.pragmatica.aether.slice.blueprint.TopicAddressResolver;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.BlueprintStreamBindingsKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamRegistrationKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.TopicSubscriptionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.BlueprintStreamBindingsValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamRegistrationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopicSubscriptionValue;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.stream.BlueprintStreamAddresses;
import org.pragmatica.aether.slice.stream.KvBackedStreamRegistry;
import org.pragmatica.aether.slice.stream.StreamRegistryEntry;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.config.ConfigError;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ProviderBasedConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// The KV commands a slice's activation carries, **built and not sent** (phase-3 design 4.3 step map: "command builders copied from legacy.
/// Legacy builds **and** self-submits; the copy only builds"). Copied, not shared, because the legacy `NodeDeploymentState` is deleted in
/// phase 4b and the FSM2 chain must not depend on it; the drift risk the copy creates is what W-30 (a golden test against the legacy
/// builders, same inputs) guards (L3).
///
/// The sources copied, and their legacy counterparts in `NodeDeploymentState.Active`:
/// - topic subscriptions: `doPublishTopicSubscriptions` and `buildTopicSubscriptionPutCommand`;
/// - stream registrations: `doPublishStreamSubscriptions`, `readStreamSubscriptions`, `resolveStreamName`, the #1935 warnings;
/// - scheduled tasks: `doPublishScheduledTasks` and `buildValidatedScheduledTaskPutCommand`;
/// - stream references: `buildStreamRefCommands` (ACQUIRE).
///
/// Teardown builders (`PREPARE_TEARDOWN`) are not part of this tracer: the executor fails that step loudly.
final class SliceLegBuilders {
    private static final Logger log = LoggerFactory.getLogger(SliceLegBuilders.class);
    private static final String MANIFEST_DIR = "META-INF/slice/";

    private static final Fn3<Cause, String, String, String> INVALID_SCHEDULE_STRING = Causes.forThreeValues("Scheduled task '%s' has an invalid schedule '%s': %s");

    /// What the builders read.
    record Sources(NodeId self,
                   SliceStore sliceStore,
                   KVStore<AetherKey, AetherValue> kvStore,
                   Supplier<ClusterNode<KVCommand<AetherKey>>> cluster,
                   LongSupplier clock,
                   Supplier<OperatorWarningSink> warnings) {}

    private record ReactiveEntry(String category, String method, String config, Properties manifest, String prefix) {
        String property(String key) {
            return manifest.getProperty(prefix + key, "");
        }
    }

    private record SubscriptionEntry(ResourceAddress address, MethodName methodName) {}

    private record ScheduledEntry(String configSection, MethodName methodName) {}

    private record StreamEntry(String streamName,
                               String configSection,
                               MethodName methodName,
                               boolean batchMode,
                               String eventType) {}

    private record StreamRole(String alias) {}

    /// The `config-update` sections a slice declares and the factory class that receives their changes (`slice.factory` of the manifest
    /// that declares the first of them), copied from `readConfigUpdateEntriesFromManifest`.
    record ConfigUpdates(String factoryClassName, List<String> sections) {
        ConfigUpdates {
            sections = List.copyOf(sections);
        }

        boolean isEmpty() {
            return sections.isEmpty();
        }
    }

    private final Sources sources;
    private final Set<String> unregisteredConsumers = ConcurrentHashMap.newKeySet();

    SliceLegBuilders(Sources sources) {
        this.sources = sources;
    }

    /// The commands registering the legs of `slice` that ride in the ACTIVE batch (spec R-3.4.6), in the legacy chain's order: topic
    /// subscriptions, stream registrations, scheduled tasks. An invalid schedule string fails the whole step (it must not reach the KV).
    Result<List<LegCommand>> legs(Artifact artifact, Slice slice) {
        return scheduledTasks(artifact, slice).map(scheduled -> concat(topicSubscriptions(artifact, slice),
                                                                       streamRegistrations(artifact, slice),
                                                                       scheduled));
    }

    /// The per-instance stream reference counts to take with the ACTIVE row (event-stream-namespaces 8.5), one per declared stream role
    /// whose address the blueprint's bindings resolve.
    List<LegCommand> streamReferences(Artifact artifact, Slice slice) {
        var declarations = streamRoleDeclarations(artifact, slice);

        return declarations.isEmpty()
               ? List.of()
               : acquireCommands(artifact, declarations);
    }

    ConfigUpdates configUpdates(Artifact artifact, Slice slice) {
        var factory = new ArrayList<String>();
        var sections = new ArrayList<String>();

        for (var manifest : manifests(artifact, slice)) {
            var factoryClassName = manifest.getProperty("slice.factory", "");
            var declared = factoryClassName.isEmpty()
                           ? List.<String> of()
                           : configUpdateSections(manifest);

            if (!declared.isEmpty() && factory.isEmpty()) {
                factory.add(factoryClassName);
            }

            sections.addAll(declared);
        }

        return new ConfigUpdates(factory.stream().findFirst().orElse(""),
                                 sections);
    }

    private static List<String> configUpdateSections(Properties manifest) {
        var sections = new ArrayList<String>();
        var count = manifestCount(manifest.getProperty("reactive.count", "0"));

        for (int i = 0; i < count; i++) {
            var prefix = "reactive." + i + ".";

            if ("config-update".equals(manifest.getProperty(prefix + "category", ""))) {
                Option.option(manifest.getProperty(prefix + "config")).onPresent(sections::add);
            }
        }

        return sections;
    }

    @SafeVarargs
    private static List<LegCommand> concat(List<LegCommand>... parts) {
        var all = new ArrayList<LegCommand>();

        for (var part : parts) {
            all.addAll(part);
        }

        return List.copyOf(all);
    }

    // -- topic subscriptions ------------------------------------------------------------------------------------------
    private List<LegCommand> topicSubscriptions(Artifact artifact, Slice slice) {
        return subscriptionEntries(artifact, slice).stream()
                                  .map(entry -> topicPut(artifact, entry))
                                  .map(command -> LegCommand.legCommand(LegKind.TOPIC_SUBSCRIPTION, command))
                                  .toList();
    }

    /// #1448: the key is node-scoped, so this node's subscription record is its own row.
    private KVCommand<AetherKey> topicPut(Artifact artifact, SubscriptionEntry entry) {
        var key = TopicSubscriptionKey.topicSubscriptionKey(entry.address(),
                                                            artifact,
                                                            entry.methodName(),
                                                            sources.self());

        return new KVCommand.Put<>(key,
                                   TopicSubscriptionValue.topicSubscriptionValue(sources.self()));
    }

    private List<SubscriptionEntry> subscriptionEntries(Artifact artifact, Slice slice) {
        var result = new ArrayList<SubscriptionEntry>();

        for (var entry : reactiveBindings(artifact, slice)) {
            if ("subscription".equals(entry.category())) {
                subscriptionAddress(artifact, entry).flatMap(address -> MethodName.methodName(entry.method()).map(method -> new SubscriptionEntry(address,
                                                                                                                                                  method)))
                                   .option()
                                   .onPresent(result::add);
            }
        }

        return result;
    }

    /// The manifest-generated `topicName` wins; the `config`-section lookup is the legacy way of declaring a subscription (#396).
    private Result<ResourceAddress> subscriptionAddress(Artifact artifact, ReactiveEntry entry) {
        var manifestTopicName = entry.property("topicName");

        return manifestTopicName.isEmpty()
               ? topicAddressFromConfig(artifact, entry.config())
               : TopicAddressResolver.resolve(owningBlueprintOf(artifact), artifact, manifestTopicName);
    }

    private Result<ResourceAddress> topicAddressFromConfig(Artifact artifact, String configSection) {
        return configService(artifact).toResult(Causes.cause("ConfigService not available for topic resolution"))
                            .flatMap(service -> service.config(configSection, TopicConfig.class))
                            .flatMap(config -> TopicAddressResolver.resolve(owningBlueprintOf(artifact),
                                                                            artifact,
                                                                            config.topicName()));
    }

    private Option<Artifact> owningBlueprintOf(Artifact sliceArtifact) {
        return OwningBlueprintResolver.owningBlueprintOf(sources.kvStore(), sliceArtifact);
    }

    // -- stream registrations -----------------------------------------------------------------------------------------
    private List<LegCommand> streamRegistrations(Artifact artifact, Slice slice) {
        return streamEntries(artifact, slice).stream()
                            .map(entry -> streamPut(artifact, entry))
                            .map(command -> LegCommand.legCommand(LegKind.STREAM_REGISTRATION, command))
                            .toList();
    }

    private KVCommand<AetherKey> streamPut(Artifact artifact, StreamEntry entry) {
        var key = StreamRegistrationKey.streamRegistrationKey(entry.streamName(),
                                                              entry.configSection(),
                                                              artifact,
                                                              entry.methodName());
        var consumerGroup = artifact.base().asString() + "-" + entry.methodName().name();

        return new KVCommand.Put<>(key,
                                   StreamRegistrationValue.streamRegistrationValue(sources.self(),
                                                                                   consumerGroup,
                                                                                   entry.batchMode(),
                                                                                   entry.eventType()));
    }

    /// The registering read: a consumer that cannot be resolved here is one that will receive nothing, so it is raised (#1935). The slice
    /// still activates: one unregistered consumer must not take down the slice's other entry points.
    private List<StreamEntry> streamEntries(Artifact artifact, Slice slice) {
        var result = new ArrayList<StreamEntry>();

        for (var entry : reactiveBindings(artifact, slice)) {
            if ("stream".equals(entry.category())) {
                streamEntry(artifact, entry).onSuccess(_ -> raiseRegisteredAgain(artifact, entry))
                           .onFailure(cause -> raiseNotRegistered(artifact, entry, cause))
                           .option()
                           .onPresent(result::add);
            }
        }

        return result;
    }

    private Result<StreamEntry> streamEntry(Artifact artifact, ReactiveEntry entry) {
        var batchMode = Boolean.parseBoolean(entry.property("batch"));
        var eventType = entry.property("eventType");

        return resolveStreamName(artifact, entry.config()).flatMap(streamName -> MethodName.methodName(entry.method()).map(method -> new StreamEntry(streamName,
                                                                                                                                                     entry.config(),
                                                                                                                                                     method,
                                                                                                                                                     batchMode,
                                                                                                                                                     eventType)));
    }

    /// The stream a `[streams.X]` consumer subscribes to: the section suffix, qualified to the engine key (#1040) so that a consumer resolves
    /// to exactly the stream its publisher writes to.
    private Result<String> resolveStreamName(Artifact artifact, String configSection) {
        return configService(artifact).toResult(Causes.cause("ConfigService not available for stream name resolution"))
                            .flatMap(service -> requireSection(service, configSection))
                            .flatMap(_ -> streamNameOf(configSection))
                            .flatMap(alias -> BlueprintStreamAddresses.engineKeyFor(sources.kvStore(),
                                                                                    artifact,
                                                                                    alias));
    }

    private static Result<ConfigService> requireSection(ConfigService service, String configSection) {
        return service.hasSection(configSection)
               ? Result.success(service)
               : ConfigError.sectionNotFound(configSection).result();
    }

    private static Result<String> streamNameOf(String configSection) {
        var lastDot = configSection.lastIndexOf('.');

        return lastDot < 0 || lastDot == configSection.length() - 1
               ? Causes.cause("Stream section '" + configSection + "' has no stream name segment").result()
               : Result.success(configSection.substring(lastDot + 1));
    }

    private void raiseNotRegistered(Artifact artifact, ReactiveEntry entry, Cause cause) {
        unregisteredConsumers.add(consumerSubject(artifact, entry));
        OperatorWarnings.raise(log,
                               sources.warnings().get(),
                               OperatorWarningCode.STREAM_CONSUMER_NOT_REGISTERED,
                               consumerSubject(artifact, entry),
                               "Declarative stream consumer {}.{} on section [{}] could NOT be registered, it will receive nothing: {}",
                               artifact,
                               entry.method(),
                               entry.config(),
                               cause.message());
    }

    /// Raised only when `stream-consumer-not-registered` was raised for this same consumer and it now registers.
    private void raiseRegisteredAgain(Artifact artifact, ReactiveEntry entry) {
        var subject = consumerSubject(artifact, entry);

        if (unregisteredConsumers.remove(subject)) {
            OperatorWarnings.raise(log,
                                   sources.warnings().get(),
                                   OperatorWarningCode.STREAM_CONSUMER_REGISTERED_AGAIN,
                                   subject,
                                   "Declarative stream consumer {}.{} on section [{}] is registered again",
                                   artifact,
                                   entry.method(),
                                   entry.config());
        }
    }

    private static String consumerSubject(Artifact artifact, ReactiveEntry entry) {
        return artifact + "." + entry.method() + "[" + entry.config() + "]";
    }

    // -- scheduled tasks ----------------------------------------------------------------------------------------------
    /// The schedule string is node-local config resolved at activation time, the earliest point an invalid one can be rejected; every invalid
    /// entry is aggregated into one failure.
    private Result<List<LegCommand>> scheduledTasks(Artifact artifact, Slice slice) {
        var results = new ArrayList<Result<LegCommand>>();

        for (var entry : scheduledEntries(artifact, slice)) {
            scheduleConfig(artifact, entry.configSection()).onPresent(config -> results.add(scheduledPut(artifact,
                                                                                                         entry,
                                                                                                         config)));
        }

        return Result.allOf(results);
    }

    private Result<LegCommand> scheduledPut(Artifact artifact, ScheduledEntry entry, ScheduleConfig config) {
        var key = ScheduledTaskKey.scheduledTaskKey(entry.configSection(), artifact, entry.methodName());
        var taskName = entry.configSection() + "." + entry.methodName().name();
        Result<ScheduledTaskValue> validated = config.interval().isEmpty()
                                               ? CronExpression.parse(config.cron())
                                                               .map(_ -> ScheduledTaskValue.cronTask(sources.self(),
                                                                                                     config.cron(),
                                                                                                     config.executionMode()))
                                                               .mapError(cause -> INVALID_SCHEDULE_STRING.apply(taskName,
                                                                                                                config.cron(),
                                                                                                                cause.message()))
                                               : ScheduledTaskManager.IntervalParser.parse(config.interval())
                                                                                    .map(_ -> ScheduledTaskValue.intervalTask(sources.self(),
                                                                                                                              config.interval(),
                                                                                                                              config.executionMode()))
                                                                                    .mapError(cause -> INVALID_SCHEDULE_STRING.apply(taskName,
                                                                                                                                     config.interval(),
                                                                                                                                     cause.message()));

        return validated.map(value -> LegCommand.legCommand(LegKind.SCHEDULED_TASK, new KVCommand.Put<>(key, value)));
    }

    private List<ScheduledEntry> scheduledEntries(Artifact artifact, Slice slice) {
        var result = new ArrayList<ScheduledEntry>();

        for (var entry : reactiveBindings(artifact, slice)) {
            if ("scheduled".equals(entry.category())) {
                MethodName.methodName(entry.method())
                          .map(method -> new ScheduledEntry(entry.config(),
                                                            method))
                          .option()
                          .onPresent(result::add);
            }
        }

        return result;
    }

    private Option<ScheduleConfig> scheduleConfig(Artifact artifact, String configSection) {
        return configService(artifact).flatMap(service -> service.config(configSection, ScheduleConfig.class)
                                                                 .onFailure(cause -> log.warn("Slice {} schedule config binding failed for section {}: {}",
                                                                                              artifact,
                                                                                              configSection,
                                                                                              cause.message()))
                                                                 .option());
    }

    // -- stream references ----------------------------------------------------------------------------------------------
    private List<LegCommand> acquireCommands(Artifact artifact, List<StreamRole> declarations) {
        var bindings = owningBlueprintId(artifact).flatMap(this::bindingsFor);

        if (bindings.isEmpty()) {
            return List.of();
        }

        var registry = KvBackedStreamRegistry.kvBackedStreamRegistry(sources.cluster().get(),
                                                                     sources.kvStore());
        var commands = new ArrayList<LegCommand>();

        for (var declaration : declarations) {
            bindings.flatMap(found -> found.addressFor(declaration.alias()))
                    .onPresent(address -> commands.add(LegCommand.legCommand(LegKind.STREAM_REF,
                                                                             registry.acquireCommand(blueprintTemplate(address)))));
        }

        return List.copyOf(commands);
    }

    private StreamRegistryEntry blueprintTemplate(ResourceAddress address) {
        return StreamRegistryEntry.blueprint(address,
                                             RetentionPolicy.retentionPolicy(),
                                             Instant.ofEpochMilli(sources.clock().getAsLong()));
    }

    private Option<BlueprintStreamBindingsValue> bindingsFor(BlueprintId blueprintId) {
        return sources.kvStore()
                      .get(BlueprintStreamBindingsKey.blueprintStreamBindingsKey(blueprintId))
                      .filter(BlueprintStreamBindingsValue.class::isInstance)
                      .map(BlueprintStreamBindingsValue.class::cast);
    }

    private Option<BlueprintId> owningBlueprintId(Artifact artifact) {
        return sources.kvStore()
                      .get(SliceTargetKey.sliceTargetKey(artifact.base()))
                      .filter(SliceTargetValue.class::isInstance)
                      .map(SliceTargetValue.class::cast)
                      .flatMap(SliceTargetValue::owningBlueprint);
    }

    private List<StreamRole> streamRoleDeclarations(Artifact artifact, Slice slice) {
        var result = new ArrayList<StreamRole>();

        for (var manifest : manifests(artifact, slice)) {
            appendRoles(manifest, "stream.publisher.", "stream.publishers.count", result);
            appendRoles(manifest, "stream.access.", "stream.access.count", result);
        }

        return result;
    }

    private static void appendRoles(Properties props, String prefix, String countKey, List<StreamRole> sink) {
        var count = manifestCount(props.getProperty(countKey));

        for (int i = 0; i < count; i++) {
            Option.option(props.getProperty(prefix + i + ".config"))
                  .filter(section -> !section.isEmpty())
                  .map(SliceLegBuilders::aliasOf)
                  .filter(alias -> !alias.isEmpty())
                  .onPresent(alias -> sink.add(new StreamRole(alias)));
        }
    }

    private static int manifestCount(String raw) {
        return Option.option(raw)
                     .filter(value -> !value.isEmpty())
                     .flatMap(value -> Number.parseInt(value.trim()).option())
                     .or(0);
    }

    private static String aliasOf(String configSection) {
        var prefix = "streams.";

        return configSection.startsWith(prefix)
               ? configSection.substring(prefix.length())
               : "";
    }

    // -- manifests and config -------------------------------------------------------------------------------------------
    private List<ReactiveEntry> reactiveBindings(Artifact artifact, Slice slice) {
        var result = new ArrayList<ReactiveEntry>();

        for (var manifest : manifests(artifact, slice)) {
            var count = manifestCount(manifest.getProperty("reactive.count", "0"));

            for (int i = 0; i < count; i++) {
                var prefix = "reactive." + i + ".";

                result.add(new ReactiveEntry(manifest.getProperty(prefix + "category", ""),
                                             manifest.getProperty(prefix + "method", ""),
                                             manifest.getProperty(prefix + "config", ""),
                                             manifest,
                                             prefix));
            }
        }

        return result;
    }

    /// The `META-INF/slice/<Interface>.manifest` of every interface the slice implements; one that cannot be read is logged and skipped,
    /// as legacy does. FER (degrade forward): the slice activates without the legs that manifest would have declared; the guarantee earned
    /// is that one bad manifest does not stop the slice, the cost is a leg silently absent (the WARN is the only trace).
    private List<Properties> manifests(Artifact artifact, Slice slice) {
        var classLoader = slice.getClass().getClassLoader();
        var result = new ArrayList<Properties>();

        for (var iface : slice.getClass().getInterfaces()) {
            if (iface != Slice.class) {
                readManifest(classLoader,
                             MANIFEST_DIR + iface.getSimpleName() + ".manifest").onFailure(cause -> log.warn("Could not read manifest of {}: {}",
                                                                                                             artifact,
                                                                                                             cause.message()))
                            .option()
                            .flatMap(found -> found)
                            .onPresent(result::add);
            }
        }

        return result;
    }

    private static Result<Option<Properties>> readManifest(ClassLoader classLoader, String path) {
        return Result.lift(Causes::fromThrowable, () -> load(classLoader, path));
    }

    /// The one place a checked `IOException` is born: the try-with-resources on the JDK resource stream, reached only through
    /// `Result.lift` above (legacy suppresses the same rule at the same kind of site).
    @SuppressWarnings("JBCT-EX-01")
    private static Option<Properties> load(ClassLoader classLoader, String path) throws IOException {
        try (var stream = classLoader.getResourceAsStream(path)) {
            return Option.option(stream).isEmpty()
                   ? Option.none()
                   : Option.some(read(stream));
        }
    }

    private static Properties read(InputStream stream) throws IOException {
        var props = new Properties();

        props.load(stream);

        return props;
    }

    /// The slice's own composite when it has one, the process-wide service otherwise (legacy `sliceConfigService` and its fallback).
    private Option<ConfigService> configService(Artifact artifact) {
        return sources.sliceStore()
                      .sliceComposite(artifact)
                      .map(ProviderBasedConfigService::providerBasedConfigService)
                      .<ConfigService> map(service -> service)
                      .orElse(ConfigService::instance);
    }
}
