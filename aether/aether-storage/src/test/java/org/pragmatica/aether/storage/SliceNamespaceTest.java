// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.storage;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.storage.Namespace;

import static org.assertj.core.api.Assertions.assertThat;

/// U2: the namespace a slice's stored uploads live in is its `groupId.artifactId`; a name too long for a [Namespace]
/// keeps a 46-character prefix and a 16-hex-digit digest of the whole coordinate, so long names sharing a prefix differ.
class SliceNamespaceTest {
    private static final String LONG_GROUP = "org.example.averyveryverylongorganisationname.platform";

    @Test
    void sliceNamespace_shortCoordinate_isGroupDotArtifact() {
        assertThat(namespaceOf("org.example:orders")).isEqualTo("org.example.orders");
    }

    @Test
    void sliceNamespace_tooLongCoordinate_isPrefixDashDigest_withinTheNamespaceLimit() {
        var value = namespaceOf(LONG_GROUP + ":inventory-service");

        assertThat(value).hasSize(63)
                         .startsWith((LONG_GROUP + ".inventory-service").substring(0, 46) + "-")
                         .matches("[a-z0-9][a-z0-9._-]{0,62}");
        assertThat(value.substring(47)).matches("[0-9a-f]{16}");
    }

    @Test
    void sliceNamespace_longCoordinatesSharingAPrefix_differ_andEachIsStable() {
        var first = namespaceOf(LONG_GROUP + ":inventory-service");
        var second = namespaceOf(LONG_GROUP + ":inventory-search");

        assertThat(first).isNotEqualTo(second);
        assertThat(namespaceOf(LONG_GROUP + ":inventory-service")).isEqualTo(first);
    }

    private static String namespaceOf(String coordinate) {
        return ArtifactBase.artifactBase(coordinate)
                           .flatMap(SliceNamespace::sliceNamespace)
                           .map(Namespace::value)
                           .unwrap();
    }
}
