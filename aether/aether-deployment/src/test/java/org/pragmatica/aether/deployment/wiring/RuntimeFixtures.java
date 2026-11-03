// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.http.HttpRoutePublisher;
import org.pragmatica.aether.invoke.InvocationHandler;
import org.pragmatica.aether.invoke.InvocationMessage;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceBridge;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;

/// The slice runtime's collaborators as test doubles: a store of loaded slices, an invocation handler and a route publisher that record what
/// they are asked, and slices that carry manifests by implementing a named marker interface (`META-INF/slice/<Marker>.manifest`).
final class RuntimeFixtures {
    private RuntimeFixtures() {}

    interface WiringTopicSlice extends Slice {}

    interface WiringStreamSlice extends Slice {}

    interface WiringScheduledSlice extends Slice {}

    interface WiringMixedSlice extends Slice {}

    interface WiringStreamRoleSlice extends Slice {}

    interface WiringConfigSlice extends Slice {}

    interface PlainSlice extends Slice {}

    /// A slice with two endpoints; the marker interface it implements picks the manifest.
    static class MarkedSlice implements WiringTopicSlice, WiringStreamSlice, WiringScheduledSlice, WiringMixedSlice, WiringStreamRoleSlice, WiringConfigSlice, PlainSlice {
        @Override
        public List<SliceMethod<?, ?>> methods() {
            return List.of(method("execute"), method("handle"));
        }

        private static SliceMethod<?, ?> method(String name) {
            return SliceMethod.sliceMethod(MethodName.methodName(name).unwrap(),
                                           (Unit unit) -> Promise.unitPromise(),
                                           TypeToken.typeToken(Unit.class),
                                           TypeToken.typeToken(Unit.class))
                              .unwrap();
        }
    }

    static final class TopicSlice extends MarkedSlice implements WiringTopicSlice {}

    static final class StreamSlice implements WiringStreamSlice {
        @Override
        public List<SliceMethod<?, ?>> methods() {
            return List.of();
        }
    }

    static final class ScheduledSlice implements WiringScheduledSlice {
        @Override
        public List<SliceMethod<?, ?>> methods() {
            return List.of();
        }
    }

    static final class MixedSlice implements WiringMixedSlice {
        @Override
        public List<SliceMethod<?, ?>> methods() {
            return List.of();
        }
    }

    static final class StreamRoleSlice implements WiringStreamRoleSlice {
        @Override
        public List<SliceMethod<?, ?>> methods() {
            return List.of();
        }
    }

    static final class ConfigSlice implements WiringConfigSlice {
        @Override
        public List<SliceMethod<?, ?>> methods() {
            return List.of();
        }
    }

    static final class BareSlice implements PlainSlice {
        @Override
        public List<SliceMethod<?, ?>> methods() {
            return List.of(MarkedSlice.method("execute"), MarkedSlice.method("handle"));
        }
    }

    /// A store of loaded slices whose loads and activations are scripted.
    static final class FakeSliceStore implements SliceStore {
        final List<LoadedSlice> loaded = new CopyOnWriteArrayList<>();
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final Set<Artifact> failedEntries = Collections.synchronizedSet(new java.util.HashSet<>());
        volatile Cause loadFailure;
        volatile Cause activateFailure;
        volatile Cause deactivateFailure;
        volatile Cause unloadFailure;
        /// One list shared by the doubles of a test, to read the order of calls across collaborators.
        volatile List<String> journal;
        volatile Option<ConfigurationProvider> composite = Option.none();

        FakeSliceStore withLoaded(Artifact artifact, Slice slice) {
            loaded.add(loadedSlice(artifact, slice));

            return this;
        }

        static LoadedSlice loadedSlice(Artifact artifact, Slice slice) {
            return new LoadedSlice() {
                @Override
                public Artifact artifact() {
                    return artifact;
                }

                @Override
                public Slice slice() {
                    return slice;
                }
            };
        }

        @Override
        public List<LoadedSlice> loaded() {
            return List.copyOf(loaded);
        }

        @Override
        public Promise<LoadedSlice> loadSlice(Artifact artifact) {
            calls.add("load " + artifact);

            return loadFailure != null
                   ? loadFailure.promise()
                   : Promise.success(loadedSlice(artifact, new BareSlice()));
        }

        @Override
        public Promise<LoadedSlice> activateSlice(Artifact artifact) {
            calls.add("activate " + artifact);

            return activateFailure != null
                   ? activateFailure.promise()
                   : Promise.success(loadedSlice(artifact, new BareSlice()));
        }

        @Override
        public Promise<LoadedSlice> deactivateSlice(Artifact artifact) {
            calls.add("deactivate " + artifact);
            journal("store: deactivate " + artifact);

            return deactivateFailure != null
                   ? deactivateFailure.promise()
                   : Promise.success(loadedSlice(artifact, new BareSlice()));
        }

