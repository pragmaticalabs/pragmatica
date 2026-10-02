// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ArtifactVersionsKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactVersionEntry;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactVersionsValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SystemTags;

import static org.assertj.core.api.Assertions.assertThat;

/// #1778: the artifact-versions key and value travel through consensus, so each type needs a hand-assigned
/// wire tag (an unpinned one makes every node fail to boot). Pinned where the node codec is assembled.
class ArtifactVersionsPutCodecTest {
    @Test
    void versionsTypes_havePinnedSystemTags() {
        assertThat(SystemTags.tagFor("org.pragmatica.aether.slice.kvstore.AetherKey.ArtifactVersionsKey")).isEqualTo(2114);
        assertThat(SystemTags.tagFor("org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactVersionsValue")).isEqualTo(2115);
        assertThat(SystemTags.tagFor("org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactVersionEntry")).isEqualTo(2116);
    }

    @Test
    void versionsPut_roundTrips_throughTheNodeCodec_withTheArchivedFlag() {
        var codec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
        var key = ArtifactVersionsKey.artifactVersionsKey(ArtifactBase.artifactBase("org.example:lib").unwrap());
        var value = new ArtifactVersionsValue(java.util.List.of(new ArtifactVersionEntry("1.0.0", true),
                                                                new ArtifactVersionEntry("2.0.0", false)),
                                           ArtifactVersionsValue.DEFAULT_MAX_LIVE);
        var put = new KVCommand.Put<AetherKey, AetherValue>(key, value);
        var buf = Unpooled.buffer();

        codec.write(buf, put);
        KVCommand.Put<?, ?> decoded = codec.read(buf);

        assertThat(decoded.key()).isEqualTo(key);
        assertThat(decoded.value()).isEqualTo(value);
        assertThat(((ArtifactVersionsValue) decoded.value()).live()).containsExactly("2.0.0");
    }

    @Test
    void contentBinding_hasPinnedTags_andRoundTripsThroughTheNodeCodec() {
        assertThat(SystemTags.tagFor("org.pragmatica.aether.slice.kvstore.AetherKey.ArtifactContentKey")).isEqualTo(2117);
        assertThat(SystemTags.tagFor("org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactContentValue")).isEqualTo(2118);

        var codec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
        var key = AetherKey.ArtifactContentKey.artifactContentKey(ArtifactBase.artifactBase("org.example:lib").unwrap(), "1.0.0", "sources.jar");
        var value = new AetherValue.ArtifactContentValue(42L, "md5hex", "sha1hex");
        var buf = Unpooled.buffer();

        codec.write(buf, new KVCommand.Put<AetherKey, AetherValue>(key, value));
        KVCommand.Put<?, ?> decoded = codec.read(buf);

        assertThat(decoded.key()).isEqualTo(key);
        assertThat(decoded.value()).isEqualTo(value);
        assertThat(AetherKey.ArtifactContentKey.artifactContentKey(key.asString()).unwrap()).isEqualTo(key);
    }

    @Test
    void versionsKey_parsesItsOwnStringForm() {
        var key = ArtifactVersionsKey.artifactVersionsKey(ArtifactBase.artifactBase("org.example:lib").unwrap());

        assertThat(ArtifactVersionsKey.artifactVersionsKey(key.asString()).unwrap()).isEqualTo(key);
        assertThat(ArtifactVersionsKey.artifactVersionsKey("slice-target/org.example:lib").isFailure()).isTrue();
    }
}
