// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.projection;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.invoke.PublisherFactory;
import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.resource.TopicConfig;
import org.pragmatica.aether.resource.TopicDurability;
import org.pragmatica.aether.resource.projection.Projection;
import org.pragmatica.aether.resource.projection.ProjectionRuntime;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.stream.topic.DurableTopicNames;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// [ResourceFactory] for [ProjectionRuntime] (#1333), discovered through the resource SPI like
/// `DurableEntityFactory`. Bound to the projection's TOPIC section (`configType` is [TopicConfig]), so
/// the runtime resolves the backing stream through the SAME rule the topic's publisher uses
/// ([PublisherFactory#resolveTopicAddress]) and the registry keys on exactly the stream the group consumes.
///
/// Refuses: a context without the node's [ProjectionNodeSupport] (a bare context cannot rewind anything —
/// refusing beats handing out a runtime whose `attach` would silently keep the refusing default cursor);
/// an EPHEMERAL topic (no backing stream, nothing to capture or rewind); a context without the slice id
/// (the registry keys on the slice base).
///
/// Close: unregisters every projection the slice attached on this topic, so a redeployed slice registers
/// afresh and a stopped one stops receiving cursor reports.
public final class ProjectionRuntimeFactory implements ResourceFactory<ProjectionRuntime, TopicConfig> {
    private static final Logger LOG = LoggerFactory.getLogger(ProjectionRuntimeFactory.class);

    public sealed interface ProvisioningError extends Cause {
        record SupportUnavailable(String topicName, String missing) implements ProvisioningError {
            @Override
            public String message() {
                return "ProjectionRuntime for topic '" + topicName
                     + "' cannot be provisioned: the provisioning context carries no " + missing
                     + " — the node registers it; a bare context has nothing to rewind a cursor with";
            }
        }

        record EphemeralTopic(String topicName) implements ProvisioningError {
            @Override
            public String message() {
                return "ProjectionRuntime for topic '" + topicName
                     + "' refused: the topic is ephemeral, so it has no backing"
                     + " stream and no group cursor to rebuild from; declare it durability = \"durable\"";
            }
        }

        record TopicMismatch(String runtimeTopic, String projectionTopic) implements ProvisioningError {
            @Override
            public String message() {
                return "Projection on topic '" + projectionTopic
                     + "' attached through the ProjectionRuntime of topic '" + runtimeTopic
                     + "' — a runtime is bound to one topic section; declare a ProjectionRuntime for '" + projectionTopic
                     + "'";
            }
        }
    }

    @Override
    public Class<ProjectionRuntime> resourceType() {
        return ProjectionRuntime.class;
    }

    @Override
    public Class<TopicConfig> configType() {
        return TopicConfig.class;
    }

    @Override
    public Promise<ProjectionRuntime> provision(TopicConfig config) {
        return new ProvisioningError.SupportUnavailable(config.topicName(), "ProvisioningContext").promise();
    }

    @Override
    public Promise<ProjectionRuntime> provision(TopicConfig config, ProvisioningContext context) {
        return Result.all(support(config, context),
                          sliceOf(config, context),
                          durable(config))
                     .map((support, slice, _) -> runtime(config, context, support, slice))
                     .async();
    }

    private static Result<ProjectionNodeSupport> support(TopicConfig config, ProvisioningContext context) {
        return context.extension(ProjectionNodeSupport.class)
                      .mapError(_ -> new ProvisioningError.SupportUnavailable(config.topicName(),
                                                                              "ProjectionNodeSupport"));
    }

    private static Result<ArtifactBase> sliceOf(TopicConfig config, ProvisioningContext context) {
        return context.extension(String.class)
                      .flatMap(Artifact::artifact)
                      .map(Artifact::base)
                      .mapError(_ -> new ProvisioningError.SupportUnavailable(config.topicName(),
                                                                              "slice id"));
    }

    private static Result<Unit> durable(TopicConfig config) {
        return config.durability() == TopicDurability.DURABLE
               ? Result.unitResult()
               : new ProvisioningError.EphemeralTopic(config.topicName()).result();
    }

    private static ProjectionRuntime runtime(TopicConfig config,
                                             ProvisioningContext context,
                                             ProjectionNodeSupport support,
                                             ArtifactBase slice) {
        var address = PublisherFactory.resolveTopicAddress(config, context);
        var topicStream = DurableTopicNames.topicStream(address);
        var bareName = ResourceAddress.resourceAddress(address)
                                      .map(resolved -> resolved.name()
                                                               .value())
                                      .or(config.topicName());

        LOG.info("ProjectionRuntime provisioned for slice {} on {}", slice.asString(), topicStream);

        return new NodeProjectionRuntime(support, slice, topicStream, bareName);
    }

    @Override
    public Promise<Unit> close(ProjectionRuntime resource) {
        if (resource instanceof NodeProjectionRuntime runtime) {
            runtime.support().registry().unregister(runtime.slice(), runtime.topicStream());
            LOG.info("ProjectionRuntime released for slice {} on {}",
                     runtime.slice().asString(),
                     runtime.topicStream());
        }

        return Promise.unitPromise();
    }

    /// One slice's runtime for one topic. `attach` refuses a projection declared on a DIFFERENT topic:
    /// the group it would report for consumes another stream, so its reports would never arrive and
    /// its rewind would move the wrong cursor.
    record NodeProjectionRuntime(ProjectionNodeSupport support,
                                 ArtifactBase slice,
                                 String topicStream,
                                 String topicName) implements ProjectionRuntime {
        @Override
        public <S, T> Result<Projection<S, T>> attach(Projection<S, T> projection) {
            return attach(projection, Option.none());
        }

        @Override
        public <S, T> Result<Projection<S, T>> attach(Projection<S, T> projection, String subscriberMethod) {
            return attach(projection, Option.some(subscriberMethod));
        }

        private <S, T> Result<Projection<S, T>> attach(Projection<S, T> projection, Option<String> method) {
            var declared = projection.topic().name();

            return declared.equals(topicName)
                   ? support.attach(slice, topicStream, method, projection)
                   : new ProvisioningError.TopicMismatch(topicName, declared).result();
        }
    }
}
