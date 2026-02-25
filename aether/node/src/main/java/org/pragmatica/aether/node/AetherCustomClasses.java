package org.pragmatica.aether.node;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.ArtifactId;
import org.pragmatica.aether.artifact.GroupId;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.aether.http.handler.security.Principal;
import org.pragmatica.aether.http.handler.security.Role;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.http.forward.HttpForwardMessage;
import org.pragmatica.aether.invoke.InvocationMessage;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.dht.DHTMessage;
import org.pragmatica.dht.Partition;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.cluster.metrics.DeploymentMetricsMessage;
import org.pragmatica.cluster.metrics.MetricsMessage;
import org.pragmatica.cluster.node.rabia.CustomClasses;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.ClassRegistrator;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.pragmatica.utility.HierarchyScanner.concreteSubtypes;

/// Registers Aether-specific classes for serialization.
public interface AetherCustomClasses extends ClassRegistrator {
    AetherCustomClasses INSTANCE = new AetherCustomClasses() {};

    @Override
    default List<Class<?>> classesToRegister() {
        var classes = new ArrayList<Class<?>>();
        // Include base Rabia classes
        classes.addAll(CustomClasses.INSTANCE.classesToRegister());
        // Option types (needed for SliceTargetValue.owningBlueprint)
        classes.addAll(concreteSubtypes(Option.class));
        // Aether key/value types
        classes.addAll(concreteSubtypes(AetherKey.class));
        classes.addAll(concreteSubtypes(AetherValue.class));
        // Artifact types
        Stream.of(Artifact.class,
                  ArtifactBase.class,
                  GroupId.class,
                  ArtifactId.class,
                  Version.class,
                  // Slice types
        SliceState.class,
                  AetherValue.NodeLifecycleState.class,
                  MethodName.class,
                  // Blueprint types
        BlueprintId.class,
                  ExpandedBlueprint.class,
                  ResolvedSlice.class)
              .forEach(classes::add);
        // Metrics types
        classes.addAll(concreteSubtypes(MetricsMessage.class));
        classes.addAll(concreteSubtypes(DeploymentMetricsMessage.class));
        classes.add(DeploymentMetricsMessage.DeploymentMetricsEntry.class);
        // Invocation types
        classes.addAll(concreteSubtypes(InvocationMessage.class));
        // HTTP forwarding types
        classes.addAll(concreteSubtypes(HttpForwardMessage.class));
        // Leader election types (for consensus-based leader election)
        Stream.of(LeaderKey.class,
                  LeaderValue.class,
                  // HTTP handler types (for remote slice invocation)
        HttpRequestContext.class,
                  HttpResponseData.class,
                  SecurityContext.class,
                  Principal.class,
                  Role.class)
              .forEach(classes::add);
        // DHT message types
        classes.addAll(concreteSubtypes(DHTMessage.class));
        classes.add(DHTMessage.KeyValue.class);
        classes.add(Partition.class);
        return List.copyOf(classes);
    }
}
