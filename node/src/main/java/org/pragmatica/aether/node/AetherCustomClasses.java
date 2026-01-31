package org.pragmatica.aether.node;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.ArtifactId;
import org.pragmatica.aether.artifact.GroupId;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.invoke.InvocationMessage;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.cluster.metrics.MetricsMessage;
import org.pragmatica.cluster.node.rabia.CustomClasses;
import org.pragmatica.lang.Option;

import java.util.function.Consumer;

import static org.pragmatica.utility.HierarchyScanner.concreteSubtypes;

/**
 * Registers Aether-specific classes for serialization.
 */
public interface AetherCustomClasses {
    static void configure(Consumer<Class<?>> consumer) {
        // Include base Rabia classes
        CustomClasses.configure(consumer);
        // Option types (needed for SliceTargetValue.owningBlueprint)
        concreteSubtypes(Option.class).forEach(consumer);
        // Aether key/value types
        concreteSubtypes(AetherKey.class).forEach(consumer);
        concreteSubtypes(AetherValue.class).forEach(consumer);
        concreteSubtypes(AetherKey.AetherKeyPattern.class).forEach(consumer);
        // Artifact types
        consumer.accept(Artifact.class);
        consumer.accept(ArtifactBase.class);
        consumer.accept(GroupId.class);
        consumer.accept(ArtifactId.class);
        consumer.accept(Version.class);
        // Slice types
        consumer.accept(SliceState.class);
        consumer.accept(MethodName.class);
        // Blueprint types
        consumer.accept(BlueprintId.class);
        consumer.accept(ExpandedBlueprint.class);
        consumer.accept(ResolvedSlice.class);
        // Metrics types
        concreteSubtypes(MetricsMessage.class).forEach(consumer);
        // Invocation types
        concreteSubtypes(InvocationMessage.class).forEach(consumer);
        // Leader election types (for consensus-based leader election)
        consumer.accept(LeaderKey.class);
        consumer.accept(LeaderKey.LeaderKeyPattern.class);
        consumer.accept(LeaderValue.class);
    }
}
