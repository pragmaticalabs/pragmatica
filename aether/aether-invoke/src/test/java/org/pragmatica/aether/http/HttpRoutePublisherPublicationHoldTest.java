// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodHandle;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.blueprint.SecurityOverridePolicy;
import org.pragmatica.aether.slice.blueprint.SecurityOverrides;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.assertj.core.api.Assertions.assertThat;

/// #3021 probe 2 seam, the route-publication HOLD: while held, the publication step returns at ONCE (slice activation is not
/// held back) and the routes are recorded as PENDING, visible neither in the local route table nor in the cluster. The hold is
/// a condition the test releases, so every assertion here is a state read after an explicit step, never after a wait.
///
/// Pinned invariants: unpublish cancels a pending publication (the ghost-route probe p1 of the #3031 verification, RED on the
/// timer seam it replaces); release publishes the pending set; a republish after the release includes what was pending; with
/// no hold routes are local when the step returns (the control).
@SuppressWarnings("JBCT-EX-01")
class HttpRoutePublisherPublicationHoldTest {
    private static final Artifact OUTER = Artifact.artifact("org.example:pricing-catalog:1.0.0").unwrap();
    private static final NodeId SELF = NodeId.nodeId("self-hold").unwrap();

    @Test
    void notHeld_routesAreLocalWhenPublicationReturns() {
        var cluster = new RecordingCluster();
        var publisher = HttpRoutePublisher.httpRoutePublisher(SELF, cluster);

        publish(publisher);
        assertThat(publisher.routePublicationHeld()).as("CONTROL: no hold").isFalse();
        assertThat(publisher.allLocalRoutes()).as("CONTROL: no hold, so the route is there at once").hasSize(1);
        assertThat(cluster.puts()).as("CONTROL: and in the cluster").hasSize(1);
    }

    @Test
    @Timeout(30)
    void held_publicationReturnsAtOnce_routeIsNeitherLocalNorInTheCluster_untilReleased() {
        var cluster = new RecordingCluster();
        var publisher = HttpRoutePublisher.httpRoutePublisher(SELF, cluster);

        publisher.holdRoutePublication();
        publish(publisher);

        assertThat(publisher.routePublicationHeld()).isTrue();
        assertThat(publisher.allLocalRoutes()).as("held: not in the local route table").isEmpty();
        assertThat(cluster.commands()).as("held: nothing written to the cluster").isEmpty();

        release(publisher);

        assertThat(publisher.routePublicationHeld()).isFalse();
        assertThat(publisher.allLocalRoutes()).as("released: the pending route is local").hasSize(1);
        assertThat(cluster.puts()).as("released: and in the cluster, once").hasSize(1);
    }

    /// The ghost-route probe p1 (#3031 verification, v3031-report A1): a slice undeployed while its publication is pending
    /// must leave nothing behind after the release. RED on the timer seam: the delayed put ran after the unpublish.
    @Test
    @Timeout(30)
    void p1_unpublishWhilePending_cancelsIt_andReleaseLeavesNoGhostRoute() {
        var cluster = new RecordingCluster();
        var publisher = HttpRoutePublisher.httpRoutePublisher(SELF, cluster);

        publisher.holdRoutePublication();
        publish(publisher);
        publisher.unpublishRoutes(OUTER).await(timeSpan(30).seconds());
        release(publisher);

        assertThat(publisher.allLocalRoutes()).as("P1: unpublished while pending, released: no ghost route").isEmpty();
        assertThat(cluster.commands()).as("P1: the cluster never heard of the cancelled publication").isEmpty();
    }

    @Test
    @Timeout(30)
    void republishWhilePending_keepsOneRouteSet() {
        var cluster = new RecordingCluster();
        var publisher = HttpRoutePublisher.httpRoutePublisher(SELF, cluster);

        publisher.holdRoutePublication();
        publish(publisher);
        publish(publisher);
        release(publisher);

        assertThat(publisher.allLocalRoutes()).hasSize(1);
        assertThat(cluster.puts()).as("one pending entry per artifact, so one write").hasSize(1);
    }

