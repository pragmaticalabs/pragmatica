package org.pragmatica.aether.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.aether.slice.MethodHandle;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #755: siblings that differ ONLY by spacer position, with different policies, each keep their own on the hosting node.
class SpacerPositionSiblingPolicyTest {
    private static final Artifact MIXED = Artifact.artifact("org.example:mixed:1.0.0").unwrap();
    private static final Artifact ID_THEN_EDIT = Artifact.artifact("org.example:aaa-id-then-edit:1.0.0").unwrap();
    private static final Artifact EDIT_THEN_ID = Artifact.artifact("org.example:zzz-edit-then-id:1.0.0").unwrap();

    /// On the hosting node each position keeps ITS OWN policy. Without `sameShape` comparing slots, the two
    /// siblings are one "shape" to strictestOfItsShape and the public one inherits `admin`.
    @Test
    void host_siblingsSplitOnlyBySpacerPosition_eachKeepsItsOwnPolicy() {
        var publisher = HttpRoutePublisher.httpRoutePublisher(node("host"), new RecordingCluster(node("host")));

        publish(publisher, MIXED, new SpacerPositionPolicySliceRoutes.MixedSlice());

        assertThat(servedBody(publisher, "/users/42/edit")).as("CONTROL").contains("id-then-edit-42");
        assertThat(servedBody(publisher, "/users/edit/42")).as("CONTROL").contains("edit-then-id-42");
        assertThat(policy(publisher, "/users/edit/42").asString()).as("CONTROL: the admin sibling")
                                                                  .isEqualTo(SecurityPolicy.roleRequired("admin").asString());
        assertThat(policy(publisher, "/users/42/edit").asString()).as("the public sibling keeps PUBLIC")
                                                                  .isEqualTo(SecurityPolicy.publicRoute().asString());
    }

    private static NodeId node(String id) {
        return NodeId.nodeId(id).unwrap();
    }

    private static void publish(HttpRoutePublisher publisher, Artifact artifact, Object slice) {
        publisher.publishRoutes(artifact, SpacerPositionSiblingPolicyTest.class.getClassLoader(), slice, stubInvokerFacade())
                 .await(timeSpan(30).seconds())
                 .onFailure(cause -> Assertions.fail("route publication must succeed: " + cause.message()));
    }

    private static String servedBody(HttpRoutePublisher publisher, String path) {
        return new String(publisher.findServingRouter("GET", path)
                                   .unwrap()
                                   .handle(HttpRequestContext.httpRequestContext(path, "GET", Map.of(), Map.of(), "req"))
                                   .await(timeSpan(10).seconds())
                                   .unwrap()
                                   .body());
    }

    private static SecurityPolicy policy(HttpRoutePublisher publisher, String path) {
        return publisher.findLocalRoute("GET", path)
                        .map(HttpRoutePublisher.LocalRouteInfo::security)
                        .toResult(Causes.cause("no local route for " + path))
                        .unwrap();
    }

    private static SliceInvokerFacade stubInvokerFacade() {
        return new SliceInvokerFacade() {
            @Override
            public <R, T> Result<MethodHandle<R, T>> methodHandle(String a, String m, TypeToken<T> q, TypeToken<R> r) {
                return Causes.cause("stub").result();
            }
        };
    }

    private static final class RecordingCluster implements ClusterNode<KVCommand<AetherKey>> {
        private final NodeId self;
        private final List<ValuePut<NodeRoutesKey, NodeRoutesValue>> puts = new ArrayList<>();

        RecordingCluster(NodeId self) {
            this.self = self;
        }

        @Override
        public NodeId self() {
            return self;
        }

        @Override
        public TopologyManager topologyManager() {
            return Assertions.fail("unused");
        }

        @Override
        public Promise<Unit> start() {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            for (var command : commands) {
                if (command instanceof KVCommand.Put<AetherKey, ?> put
                    && put.key() instanceof NodeRoutesKey key
                    && put.value() instanceof NodeRoutesValue value) {
                    puts.add(new ValuePut<>(new KVCommand.Put<>(key, value), Option.none()));
                }
            }

            return (Promise<List<R>>) (Promise<?>) Promise.success(List.of());
        }
    }
}
