// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;

/// #2100 E-B N5b/N7: the connector cache is keyed by the cache key, created from the section; `release` and
/// `releaseAll` act on the cache key, and the one-argument forms are the key-equals-section case.
class DatasourceConnectionProviderReleaseTest {
    private static final TimeSpan DEADLINE = TimeSpan.timeSpan(10).seconds();

    private final AtomicInteger creations = new AtomicInteger();
    private final AtomicInteger stops = new AtomicInteger();

    private void serve(String section) {
        ControlledSqlConnectorFactory.behave(section, this::created);
    }

    private Promise<SqlConnector> created() {
        creations.incrementAndGet();
        return Promise.success(ControlledSqlConnectorFactory.stubConnector(stops));
    }

    @Test
    void release_byCacheKey_removesTheEntryStopsTheConnectorAndTheNextCallCreatesAnew() throws Exception {
        serve("rel-section");

        var provider = ControlledSqlConnectorFactory.provider();

        assertThat(provider.connector("rel-key", "rel-section").await(DEADLINE).isSuccess()).isTrue();
        assertThat(ControlledSqlConnectorFactory.cacheOf(provider)).as("cached by the key, not the section").containsOnlyKeys("rel-key");

        assertThat(provider.release("rel-key", "rel-section").await(DEADLINE).isSuccess()).isTrue();

        assertThat(ControlledSqlConnectorFactory.cacheOf(provider)).isEmpty();
        assertThat(stops.get()).as("the released connector was stopped").isEqualTo(1);
        assertThat(provider.connector("rel-key", "rel-section").await(DEADLINE).isSuccess()).isTrue();
        assertThat(creations.get()).as("created anew after release").isEqualTo(2);
    }

    @Test
    void release_ofAnAbsentKey_isASuccessThatStopsNothing() {
        var provider = ControlledSqlConnectorFactory.provider();

        assertThat(provider.release("never-created", "never-created").await(DEADLINE).isSuccess()).isTrue();
        assertThat(stops.get()).isZero();
    }

    @Test
    void oneArgumentForms_useTheNameAsKeyAndAsSection() throws Exception {
        serve("one-arg");

        var provider = ControlledSqlConnectorFactory.provider();

        assertThat(provider.connector("one-arg").await(DEADLINE).isSuccess()).as("section is the name").isTrue();
        assertThat(ControlledSqlConnectorFactory.cacheOf(provider)).containsOnlyKeys("one-arg");

        assertThat(provider.release("one-arg").await(DEADLINE).isSuccess()).isTrue();

        assertThat(ControlledSqlConnectorFactory.cacheOf(provider)).isEmpty();
        assertThat(stops.get()).isEqualTo(1);
    }

    @Test
    void releaseAll_stopsEveryCachedConnectorAndEmptiesTheCache() throws Exception {
        serve("all-a");
        serve("all-b");

        var provider = ControlledSqlConnectorFactory.provider();

        provider.connector("all-key-a", "all-a").await(DEADLINE);
        provider.connector("all-key-b", "all-b").await(DEADLINE);

        assertThat(provider.releaseAll().await(DEADLINE).isSuccess()).isTrue();

        assertThat(ControlledSqlConnectorFactory.cacheOf(provider)).isEmpty();
        assertThat(stops.get()).isEqualTo(2);
    }
}