    @Test
    @Timeout(30)
    void publicationAfterTheRelease_isPublishedAtOnce() {
        var cluster = new RecordingCluster();
        var publisher = HttpRoutePublisher.httpRoutePublisher(SELF, cluster);

        publisher.holdRoutePublication();
        release(publisher);
        publish(publisher);

        assertThat(publisher.allLocalRoutes()).as("the hold ended: no new publication is parked").hasSize(1);
        assertThat(cluster.puts()).hasSize(1);
    }

    @Test
    @Timeout(30)
    void releaseWithoutPending_completesAndWritesNothing() {
        var cluster = new RecordingCluster();
        var publisher = HttpRoutePublisher.httpRoutePublisher(SELF, cluster);

        publisher.holdRoutePublication();
        release(publisher);

        assertThat(cluster.commands()).isEmpty();
    }

    /// Reconnect republish (override update) while a publication is pending leaves it pending and writes nothing; the entry
    /// written at the release carries the overrides active THEN, and a later override update republishes it like any other.
    @Test
    @Timeout(30)
    void republish_whilePending_writesNothing_thePendingEntryIsWrittenWithTheCurrentOverrides_andLaterRepublishesIncludeIt() {
        var cluster = new RecordingCluster();
        var publisher = HttpRoutePublisher.httpRoutePublisher(SELF, cluster);

        publisher.holdRoutePublication();
        publish(publisher);
        publisher.updateSecurityOverrides(lockdownToAdmin());

        assertThat(cluster.commands()).as("a republish while held must not publish the pending entry").isEmpty();

        release(publisher);

        assertThat(cluster.puts()).hasSize(1);
        assertThat(securityOf(cluster.puts().getFirst())).as("the released entry carries the overrides active at the release")
                  .contains("admin");

        publisher.updateSecurityOverrides(SecurityOverrides.EMPTY);

        assertThat(cluster.puts()).as("a republish after the release includes the formerly pending route").hasSize(2);
        assertThat(securityOf(cluster.puts().getLast())).doesNotContain("admin");
    }

    /// v3031h survivor h6: an unpublish of a PUBLISHED (not pending) artifact forgets it, so it leaves the local route table, the
    /// cluster is told, and a later republish does not bring it back.
    @Test
    @Timeout(30)
    void unpublishAfterThePublication_forgetsTheRoute_tellsTheCluster_andALaterRepublishDoesNotResurrectIt() {
        var cluster = new RecordingCluster();
        var publisher = HttpRoutePublisher.httpRoutePublisher(SELF, cluster);

        publish(publisher);
        publisher.unpublishRoutes(OUTER).await(timeSpan(30).seconds());

        assertThat(publisher.allLocalRoutes()).as("unpublished: no longer a local route").isEmpty();
        assertThat(cluster.commands()).as("one put, then the remove").hasSize(2);
        assertThat(cluster.commands().getLast()).isInstanceOf(KVCommand.Remove.class);

        publisher.updateSecurityOverrides(lockdownToAdmin());

        assertThat(cluster.puts()).as("a republish after the unpublish writes nothing for it").hasSize(1);
    }

    /// v3031h survivor h10: the release EMPTIES the pending set, so a second hold and release publishes nothing again.
    @Test
    @Timeout(30)
    void aSecondHoldAndRelease_doesNotPublishWhatTheFirstReleaseAlreadyPublished() {
        var cluster = new RecordingCluster();
        var publisher = HttpRoutePublisher.httpRoutePublisher(SELF, cluster);

        publisher.holdRoutePublication();
        publish(publisher);
        release(publisher);
        publisher.holdRoutePublication();
        release(publisher);

        assertThat(cluster.puts()).as("the entry released first is not pending any more, so it is written once").hasSize(1);
    }

