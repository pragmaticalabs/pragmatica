// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.VersionRoutingKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.VersionRoutingValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.List;

import io.netty.buffer.ByteBuf;

import static org.assertj.core.api.Assertions.assertThat;

/// #1068 — the one predicate every slice start is gated on, at the predicate level. The rolling-update
/// arms are the ones worth pinning on their own: the target already names the NEW version while the
/// OLD instances legitimately run, and a rollback (target back to old, routing entry REMOVED) must
/// leave the rolled-back new version unpermitted.
class CommittedSliceTargetTest {
    private static final ArtifactBase BASE = ArtifactBase.artifactBase("org.example:slice-a").unwrap();
    private static final Version V1 = Version.version("1.0.0").unwrap();
    private static final Version V2 = Version.version("2.0.0").unwrap();

    private KVStore<AetherKey, AetherValue> kvStore;

    @BeforeEach
    void setUp() {
        kvStore = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
    }

    @Test
    void absentTarget_permitsNothing() {
        assertThat(CommittedSliceTarget.permits(kvStore, BASE.withVersion(V1))).isFalse();
    }

    @Test
    void targetNamesTheVersion_permitsIt_andOnlyIt() {
        target(V1);

        assertThat(CommittedSliceTarget.permits(kvStore, BASE.withVersion(V1))).isTrue();
        assertThat(CommittedSliceTarget.permits(kvStore, BASE.withVersion(V2))).isFalse();
    }

    @Test
    void rollingUpdate_permitsOldAndNewWhileTheRoutingEntryExists() {
        target(V2);
        routing(V1, V2);

        assertThat(CommittedSliceTarget.permits(kvStore, BASE.withVersion(V1))).as("old, still routed").isTrue();
        assertThat(CommittedSliceTarget.permits(kvStore, BASE.withVersion(V2))).as("new, the target").isTrue();
    }

    @Test
    void routingWithoutTarget_isNotADeploy() {
        routing(V1, V2);

        assertThat(CommittedSliceTarget.permits(kvStore, BASE.withVersion(V1))).isFalse();
        assertThat(CommittedSliceTarget.permits(kvStore, BASE.withVersion(V2))).isFalse();
    }

    /// The batch `DeploymentManagerImpl.applyRollbackRouting` commits: target back to V1, routing removed.
    @Test
    void rolledBackRollingUpdate_noLongerPermitsTheNewVersion() {
        target(V2);
        routing(V1, V2);
        apply(List.of(new KVCommand.Put<>(SliceTargetKey.sliceTargetKey(BASE), SliceTargetValue.sliceTargetValue(V1, 1)),
                      new KVCommand.Remove<>(VersionRoutingKey.versionRoutingKey(BASE))));

        assertThat(CommittedSliceTarget.permits(kvStore, BASE.withVersion(V1))).as("restored old target").isTrue();
        assertThat(CommittedSliceTarget.permits(kvStore, BASE.withVersion(V2))).as("the rolled-back version").isFalse();
    }

    private void target(Version version) {
        apply(List.of(new KVCommand.Put<>(SliceTargetKey.sliceTargetKey(BASE), SliceTargetValue.sliceTargetValue(version, 1))));
    }

    private void routing(Version oldVersion, Version newVersion) {
        apply(List.of(new KVCommand.Put<>(VersionRoutingKey.versionRoutingKey(BASE),
                                          VersionRoutingValue.versionRoutingValue(oldVersion, newVersion))));
    }

    private void apply(List<KVCommand<AetherKey>> commands) {
        kvStore.process(kvStore.createBatch(commands));
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
