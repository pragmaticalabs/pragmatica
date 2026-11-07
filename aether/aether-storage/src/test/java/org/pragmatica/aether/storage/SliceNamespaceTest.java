// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.storage;

import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.storage.Namespace;

import static org.assertj.core.api.Assertions.assertThat;

class SliceNamespaceTest {
    private static Namespace namespaceOf(String artifactBase) {
        return ArtifactBase.artifactBase(artifactBase)
                           .flatMap(SliceNamespace::sliceNamespace)
                           .unwrap();
    }

    @Test
    void sliceNamespace_shortName_isGroupDotArtifact() {
        assertThat(namespaceOf("com.example:order-service").value()).isEqualTo("com.example.order-service");
    }

    @Test
    void sliceNamespace_underscoreAndCase_stayWithinTheAlphabet() {
        assertThat(namespaceOf("org.my_corp:svc").value()).isEqualTo("org.my_corp.svc");
    }

    @Test
    void sliceNamespace_sameInput_isDeterministic() {
        var group = "com.example." + "a".repeat(60);

        assertThat(namespaceOf(group + ":svc")).isEqualTo(namespaceOf(group + ":svc"));
    }

    @Test
    void sliceNamespace_longName_isPrefixPlusDigest_withinSixtyThreeCharacters() {
        var group = "com.example." + "a".repeat(60);
        var value = namespaceOf(group + ":svc").value();

        assertThat(value).hasSize(46 + 1 + 16)
                         .startsWith(("com.example." + "a".repeat(60)).substring(0, 46) + "-")
                         .matches("[a-z0-9][a-z0-9._-]{0,62}");
    }

    @Test
    void sliceNamespace_distinctInputs_neverCollide_evenSharingALongPrefix() {
        var group = "com.example." + "b".repeat(60);
        var inputs = List.of(group + ":one",
                             group + ":two",
                             group + "x:one",
                             "com.example:one",
                             "com.example.one:x",
                             "com.examplex:one");
        var namespaces = new HashSet<Namespace>();

        inputs.forEach(input -> namespaces.add(namespaceOf(input)));

        assertThat(namespaces).hasSize(inputs.size());
    }

    @Test
    void sliceNamespace_exactlySixtyThreeCharacters_isKeptPlain() {
        var group = "com.e" + "c".repeat(63 - "com.e".length() - ".s".length());
        var value = namespaceOf(group + ":s").value();

        assertThat(value).hasSize(63)
                         .isEqualTo(group + ".s");
    }
}