    /// v3031h survivors h14, h15: the interface defaults are for publishers that publish no routes (test doubles): never held,
    /// the hold is a no-op and the release completes at once. A proxy runs the real defaults without implementing the rest.
    @Test
    @Timeout(30)
    void aPublisherThatPublishesNoRoutes_isNeverHeld_andItsReleaseCompletesAtOnce() {
        var publisher = defaultsOnly();

        assertThat(publisher.routePublicationHeld()).as("the default is not held").isFalse();
        assertThat(publisher.holdRoutePublication()).isEqualTo(Unit.unit());
        assertThat(publisher.routePublicationHeld()).as("the default hold changes nothing").isFalse();

        var released = publisher.releaseRoutePublication();

        assertThat(released.isResolved()).as("the default release is complete when it returns").isTrue();
        assertThat(released.await(timeSpan(5).seconds()).isSuccess()).isTrue();
    }

    private static HttpRoutePublisher defaultsOnly() {
        return (HttpRoutePublisher) Proxy.newProxyInstance(HttpRoutePublisher.class.getClassLoader(),
                                                           new Class<?>[]{HttpRoutePublisher.class},
                                                           HttpRoutePublisherPublicationHoldTest::defaultOrRefuse);
    }

    private static Object defaultOrRefuse(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, args);
        }

        return Assertions.fail("only the interface defaults are exercised, not " + method.getName());
    }

    private static SecurityOverrides lockdownToAdmin() {
        return SecurityOverrides.securityOverrides(List.of(SecurityOverrides.Entry.entry("GET "
                                                                                          + NestedPrefixRouteFactories.OUTER_PREFIX,
                                                                                          "role:admin")),
                                                   SecurityOverridePolicy.STRENGTHEN_ONLY);
    }

    private static String securityOf(KVCommand.Put<AetherKey, ?> put) {
        return ((NodeRoutesValue) put.value()).routes()
                                              .getFirst()
                                              .security();
    }

    private static void release(HttpRoutePublisher publisher) {
        publisher.releaseRoutePublication()
                 .await(timeSpan(30).seconds())
                 .onFailure(cause -> Assertions.fail("release must succeed: " + cause.message()));
    }

    private void publish(HttpRoutePublisher publisher) {
        publisher.publishRoutes(OUTER,
                                getClass().getClassLoader(),
                                new NestedPrefixRouteFactories.OuterSlice(),
                                stubInvokerFacade())
                 .await(timeSpan(30).seconds())
                 .onFailure(cause -> Assertions.fail("route publication must succeed: " + cause.message()));
    }

    private static SliceInvokerFacade stubInvokerFacade() {
        return new SliceInvokerFacade() {
            @Override
            public <R, T> Result<MethodHandle<R, T>> methodHandle(String sliceArtifact,
                                                                  String methodName,
                                                                  TypeToken<T> requestType,
                                                                  TypeToken<R> responseType) {
                return Causes.cause("stub invoker facade").result();
            }
        };
    }

    private static final class RecordingCluster implements ClusterNode<KVCommand<AetherKey>> {
        private final List<KVCommand<AetherKey>> applied = new CopyOnWriteArrayList<>();

        List<KVCommand<AetherKey>> commands() {
            return List.copyOf(applied);
        }

        @SuppressWarnings("unchecked")
        List<KVCommand.Put<AetherKey, ?>> puts() {
            var puts = new ArrayList<KVCommand.Put<AetherKey, ?>>();

            applied.stream()
                   .filter(KVCommand.Put.class::isInstance)
                   .forEach(command -> puts.add((KVCommand.Put<AetherKey, ?>) command));
            return puts;
        }

        @Override
        public NodeId self() {
            return SELF;
        }

        @Override
        public TopologyManager topologyManager() {
            return Assertions.fail("topologyManager() is not exercised by route publication");
        }

        @Override
        public Promise<Unit> start() {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            applied.addAll(commands);

            return (Promise<List<R>>)(Promise<?>) Promise.success(List.of());
        }
    }
}
