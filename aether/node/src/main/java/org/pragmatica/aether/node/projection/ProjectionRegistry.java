// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.projection;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.endpoint.TopicSubscriptionRegistry.TopicSubscription;
import org.pragmatica.aether.stream.topic.DurableGroupIdentity;
import org.pragmatica.aether.stream.topic.DurableTopicNames;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Which projections this node hosts, keyed the way the consumer runtime keys its groups (#1333).
///
/// A projection registers as `(slice base, topic stream, method)` where `method` is the slice's durable
/// subscriber that delegates to the projection — named by the slice, or INFERRED. The runtime's group for
/// that subscriber is `sliceBase#method` ([DurableGroupIdentity]); the projection's own name is never a
/// key here, because it is not the runtime's group.
///
/// **Inference, and why it refuses.** An inferred registration resolves to the slice's durable
/// subscriber on that topic iff there is EXACTLY ONE (read from the cluster's topic subscriptions, so
/// resolution is lazy and provisioning order against the subscription write does not matter). With two,
/// the registry cannot tell which group's cursor is the projection's; attributing the other group's
/// cursor would make the projection skip replay offsets it never applied — the #1304 X6 loss shape — so
/// [#lookup] answers nothing and logs one ERROR per `(stream, group)`, and [#groupIdOf] refuses the
/// rewind. Naming the method ([org.pragmatica.aether.resource.projection.ProjectionRuntime#attach(
/// org.pragmatica.aether.resource.projection.Projection, String)]) removes the ambiguity.
///
/// **Lifecycle.** Registered when the slice's `ProjectionRuntime` resource attaches the projection,
/// removed when that resource is closed on slice stop (`ResourceFactory.close`), so a redeploy
/// re-registers under the version-stable group. Entries are per node: the assignee of a partition hosts
/// the slice by construction, so its registration is the one found at commit time.
public interface ProjectionRegistry {
    record Registration(ArtifactBase slice, String topicStream, Option<String> method, ProjectionHandle handle) {
        RegistrationKey key() {
            return new RegistrationKey(slice, topicStream, method);
        }
    }

    record RegistrationKey(ArtifactBase slice, String topicStream, Option<String> method) {}

    sealed interface RegistryError extends Cause {
        record AlreadyAttached(ArtifactBase slice, String topicStream, Option<String> method) implements RegistryError {
            @Override
            public String message() {
                return "Projection for slice " + slice.asString()
                     + " on stream " + topicStream + method.map(m -> " (subscriber " + m + ")")
                                                           .or("")
                     + " is already attached — a slice attaches one projection per durable subscriber; name the"
                     + " subscriber method to attach a second projection on the same topic";
            }
        }

        record AmbiguousSubscriber(ArtifactBase slice, String topicStream, List<String> methods) implements RegistryError {
            @Override
            public String message() {
                return "Projection for slice " + slice.asString()
                     + " on stream " + topicStream
                     + " cannot infer its consumer group: the slice has " + methods.size()
                     + " durable subscribers on that topic " + methods
                     + " — attach with the subscriber method named, otherwise another group's cursor could be"
                     + " attributed to the projection and skip replay offsets it never applied";
            }
        }

        record NoSubscriberVisible(ArtifactBase slice, String topicStream) implements RegistryError {
            @Override
            public String message() {
                return "Projection for slice " + slice.asString()
                     + " on stream " + topicStream
                     + " has no durable subscriber visible on this node yet — the topic subscription is written by"
                     + " the deployment; retry once the slice is ACTIVE";
            }
        }
    }

    /// Refused when the same `(slice, stream, method)` is already registered.
    Result<Unit> register(Registration registration);

    @Contract
    void unregister(ArtifactBase slice, String topicStream);

    /// The projection whose consumer group is `groupId` on `topicStream`, if this node hosts one and the
    /// attribution is unambiguous. Silent for a group that is no projection's; ERROR once when ambiguous.
    Option<ProjectionHandle> lookup(String topicStream, String groupId);
    /// The runtime group a registration reports for — resolved now, refused when it cannot be.
    Result<String> groupIdOf(ArtifactBase slice, String topicStream, Option<String> method);

    default Result<String> groupIdOf(Registration registration) {
        return groupIdOf(registration.slice(), registration.topicStream(), registration.method());
    }

    List<Registration> registrations(String topicStream);

    static ProjectionRegistry projectionRegistry(Supplier<List<TopicSubscription>> subscriptions) {
        return new RegistryState(subscriptions);
    }

    final class RegistryState implements ProjectionRegistry {
        private static final Logger log = LoggerFactory.getLogger(ProjectionRegistry.class);

        private final Supplier<List<TopicSubscription>> subscriptions;
        private final Map<RegistrationKey, Registration> registrations = new ConcurrentHashMap<>();
        private final Set<String> refusedGroups = ConcurrentHashMap.newKeySet();

        RegistryState(Supplier<List<TopicSubscription>> subscriptions) {
            this.subscriptions = subscriptions;
        }

        @Override
        public Result<Unit> register(Registration registration) {
            return registrations.putIfAbsent(registration.key(), registration) == null
                   ? Result.unitResult()
                   : new RegistryError.AlreadyAttached(registration.slice(),
                                                       registration.topicStream(),
                                                       registration.method()).result();
        }

        @Contract
        @Override
        public void unregister(ArtifactBase slice, String topicStream) {
            registrations.keySet().removeIf(key -> key.slice()
                                                      .equals(slice) && key.topicStream()
                                                                           .equals(topicStream));
        }

        @Override
        public Option<ProjectionHandle> lookup(String topicStream, String groupId) {
            return DurableGroupIdentity.parse(groupId).flatMap(identity -> lookup(topicStream, groupId, identity));
        }

        private Option<ProjectionHandle> lookup(String topicStream,
                                                String groupId,
                                                DurableGroupIdentity.GroupIdentity identity) {
            var explicit = Option.option(registrations.get(new RegistrationKey(identity.subscriber(),
                                                                               topicStream,
                                                                               Option.some(identity.method()))));

            return explicit.map(Registration::handle)
                           .orElse(() -> inferred(topicStream, groupId, identity));
        }

        private Option<ProjectionHandle> inferred(String topicStream,
                                                  String groupId,
                                                  DurableGroupIdentity.GroupIdentity identity) {
            return Option.option(registrations.get(new RegistrationKey(identity.subscriber(), topicStream, Option.none()))).flatMap(registration -> resolveInferred(registration,
                                                                                                                                                                    groupId).map(Registration::handle));
        }

        /// The inferred registration is the group's projection iff the slice's ONE durable subscriber on
        /// the topic is this group's method. A refusal is logged once per group, not once per commit.
        private Option<Registration> resolveInferred(Registration registration, String groupId) {
            return groupIdOf(registration).fold(cause -> refuse(groupId, cause),
                                                resolved -> resolved.equals(groupId)
                                                            ? Option.some(registration)
                                                            : Option.none());
        }

        private Option<Registration> refuse(String groupId, Cause cause) {
            if (cause instanceof RegistryError.AmbiguousSubscriber && refusedGroups.add(groupId)) {
                log.error("Projection cursor report for group {} refused: {}", groupId, cause.message());
            }

            return Option.none();
        }

        @Override
        public Result<String> groupIdOf(ArtifactBase slice, String topicStream, Option<String> method) {
            return method.map(named -> Result.success(DurableGroupIdentity.groupId(slice, named)))
                         .or(() -> inferGroupId(slice, topicStream));
        }

        private Result<String> inferGroupId(ArtifactBase slice, String topicStream) {
            var methods = subscriberMethods(slice, topicStream);

            return switch (methods.size()) {
                case 0 -> new RegistryError.NoSubscriberVisible(slice, topicStream).result();
                case 1 -> Result.success(DurableGroupIdentity.groupId(slice, methods.getFirst()));
                default -> new RegistryError.AmbiguousSubscriber(slice, topicStream, methods).result();
            };
        }

        private List<String> subscriberMethods(ArtifactBase slice, String topicStream) {
            return subscriptions.get()
                                .stream()
                                .filter(subscription -> subscription.artifact()
                                                                    .base()
                                                                    .equals(slice))
                                .filter(subscription -> DurableTopicNames.topicStream(subscription.routingKey()).equals(topicStream))
                                .map(subscription -> subscription.methodName()
                                                                 .name())
                                .distinct()
                                .sorted()
                                .toList();
        }

        @Override
        public List<Registration> registrations(String topicStream) {
            return registrations.values()
                                .stream()
                                .filter(registration -> registration.topicStream()
                                                                    .equals(topicStream))
                                .toList();
        }
    }
}