        @Override
        public Promise<Unit> unloadSlice(Artifact artifact) {
            calls.add("unload " + artifact);
            journal("store: unload " + artifact);
            failedEntries.remove(artifact);
            loaded.removeIf(slice -> slice.artifact().equals(artifact));

            return unloadFailure != null
                   ? unloadFailure.promise()
                   : Promise.unitPromise();
        }

        private void journal(String entry) {
            Option.option(journal).onPresent(list -> list.add(entry));
        }

        @Override
        public Option<ConfigurationProvider> sliceComposite(Artifact artifact) {
            return composite;
        }
    }

    static final class RecordingInvocationHandler implements InvocationHandler {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        volatile List<String> journal;

        @Override
        public void onInvokeRequest(InvocationMessage.InvokeRequest request) {}

        @Override
        public void registerSlice(Artifact artifact, SliceBridge bridge) {
            calls.add("register " + artifact);
        }

        @Override
        public void unregisterSlice(Artifact artifact) {
            calls.add("unregister " + artifact);
            Option.option(journal).onPresent(list -> list.add("bridge: unregister " + artifact));
        }

        @Override
        public Option<SliceBridge> localSlice(Artifact artifact) {
            return Option.none();
        }

        @Override
        public Option<SliceBridge> findBridgeByClassLoader(ClassLoader classLoader) {
            return Option.none();
        }

        @Override
        public Option<InvocationMetricsCollector> metricsCollector() {
            return Option.none();
        }
    }

    static final class RecordingRoutePublisher implements HttpRoutePublisher {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        volatile List<String> journal;
        volatile boolean hasRoutes = true;
        volatile Cause publishFailure;
        volatile Cause unpublishFailure;

        @Override
        public Promise<Unit> publishRoutes(Artifact artifact, ClassLoader classLoader, SliceInvokerFacade invokerFacade) {
            return publishRoutes(artifact, classLoader, null, invokerFacade);
        }

        @Override
        public Promise<Unit> publishRoutes(Artifact artifact, ClassLoader classLoader, Object sliceInstance, SliceInvokerFacade invokerFacade) {
            calls.add("publish " + artifact);

            return publishFailure != null
                   ? publishFailure.promise()
                   : Promise.unitPromise();
        }

        @Override
        public boolean hasRoutes(ClassLoader classLoader, Object sliceInstance) {
            return hasRoutes;
        }

        @Override
        public Promise<Unit> unpublishRoutes(Artifact artifact) {
            calls.add("unpublish " + artifact);
            Option.option(journal).onPresent(list -> list.add("routes: unpublish " + artifact));

            return unpublishFailure != null
                   ? unpublishFailure.promise()
                   : Promise.unitPromise();
        }

        @Override
        public Option<org.pragmatica.aether.http.handler.HttpRequestHandler> getHandler(Artifact artifact) {
            return Option.none();
        }

        @Override
        public Option<org.pragmatica.aether.http.adapter.SliceRouter> getSliceRouter(Artifact artifact) {
            return Option.none();
        }

        @Override
        public Set<org.pragmatica.aether.slice.kvstore.AetherKey.HttpNodeRouteKey> allLocalRoutes() {
            return Set.of();
        }

        @Override
        public Option<org.pragmatica.aether.http.adapter.SliceRouter> findLocalRouter(String httpMethod, String pathPrefix) {
            return Option.none();
        }

        @Override
        public Option<LocalRouteInfo> findLocalRoute(String httpMethod, String path) {
            return Option.none();
        }

        @Override
        public Unit updateSecurityOverrides(org.pragmatica.aether.slice.blueprint.SecurityOverrides overrides) {
            return Unit.unit();
        }

        @Override
        public Unit setVersioningMetricsSink(org.pragmatica.http.routing.VersioningMetricsSink sink) {
            return Unit.unit();
        }

        @Override
        public Unit setObservabilityCellRegistrar(org.pragmatica.aether.slice.ObservabilityCellRegistrar registrar) {
            return Unit.unit();
        }

        @Override
        public java.util.Map<Artifact, org.pragmatica.http.routing.SliceVersionRegistry> versionRegistries() {
            return java.util.Map.of();
        }
    }

    /// A config facade with nothing in it, for notifications whose content the test does not read.
    static org.pragmatica.aether.slice.ConfigFacade noConfig() {
        return org.pragmatica.aether.deployment.node.NodeDeploymentManager.NO_OP_CONFIG;
    }

    static final SliceInvokerFacade FACADE = new SliceInvokerFacade() {
        @Override
        public <R, T> Result<org.pragmatica.aether.slice.MethodHandle<R, T>> methodHandle(String sliceArtifact,
                                                                                         String methodName,
                                                                                         TypeToken<T> requestType,
                                                                                         TypeToken<R> responseType) {
            return org.pragmatica.lang.utils.Causes.cause("not used").result();
        }
    };
}
