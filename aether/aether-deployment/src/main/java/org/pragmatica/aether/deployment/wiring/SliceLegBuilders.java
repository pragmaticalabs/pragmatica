// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.node.machine.LegCommand;
import org.pragmatica.aether.deployment.node.machine.LegCommand.LegKind;
import org.pragmatica.aether.deployment.validation.TopicTierPreflight;
import org.pragmatica.aether.invoke.CronExpression;
import org.pragmatica.aether.invoke.ScheduledTaskManager;
import org.pragmatica.aether.resource.ScheduleConfig;
import org.pragmatica.aether.resource.TopicConfig;
import org.pragmatica.aether.resource.TopicDurability;
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
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskPauseKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamRegistrationKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.TopicPublisherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.TopicSubscriptionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.BlueprintStreamBindingsValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamRegistrationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopicPublisherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopicSubscriptionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopicTier;
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
import org.pragmatica.lang.Unit;
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
/// - topic subscriptions and publishers: `doPublishTopicSubscriptions`, `buildTopicSubscriptionPutCommand`, `buildTopicPublisherPutCommand`,
///   `readPublishersFromManifest` and the #1337 tier refusal `refuseTierConflicts`;
/// - stream registrations: `doPublishStreamSubscriptions`, `readStreamSubscriptions`, `resolveStreamName`, the #1935 warnings;
/// - scheduled tasks: `doPublishScheduledTasks` and `buildValidatedScheduledTaskPutCommand`;
/// - stream references: `buildStreamRefCommands` (ACQUIRE, and RELEASE for the teardown).
///
/// **Teardown** ([#teardown], the `PREPARE_TEARDOWN` step) is the same copy in the other direction: the legacy `doUnpublish*` builders and the
/// RELEASE half of `buildStreamRefCommands`. It builds every remove and release the slice's manifests declare; it does not decide which of them
/// go. Whether this node was the last replica of the artifact, which keeps the cluster-scoped stream registrations and scheduled tasks while
/// another replica hosts them, is the node FSM's rule (R-3.4.5, `Batches.keepsOnTeardown`), applied when it assembles the batch; legacy applied it
/// here (`artifactHostedElsewhere`) because it had no model to ask.
final class SliceLegBuilders {
    private static final Logger log = LoggerFactory.getLogger(SliceLegBuilders.class);
    private static final String MANIFEST_DIR = "META-INF/slice/";
    /// The manifest key naming a routed slice's JSON codec settings section (#2394), as legacy `ConfigUpdateSections.JSON_SETTINGS_SECTION_KEY`.
    private static final String JSON_SETTINGS_SECTION_KEY = "json.settings.section";
    /// The attempt of a slice's holder key, as legacy's `SLICE_HOLDER_ATTEMPT`: a node runs one instance of an artifact at a time, so acquire
    /// and release of the same instance name the same key.
    private static final long SLICE_HOLDER_ATTEMPT = 0L;

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

    private record SubscriptionEntry(ResourceAddress address, MethodName methodName, TopicTier tier) {}

    /// A topic the slice publishes and the tier it delivers on (#1337); [TopicTier#UNDECLARED] when the tier could not be read.
    private record PublisherEntry(ResourceAddress address, TopicTier tier) {}

    private record ScheduledEntry(String configSection, MethodName methodName) {}

    private record StreamEntry(String streamName,
                               String configSection,
                               MethodName methodName,
                               boolean batchMode,
                               String eventType) {}

    private record StreamRole(String alias) {}

    /// The `config-update` sections a slice declares, its `[json]` settings section, and the factory class that receives their changes
    /// (`slice.factory` of the manifest that declares the first of them), copied from `readConfigUpdateEntriesFromManifest`.
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
    /// subscriptions and publishers, stream registrations, scheduled tasks. A tier conflict with a committed topic row of another slice
    /// (#1337) and an invalid schedule string each fail the whole step (neither must reach the KV); the tier refusal is checked first, as
    /// the legacy topic pass runs before the scheduled-task pass.
    Result<List<LegCommand>> legs(Artifact artifact, Slice slice) {
        var subscriptions = subscriptionEntries(artifact, slice);
        var publishers = publisherEntries(artifact, slice);

        return refuseTierConflicts(artifact, subscriptions, publishers).flatMap(_ -> scheduledTasks(artifact, slice))
                                  .map(scheduled -> concat(topicRows(artifact, subscriptions, publishers),
                                                           streamRegistrations(artifact, slice),
                                                           scheduled));
    }

