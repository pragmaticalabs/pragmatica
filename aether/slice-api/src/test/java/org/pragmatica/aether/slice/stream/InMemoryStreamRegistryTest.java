// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.pragmatica.aether.slice.resource.ResourceVersion;

import org.pragmatica.aether.slice.resource.ResourceAddress;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.stream.StreamRegistry.StreamRegistryError.General;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.resource.ResourceAddress.resourceAddress;
import static org.pragmatica.aether.slice.resource.ResourceVersion.resourceVersion;


class InMemoryStreamRegistryTest {

    private static Cause errorOf(Result<?> result) {
        return result.fold(c -> c, _ -> null);
    }

    private static Cause errorOf(Promise<?> promise) {
        return errorOf(promise.await());
    }

    private static ResourceAddress addr(String s) {
        return resourceAddress(s).unwrap();
    }

    private static StreamRegistryEntry frameworkEntry(ResourceAddress address) {
        return StreamRegistryEntry.framework(address, RetentionPolicy.retentionPolicy(), Instant.EPOCH);
    }

    private static StreamRegistryEntry blueprintEntry(ResourceAddress address) {
        return StreamRegistryEntry.blueprint(address, RetentionPolicy.retentionPolicy(), Instant.EPOCH);
    }

    private InMemoryStreamRegistry registry;

    @BeforeEach void setUp() {
        registry = new InMemoryStreamRegistry();
    }

    @Nested
    class Registration {

        @Test
        void registerNewEntrySucceeds() {
            var entry = frameworkEntry(addr("system:cluster-events:1.0.0"));

            var result = registry.register(entry);

            assertThat(result.unwrap()).isEqualTo(entry);
            assertThat(registry.snapshot()).containsExactly(entry);
        }

        @Test
        void registerDuplicateAddressFails() {
            var entry = frameworkEntry(addr("system:cluster-events:1.0.0"));
            registry.register(entry);

            var duplicate = registry.register(entry);

            assertThat(errorOf(duplicate)).isEqualTo(General.ALREADY_REGISTERED);
        }

        @Test
        void registerMultipleVersionsOfSameStream() {
            registry.register(frameworkEntry(addr("system:cluster-events:1.0.0")));
            registry.register(frameworkEntry(addr("system:cluster-events:2.0.0")));

            assertThat(registry.snapshot()).hasSize(2);
        }
    }

    @Nested
    class Lookup {

        @Test
        void lookupExistingAddress() {
            var entry = frameworkEntry(addr("system:cluster-events:1.0.0"));
            registry.register(entry);

            var found = registry.lookup(addr("system:cluster-events:1.0.0"));

            assertThat(found.isPresent()).isTrue();
            found.onPresent(e -> assertThat(e).isEqualTo(entry));
        }

        @Test
        void lookupMissingAddressReturnsNone() {
            var found = registry.lookup(addr("system:cluster-events:1.0.0"));

            assertThat(found.isEmpty()).isTrue();
        }
    }

    @Nested
    class Resolve {

        @Test
        void resolveExactReturnsEntry() {
            var entry = frameworkEntry(addr("system:cluster-events:1.0.0"));
            registry.register(entry);

            var result = registry.resolve("system", "cluster-events",
                                           StreamVersionSpec.exact(resourceVersion(1, 0, 0).unwrap()));

            assertThat(result.unwrap()).isEqualTo(entry);
        }

        @Test
        void resolveExactMissingReturnsNotFound() {
            var result = registry.resolve("system", "cluster-events",
                                           StreamVersionSpec.exact(resourceVersion(1, 0, 0).unwrap()));

            assertThat(errorOf(result)).isEqualTo(General.NOT_FOUND);
        }

        @Test
        void resolveLatestPicksHighestVersion() {
            registry.register(frameworkEntry(addr("system:cluster-events:1.0.0")));
            registry.register(frameworkEntry(addr("system:cluster-events:2.1.0")));
            registry.register(frameworkEntry(addr("system:cluster-events:1.5.3")));

            var result = registry.resolve("system", "cluster-events", StreamVersionSpec.latest());

            assertThat(result.unwrap().address().version())
                    .isEqualTo(resourceVersion(2, 1, 0).unwrap());
        }

        @Test
        void resolveLatestWithNoVersionsFails() {
            var result = registry.resolve("system", "cluster-events", StreamVersionSpec.latest());

            assertThat(errorOf(result)).isEqualTo(General.NO_VERSIONS_REGISTERED);
        }

        @Test
        void resolveIsolatesByNamespace() {
            registry.register(blueprintEntry(addr("com.example.a:feed:1.0.0")));
            registry.register(blueprintEntry(addr("com.example.b:feed:2.0.0")));

            var result = registry.resolve("com.example.a", "feed", StreamVersionSpec.latest());

            assertThat(result.unwrap().address().namespace().value()).isEqualTo("com.example.a");
            assertThat(result.unwrap().address().version()).isEqualTo(resourceVersion(1, 0, 0).unwrap());
        }
    }

    @Nested
    class ReferenceCounting {

        @Test
        void acquireIncrementsRefCount() {
            var address = addr("system:cluster-events:1.0.0");
            registry.register(frameworkEntry(address));

            var after = registry.acquireReference(address).await().unwrap();

            assertThat(after.refCount()).isEqualTo(2);
            assertThat(registry.lookup(address).unwrap().refCount()).isEqualTo(2);
        }

        @Test
        void acquireOnMissingAddressFails() {
            var result = registry.acquireReference(addr("system:cluster-events:1.0.0"));

            assertThat(errorOf(result)).isEqualTo(General.NOT_FOUND);
        }

        @Test
        void releaseBelowOneRemovesEntry() {
            var address = addr("system:cluster-events:1.0.0");
            registry.register(frameworkEntry(address));

            var outcome = registry.releaseReference(address).await().unwrap();

            assertThat(outcome.removed()).isTrue();
            assertThat(registry.lookup(address).isEmpty()).isTrue();
        }

        @Test
        void releaseAboveOneKeepsEntry() {
            var address = addr("system:cluster-events:1.0.0");
            registry.register(frameworkEntry(address));
            registry.acquireReference(address).await();

            var outcome = registry.releaseReference(address).await().unwrap();

            assertThat(outcome.removed()).isFalse();
            assertThat(outcome.entry().refCount()).isEqualTo(1);
            assertThat(registry.lookup(address).isPresent()).isTrue();
        }

        @Test
        void releaseOnMissingAddressFails() {
            var result = registry.releaseReference(addr("system:cluster-events:1.0.0"));

            assertThat(errorOf(result)).isEqualTo(General.NOT_FOUND);
        }
    }

    @Nested
    class Snapshot {

        @Test
        void snapshotReturnsImmutableCopy() {
            registry.register(frameworkEntry(addr("system:cluster-events:1.0.0")));

            var first = registry.snapshot();
            registry.register(frameworkEntry(addr("system:cluster-events:2.0.0")));
            var second = registry.snapshot();

            assertThat(first).hasSize(1);
            assertThat(second).hasSize(2);
        }
    }
}
