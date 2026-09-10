// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.AsyncCloseable;

/// VERIFICATION-ONLY probe (v892 adversarial pass). Test-only `ResourceFactory`, discovered by
/// `SpiResourceProvider`'s `ServiceLoader` scan through `src/test/resources/META-INF/services`,
/// handing out a resource that implements [AsyncCloseable] and RECORDS its own close.
///
/// It exists so a test can observe whether a release actually reached the provider, rather than
/// whether the release promise succeeded -- the no-op default succeeds either way, which is the
/// whole of why #892 was invisible.
public final class ReleaseProbeFactory implements ResourceFactory<ReleaseProbeFactory.ProbeResource,
                                                                      ReleaseProbeFactory.ProbeConfig> {
    public static final String SECTION = "release_probe";

    private static final List<ProbeResource> PROVISIONED = new CopyOnWriteArrayList<>();

    public record ProbeConfig(boolean enabled) {}

    public static final class ProbeResource implements AsyncCloseable {
        private final AtomicInteger closes = new AtomicInteger();

        public boolean isClosed() {
            return closes.get() > 0;
        }

        @Override
        public Promise<Unit> close() {
            closes.incrementAndGet();

            return Promise.unitPromise();
        }
    }

    public static List<ProbeResource> provisioned() {
        return List.copyOf(PROVISIONED);
    }

    public static void reset() {
        PROVISIONED.clear();
    }

    @Override
    public Class<ProbeResource> resourceType() {
        return ProbeResource.class;
    }

    @Override
    public Class<ProbeConfig> configType() {
        return ProbeConfig.class;
    }

    @Override
    public Promise<ProbeResource> provision(ProbeConfig config) {
        var resource = new ProbeResource();

        PROVISIONED.add(resource);

        return Promise.success(resource);
    }
}
