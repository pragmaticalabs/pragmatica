// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #1282 (review F1): a blueprint `External` stream source is user input, and the slice's stream factories
/// MINT whatever engine key it resolves to. An address in the `topic` or `entity` namespace resolves to a
/// `topic:…`/`entity:…` engine key — a stream kind only internal provisioning may create. An `entity:`
/// key can EXACTLY collide with a real keyspace log (keyspace names may contain `:`). No legitimate
/// reference exists: spec §11.2 allows an External source to name another blueprint's namespace or the
/// `system` namespace, and a real durable-topic stream (`topic:<ns>:<name>:<ver>`) is not even addressable
/// in the three-part form. So the parser refuses it at validation, before any binding is published.
class StreamConfigParserReservedSourceTest {

    @Test
    void externalSource_topicNamespace_isRefused() {
        assertRefused("topic:orders:1.0.0", "topic:");
    }

    @Test
    void externalSource_entityNamespace_isRefused() {
        assertRefused("entity:orders:1.0.0", "entity:");
    }

    /// Spec §11.2: a consumer may reference the `system` namespace. Its engine key is the bare name, so it
    /// carries no reserved kind prefix and must still parse.
    @Test
    void externalSource_systemNamespace_stillParses() {
        assertParses("system:cluster-events:1.0.0");
    }

    @Test
    void externalSource_anotherBlueprintsNamespace_stillParses() {
        assertParses("io.acme.inventory:stock-updates:2.0.0");
    }

    private static void assertRefused(String source, String prefix) {
        StreamConfigParser.parseResources(toml(source))
                          .onSuccess(_ -> fail("External source '" + source + "' must be refused"))
                          .onFailure(cause -> assertThat(cause.message()).contains(source)
                                                                         .contains("'" + prefix + "'"));
    }

    private static void assertParses(String source) {
        StreamConfigParser.parseResources(toml(source))
                          .onFailure(cause -> fail("External source '" + source + "' must parse: " + cause.message()));
    }

    private static String toml(String source) {
        return "[streams.inbox]\nsource = \"" + source + "\"\nrole = \"consumer\"\n";
    }
}
