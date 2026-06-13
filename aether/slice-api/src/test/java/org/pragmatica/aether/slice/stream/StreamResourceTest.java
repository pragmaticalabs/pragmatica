// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.pragmatica.aether.slice.resource.ResourceVersion;

import org.pragmatica.aether.slice.resource.ResourceAddress;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.StreamConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.resource.ResourceAddress.resourceAddress;
import static org.pragmatica.aether.slice.resource.ResourceVersion.resourceVersion;


class StreamResourceTest {

    @Test
    void ownedCapturesAliasVersionAndConfig() {
        var config = StreamConfig.streamConfig("orders");
        var spec = StreamVersionSpec.exact(resourceVersion(1, 0, 0).unwrap());

        var resource = StreamResource.owned("orders", spec, config);

        assertThat(resource).isInstanceOf(StreamResource.Owned.class);
        var owned = (StreamResource.Owned) resource;
        assertThat(owned.alias()).isEqualTo("orders");
        assertThat(owned.version()).isEqualTo(spec);
        assertThat(owned.config()).isSameAs(config);
    }

    @Test
    void externalCapturesAliasAndTargetAddress() {
        var target = resourceAddress("io.acme.inventory:stock-updates:2.0.0").unwrap();

        var resource = StreamResource.external("inventory_feed", target);

        assertThat(resource).isInstanceOf(StreamResource.External.class);
        var external = (StreamResource.External) resource;
        assertThat(external.alias()).isEqualTo("inventory_feed");
        assertThat(external.target()).isEqualTo(target);
    }

    @Test
    void aliasAccessibleFromBaseInterface() {
        StreamResource owned = StreamResource.owned("o",
                                                    StreamVersionSpec.latest(),
                                                    StreamConfig.DEFAULT);
        StreamResource external = StreamResource.external("e",
                                                           resourceAddress("a.b:s:1.0.0").unwrap());

        assertThat(owned.alias()).isEqualTo("o");
        assertThat(external.alias()).isEqualTo("e");
    }
}
