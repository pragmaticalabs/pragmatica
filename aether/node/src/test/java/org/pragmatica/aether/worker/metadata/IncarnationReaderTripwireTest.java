// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.metadata;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterIncarnationValue;
import org.pragmatica.cluster.state.kvstore.IncarnationTag;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

/// Worker-replica tripwire for the production incarnation reader (W-OBS, section 2.1 R-02). The commit ring records and serves only under a
/// committed incarnation tag, read from `ClusterIncarnationKey` by an `IncarnationSource`. W1 lands the ring with no production reader of
/// that key: `AetherNode` builds its store with the three-argument constructor, which wires `IncarnationSource.none()`, so the production ring
/// records nothing. W2 adds the real reader. This ENABLED tripwire pins today's wiring so the gap cannot be forgotten; the real assertion is
/// `@Disabled` beside it.
class IncarnationReaderTripwireTest {
    private static final String ENABLE_AT_W2 = "enable at W2: real incarnation reader";
    private static final String PRODUCTION_CONSTRUCTION = "newKVStore<AetherKey,AetherValue>(delegateRouter,serializer,deserializer)";

    private static final Serializer NO_BYTES = new Serializer() {
        @Override
        public <T> void write(ByteBuf buffer, T value) {}
    };

    private static final Deserializer NOTHING = new Deserializer() {
        @Override
        public <T> T read(ByteBuf buffer) {
            return null;
        }
    };

    /// The store as `AetherNode` builds it (the three-argument constructor): a cluster incarnation committed at slot 0, then an ordinary write.
    private static KVStore<AetherKey, AetherValue> productionStoreAfterGenesis() {
        var store = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), NO_BYTES, NOTHING);

        commit(store, 0, AetherKey.ClusterIncarnationKey.SINGLETON, ClusterIncarnationValue.genesis("lineage-1", "incarnation-id-1"));
        commit(store, 1, new AetherKey.LogLevelKey("org.example"), AetherValue.LogLevelValue.logLevelValue("org.example", "DEBUG"));

        return store;
    }

    private static void commit(KVStore<AetherKey, AetherValue> store, long slot, AetherKey key, AetherValue value) {
        store.processCommitted(store.createBatch(List.of(new KVCommand.Put<>(key, value))), slot + 1);
        store.onPhaseCompleted(slot + 1);
    }

    private static Path nodeModule() {
        try {
            return Path.of(AetherNode.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent().getParent();
        } catch (java.net.URISyntaxException e) {
            throw new AssertionError(e);
        }
    }

    /// `AetherNode.java` with comments stripped and whitespace removed.
    private static String aetherNodeCode() {
        try {
            return Files.readString(nodeModule().resolve("src/main/java/org/pragmatica/aether/node/AetherNode.java"))
                        .lines()
                        .map(line -> line.replaceFirst("//.*$", ""))
                        .collect(Collectors.joining())
                        .replaceAll("\\s+", "");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// Main sources under `aether/` that mention `IncarnationSource`. Search space: every `.java` file under a `src/main/java` directory of the
    /// `aether` tree that contains this module.
    private static List<Path> aetherMainSourcesNamingAnIncarnationSource() {
        try (var paths = Files.walk(nodeModule().getParent())) {
            return paths.filter(path -> path.toString().contains("/src/main/java/") && path.toString().endsWith(".java"))
                        .filter(IncarnationReaderTripwireTest::mentionsIncarnationSource)
                        .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean mentionsIncarnationSource(Path source) {
        try {
            return Files.readString(source).contains("IncarnationSource");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void productionWiresNoIncarnationReader_soTheRingRecordsNothing_today() {
        var store = productionStoreAfterGenesis();

        assertThat(aetherNodeCode()).as("%s: AetherNode builds its store with the three-argument constructor (IncarnationSource.none())", ENABLE_AT_W2)
                                    .contains(PRODUCTION_CONSTRUCTION);
        assertThat(aetherMainSourcesNamingAnIncarnationSource()).as("%s: no aether main source supplies an IncarnationSource", ENABLE_AT_W2).isEmpty();
        assertThat(store.replicaRing().tag().isNone()).as("%s: with a committed incarnation the production ring's tag is still NONE", ENABLE_AT_W2).isTrue();
        assertThat(store.replicaRing().entries()).as("%s: and the production ring records nothing", ENABLE_AT_W2).isEmpty();
    }

    @Disabled("enable at W2: real incarnation reader (re-point productionStoreAfterGenesis at the store AetherNode wires with it)")
    @Test
    void productionRing_carriesTheCommittedIncarnation() {
        var store = productionStoreAfterGenesis();

        assertThat(store.replicaRing().tag()).as("the ring's tag is the committed (incarnation, incarnationId)")
                                             .isEqualTo(IncarnationTag.incarnationTag(ClusterIncarnationValue.GENESIS, "incarnation-id-1"));
        assertThat(store.replicaRing().entries()).extracting(entry -> entry.slot()).as("the genesis batch is a bump (not delivered); the write after it is").containsExactly(1L);
    }
}