    /// The per-instance stream references to take with the ACTIVE row (event-stream-namespaces 8.5), one per declared stream role whose
    /// address the blueprint's bindings resolve.
    ///
    /// **Holder keys, as legacy (#1978, I-36).** Each reference is this node's own holder key `(stream, node, artifact, attempt 0)`
    /// (`KvBackedStreamRegistry.acquireCommands`), plus the entry's template record when the store holds none yet. Nothing reads a count, so two
    /// instances that build before either commits keep both references, and a rebuild after a commit puts the same key again. The leg commands
    /// still ride beside the version-fenced observed Put, not inside its fence: a refused observed Put does not undo a leg that landed, which for
    /// a reference is the same key the instance's next activation puts.
    List<LegCommand> streamReferences(Artifact artifact, Slice slice) {
        var declarations = streamRoleDeclarations(artifact, slice);

        return declarations.isEmpty()
               ? List.of()
               : acquireCommands(artifact, declarations);
    }

    /// The commands of the teardown batch (N13, N16): this node's topic-subscription and topic-publisher rows, the stream registrations, this node's stream
    /// reference holder keys to release, and the scheduled tasks with their pause keys, in the legacy chain's order. Every one is a remove or a release; the
    /// last-replica filter on the cluster-scoped ones is the FSM's (see the class comment).
    List<LegCommand> teardown(Artifact artifact, Slice slice) {
        return concat(topicRemovals(artifact, slice),
                      streamRemovals(artifact, slice),
                      referenceReleases(artifact, slice),
                      scheduledRemovals(artifact, slice));
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

    /// The `config-update` sections, then the section naming a routed slice's JSON codec settings (#2394, legacy `ConfigUpdateSections`), so a
    /// committed `json.*` change reaches the factory's `notifyConfigUpdate` with section `json`.
    private static List<String> configUpdateSections(Properties manifest) {
        var sections = new ArrayList<String>();
        var count = manifestCount(manifest.getProperty("reactive.count", "0"));

        for (int i = 0; i < count; i++) {
            var prefix = "reactive." + i + ".";

            if ("config-update".equals(manifest.getProperty(prefix + "category", ""))) {
                Option.option(manifest.getProperty(prefix + "config")).onPresent(sections::add);
            }
        }

        Option.option(manifest.getProperty(JSON_SETTINGS_SECTION_KEY)).onPresent(sections::add);

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

    // -- topic subscriptions and publishers ---------------------------------------------------------------------------
    /// The subscription rows, then the publisher rows whose tier was read (#1337: an UNDECLARED tier writes no row, so nothing is refused on a
    /// guess), as legacy batches them. Both are node-scoped rows, so both carry [LegKind#TOPIC_SUBSCRIPTION]: the teardown always removes them.
    private List<LegCommand> topicRows(Artifact artifact,
                                       List<SubscriptionEntry> subscriptions,
                                       List<PublisherEntry> publishers) {
        var subscriptionPuts = subscriptions.stream().map(entry -> topicPut(artifact, entry));
        var publisherPuts = publishers.stream()
                                      .filter(entry -> entry.tier() != TopicTier.UNDECLARED)
                                      .map(entry -> publisherPut(artifact, entry));

        return Stream.concat(subscriptionPuts, publisherPuts)
                     .map(command -> LegCommand.legCommand(LegKind.TOPIC_SUBSCRIPTION, command))
                     .toList();
    }

    /// #1337: the key is node-scoped, as the subscription's (#1448).
    private KVCommand<AetherKey> publisherPut(Artifact artifact, PublisherEntry entry) {
        return new KVCommand.Put<>(TopicPublisherKey.topicPublisherKey(entry.address(), artifact, sources.self()),
                                   TopicPublisherValue.topicPublisherValue(sources.self(), entry.tier()));
    }

    /// #1448: the key is node-scoped, so this node's subscription record is its own row.
    private KVCommand<AetherKey> topicPut(Artifact artifact, SubscriptionEntry entry) {
        var key = TopicSubscriptionKey.topicSubscriptionKey(entry.address(),
                                                            artifact,
                                                            entry.methodName(),
                                                            sources.self());

        return new KVCommand.Put<>(key,
                                   TopicSubscriptionValue.topicSubscriptionValue(sources.self(), entry.tier()));
    }

    /// This node's subscription rows, then its publisher rows: every declared publisher, the UNDECLARED ones included, as legacy removes them.
    private List<LegCommand> topicRemovals(Artifact artifact, Slice slice) {
        var subscriptionRemoves = subscriptionEntries(artifact, slice).stream()
                                                     .map(entry -> topicRemove(artifact, entry));
        var publisherRemoves = publisherEntries(artifact, slice).stream()
                                               .map(entry -> publisherRemove(artifact, entry));

        return Stream.concat(subscriptionRemoves, publisherRemoves)
                     .map(command -> LegCommand.legCommand(LegKind.TOPIC_SUBSCRIPTION, command))
                     .toList();
    }

    private KVCommand<AetherKey> publisherRemove(Artifact artifact, PublisherEntry entry) {
        return new KVCommand.Remove<>(TopicPublisherKey.topicPublisherKey(entry.address(), artifact, sources.self()));
    }

    /// #1448: scoped to this node, so an unload removes the row this node published and leaves every other instance's row, and therefore the
    /// durable group's declaration, standing. The key must match [#topicPut] exactly or the remove silently removes nothing.
    private KVCommand<AetherKey> topicRemove(Artifact artifact, SubscriptionEntry entry) {
        return new KVCommand.Remove<>(TopicSubscriptionKey.topicSubscriptionKey(entry.address(),
                                                                                artifact,
                                                                                entry.methodName(),
                                                                                sources.self()));
    }

    /// #1337: the manifest's `context=message` marker (#386 D5) is the only thing that makes a handler need the durable tier; derived exactly as the
    /// legacy `NodeDeploymentState` derives it, so the row this leg writes equals the legacy row.
    private static TopicTier requiredTier(ReactiveEntry entry) {
        return "message".equals(entry.property("context"))
               ? TopicTier.DURABLE
               : TopicTier.ANY;
    }

    private List<SubscriptionEntry> subscriptionEntries(Artifact artifact, Slice slice) {
        var result = new ArrayList<SubscriptionEntry>();

        for (var entry : reactiveBindings(artifact, slice)) {
            if ("subscription".equals(entry.category())) {
                subscriptionAddress(artifact, entry).flatMap(address -> MethodName.methodName(entry.method()).map(method -> new SubscriptionEntry(address,
                                                                                                                                                  method,
                                                                                                                                                  requiredTier(entry))))
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

    /// The `publish.topic.N` bindings of every manifest whose address resolves, copied from `readPublishersFromManifest`.
    private List<PublisherEntry> publisherEntries(Artifact artifact, Slice slice) {
        var result = new ArrayList<PublisherEntry>();

        for (var entry : publishBindings(artifact, slice)) {
            subscriptionAddress(artifact, entry).option()
                               .map(address -> new PublisherEntry(address,
                                                                  declaredPublisherTier(artifact,
                                                                                        entry.config())))
                               .onPresent(result::add);
        }

        return result;
    }

    private List<ReactiveEntry> publishBindings(Artifact artifact, Slice slice) {
        var result = new ArrayList<ReactiveEntry>();

        for (var manifest : manifests(artifact, slice)) {
            var count = manifestCount(manifest.getProperty("publish.topics.count", "0"));

            for (int i = 0; i < count; i++) {
                var prefix = "publish.topic." + i + ".";

                result.add(new ReactiveEntry("publisher",
                                             "",
                                             manifest.getProperty(prefix + "config", ""),
                                             manifest,
                                             prefix));
            }
        }

        return result;
    }

    /// What the publisher's topic section declares, read through the slice's own composite: an ABSENT section is the ephemeral default, a
    /// section that cannot be bound, or no config service at all, is [TopicTier#UNDECLARED] (legacy `declaredPublisherTier`).
    private TopicTier declaredPublisherTier(Artifact artifact, String section) {
        return configService(artifact).map(service -> publisherTierIn(service, section))
                            .or(TopicTier.UNDECLARED);
    }

    private static TopicTier publisherTierIn(ConfigService service, String section) {
        return service.hasSection(section)
               ? service.config(section, TopicConfig.class)
                        .map(SliceLegBuilders::deliveryTier)
                        .or(TopicTier.UNDECLARED)
               : TopicTier.EPHEMERAL;
    }

    private static TopicTier deliveryTier(TopicConfig config) {
        return config.durability() == TopicDurability.DURABLE
               ? TopicTier.DURABLE
               : TopicTier.EPHEMERAL;
    }

    /// #1337, copied from legacy `refuseTierConflicts`: the cross-deployment half of the tier check, in BOTH orders, against the committed
    /// topic rows. A subscriber whose handler needs the durable tier is refused while an ephemeral publisher row of its topic stands; a
    /// publisher that delivers ephemerally is refused while a durable subscriber row stands. A row of the same slice at another version does
    /// not count (an upgrade replaces its predecessor). Two slices activating at the same instant can each miss the other's row; the runtime
    /// refusal in `TopicPublisher` is the backstop for that, as under legacy.
    private Result<Unit> refuseTierConflicts(Artifact artifact,
                                             List<SubscriptionEntry> subscriptions,
                                             List<PublisherEntry> publishers) {
        var conflicts = new ArrayList<String>();

        subscriptions.stream()
                     .filter(subscription -> subscription.tier()
                                                         .requiresDurable())
                     .forEach(subscription -> ephemeralPublishersAt(subscription.address(),
                                                                    artifact).forEach(publisher -> conflicts.add(subscriberConflict(artifact,
                                                                                                                                    subscription,
                                                                                                                                    publisher))));
        publishers.stream()
                  .filter(publisher -> publisher.tier()
                                                .deliversEphemeral())
                  .forEach(publisher -> durableSubscribersAt(publisher.address(),
                                                             artifact).forEach(subscriber -> conflicts.add(publisherConflict(artifact,
                                                                                                                             publisher,
                                                                                                                             subscriber))));

        return conflicts.isEmpty()
               ? Result.unitResult()
               : TopicTierPreflight.TopicTierMismatchRefused.FACTORY.apply(String.join("; ", conflicts)).result();
    }

    private static String subscriberConflict(Artifact artifact, SubscriptionEntry subscription, String publisher) {
        return "topic " + subscription.address()
                                      .asString()
             + " is subscribed by " + artifact.asString()
             + "." + subscription.methodName()
                                 .name()
             + ", whose handler takes a MessageContext and needs a durable topic, but " + publisher
             + " publishes it on the EPHEMERAL tier";
    }

    private static String publisherConflict(Artifact artifact, PublisherEntry publisher, String subscriber) {
        return "topic " + publisher.address()
                                   .asString()
             + " is published on the EPHEMERAL tier by " + artifact.asString()
             + " but subscribed by " + subscriber
             + ", whose handler takes a MessageContext and needs a durable topic";
    }

    private List<String> ephemeralPublishersAt(ResourceAddress address, Artifact self) {
        var found = new ArrayList<String>();

        sources.kvStore()
               .forEach(TopicPublisherKey.class,
                        TopicPublisherValue.class,
                        (key, value) -> collectConflictingRow(found,
                                                              new TopicRow(key.address(),
                                                                           key.artifact(),
                                                                           key.nodeId(),
                                                                           value.tier().deliversEphemeral()),
                                                              address,
                                                              self));

        return found;
    }

    private List<String> durableSubscribersAt(ResourceAddress address, Artifact self) {
        var found = new ArrayList<String>();

        sources.kvStore()
               .forEach(TopicSubscriptionKey.class,
                        TopicSubscriptionValue.class,
                        (key, value) -> collectConflictingRow(found,
                                                              new TopicRow(key.address(),
                                                                           key.artifact(),
                                                                           key.nodeId(),
                                                                           value.tier().requiresDurable()),
                                                              address,
                                                              self));

        return found;
    }

    /// A committed topic row as the refusal reads it: where, whose, on which node, and whether its tier conflicts.
    private record TopicRow(ResourceAddress address, Artifact artifact, NodeId node, boolean conflicting) {}

    private static void collectConflictingRow(List<String> found,
                                              TopicRow row,
                                              ResourceAddress address,
                                              Artifact self) {
        if (row.conflicting() && row.address().equals(address) && !row.artifact().base().equals(self.base())) {
            var description = row.artifact().asString() + " (on " + row.node().id() + ")";

            if (!found.contains(description)) {
                found.add(description);
            }
        }
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

    private List<LegCommand> streamRemovals(Artifact artifact, Slice slice) {
        return streamEntriesOnRemoval(artifact, slice).stream()
                                     .map(entry -> streamRemove(artifact, entry))
                                     .map(command -> LegCommand.legCommand(LegKind.STREAM_REGISTRATION, command))
                                     .toList();
    }

    private KVCommand<AetherKey> streamRemove(Artifact artifact, StreamEntry entry) {
        return new KVCommand.Remove<>(StreamRegistrationKey.streamRegistrationKey(entry.streamName(),
                                                                                  entry.configSection(),
                                                                                  artifact,
                                                                                  entry.methodName()));
    }

    /// The removing read: a consumer that cannot be resolved has nothing registered to remove and nothing for an operator to do, so raising
    /// "could NOT be registered" here would be a false CRITICAL on every teardown of the slice. Its mark is cleared instead, so that the consumer
    /// registering later, after its slice was removed in between, raises nothing (legacy `logUnresolvedOnRemoval`).
    private List<StreamEntry> streamEntriesOnRemoval(Artifact artifact, Slice slice) {
        var result = new ArrayList<StreamEntry>();

        for (var entry : reactiveBindings(artifact, slice)) {
            if ("stream".equals(entry.category())) {
                streamEntry(artifact, entry).onFailure(cause -> unresolvedOnRemoval(artifact, entry, cause))
                           .option()
                           .onPresent(result::add);
            }
        }

        return result;
    }

    private void unresolvedOnRemoval(Artifact artifact, ReactiveEntry entry, Cause cause) {
        unregisteredConsumers.remove(consumerSubject(artifact, entry));
        log.debug("Declarative stream consumer {}.{} on section [{}] was never resolved, nothing to unpublish: {}",
                  artifact,
                  entry.method(),
                  entry.config(),
                  cause.message());
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

    private List<LegCommand> scheduledRemovals(Artifact artifact, Slice slice) {
        return scheduledEntries(artifact, slice).stream()
                               .flatMap(entry -> scheduledRemove(artifact, entry))
                               .map(command -> LegCommand.legCommand(LegKind.SCHEDULED_TASK, command))
                               .toList();
    }

    /// The task key and, with it, its operator-pause key (B-145): the pause goes when the task itself goes, so a later redeploy starts unpaused.
    private static Stream<KVCommand<AetherKey>> scheduledRemove(Artifact artifact, ScheduledEntry entry) {
        var key = ScheduledTaskKey.scheduledTaskKey(entry.configSection(), artifact, entry.methodName());

        return Stream.<KVCommand<AetherKey>> of(new KVCommand.Remove<>(key),
                                                new KVCommand.Remove<>(ScheduledTaskPauseKey.scheduledTaskPauseKey(key)));
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
                    .onPresent(address -> registry.acquireCommands(blueprintTemplate(address),
                                                                   sources.self(),
                                                                   artifact,
                                                                   SLICE_HOLDER_ATTEMPT,
                                                                   sources.clock().getAsLong())
                                                  .forEach(command -> commands.add(LegCommand.legCommand(LegKind.STREAM_REF,
                                                                                                         command))));
        }

        return List.copyOf(commands);
    }

    private List<LegCommand> referenceReleases(Artifact artifact, Slice slice) {
        var declarations = streamRoleDeclarations(artifact, slice);

        return declarations.isEmpty()
               ? List.of()
               : releaseCommands(artifact, declarations);
    }

    /// The mirror of [#acquireCommands], as legacy: one release of this node's own holder key per declared role whose address the blueprint's
    /// bindings resolve, witnessed by the value the key holds in the applied mirror (`KvBackedStreamRegistry.releaseCommand`), so a release
    /// that lands after this node's next activation acquired again is refused. It reads no cluster, and is built whether or not the acquire
    /// committed: a release of a key that is not held is a no-op.
    private List<LegCommand> releaseCommands(Artifact artifact, List<StreamRole> declarations) {
        var bindings = owningBlueprintId(artifact).flatMap(this::bindingsFor);

        if (bindings.isEmpty()) {
            return List.of();
        }

        var commands = new ArrayList<LegCommand>();

        for (var declaration : declarations) {
            bindings.flatMap(found -> found.addressFor(declaration.alias()))
                    .map(address -> KvBackedStreamRegistry.releaseCommand(sources.kvStore(),
                                                                          address,
                                                                          sources.self(),
                                                                          artifact,
                                                                          SLICE_HOLDER_ATTEMPT))
                    .onPresent(command -> commands.add(LegCommand.legCommand(LegKind.STREAM_REF, command)));
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

    /// The manifest at `path`, absent when the class loader has none. The stream is read and closed through `Result.lift`, the adapter boundary
    /// for the JDK's checked `IOException`.
    private static Result<Option<Properties>> readManifest(ClassLoader classLoader, String path) {
        return Result.lift(Causes::fromThrowable,
                           () -> classLoader.getResourceAsStream(path))
                     .flatMap(stream -> Option.option(stream).fold(() -> Result.success(Option.<Properties> none()),
                                                                   SliceLegBuilders::parsed));
    }

    private static Result<Option<Properties>> parsed(InputStream stream) {
        var props = new Properties();
        var read = Result.lift(Causes::fromThrowable, () -> props.load(stream)).map(_ -> Option.some(props));

        closed(stream);

        return read;
    }

    /// FER: a stream that will not close after being read is logged and left to the class loader; the manifest was read.
    private static void closed(InputStream stream) {
        Result.lift(Causes::fromThrowable, stream::close).onFailure(cause -> log.debug("Could not close a manifest stream: {}",
                                                                                       cause.message()));
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
