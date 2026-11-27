// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConsumerGroupKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerGroupValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator.CoordinatorError;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #3041 through the REAL assembly: the stream-life guard and the reclaim, on a booted single-node cluster. A lone node never
/// elects itself (the election's empty-topology guard skips the proposal, seen in the first run's log), so the test
/// routes the `LeaderChange` a leader would raise through `AetherNode.route`, the same entry the election uses. For
/// the same reason no join can commit (the epoch mint needs a committed leader), so the live life's records are written
/// as the coordinator would write them; the admit side of the join is covered by `ConsumerGroupStreamLifeReclaimTest`. The unit tests drive a coordinator over a store they built; what only this test pins is what
/// `AetherNode` wires: `StreamLives.committedIn(kvStore)` into the coordinator (a join of a stream with no committed
/// config is refused, a join of a committed one is not), the applier's guard on [ConsumerGroupKey] in the node's own
/// KV store (a write of a dead life is refused), and the router entry that hands a committed removal of a
/// [StreamConfigKey] to the coordinator (the records of the deleted stream disappear through consensus).
///
/// Red with `StreamLives.committedIn(kvStore)` replaced by a constant (the first or the second join assertion), with the
/// `onRemove(StreamConfigKey ...)` route in `AetherNode` removed (the reclaim never happens: the records stay), and with
/// the guard removed from [ConsumerGroupKey] (the dead-life write is committed).
class AetherNodeConsumerGroupStreamLifeBootTest {
    private static final String STREAM = "life-stream";
    private static final long LIFE = 7L;

    @TempDir
    Path tempDir;

    private AetherNode node;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop()
                .await(timeSpan(10).seconds())
                .onFailure(cause -> {});
        }

        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 150, unit = SECONDS)
    void assembledNode_refusesGroupOfNoStream_reclaimsRecordsOfADeletedOne_andRefusesDeadLifeWrites() {
        node = AetherNode.aetherNode(AetherNodeContentStorageWarnBootTest.minimalConfig(Option.none(),
                                                                                       Option.none(),
                                                                                       ConfigurationProvider.builder().build(),
                                                                                       tempDir),
                                     () -> {})
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();
        node.start()
            .await(timeSpan(30).seconds())
            .onFailure(cause -> fail("start() must succeed: " + cause.message()));

        node.route(LeaderNotification.leaderChange(Option.some(node.self()), true));

        awaitJoinRefusedAs(CoordinatorError.STREAM_NOT_COMMITTED);
        assertThat(recordsOfStream()).as("the refused join committed nothing").isEmpty();

        node.<Object>apply(List.of(putStreamConfig()))
            .await(timeSpan(30).seconds())
            .onFailure(cause -> fail("the stream config must commit: " + cause.message()));
        node.<Object>apply(List.of(groupRecord(0), groupRecord(1)))
            .await(timeSpan(30).seconds())
            .onFailure(cause -> fail("the records of the live life must commit: " + cause.message()));

        await().atMost(Duration.ofSeconds(60))
               .untilAsserted(() -> assertThat(recordsOfStream()).as("the records of the live life were admitted and mirrored by the node's registry")
                                                                 .hasSize(2));

        node.<Object>apply(List.of(new KVCommand.Remove<AetherKey>(StreamConfigKey.streamConfigKey(STREAM))))
            .await(timeSpan(30).seconds())
            .onFailure(cause -> fail("the stream delete must commit: " + cause.message()));

        await().atMost(Duration.ofSeconds(60))
               .untilAsserted(() -> assertThat(recordsOfStream()).as("the node's reclaim removed the records of the deleted stream")
                                                                 .isEmpty());

        node.kvStore()
            .process(node.kvStore()
                         .createBatch(List.of(new KVCommand.Put<AetherKey, AetherValue>(ConsumerGroupKey.consumerGroupKey("g", STREAM, 0),
                                                                                         ConsumerGroupValue.consumerGroupValue(node.self(),
                                                                                                                              "c1",
                                                                                                                              Epoch.epoch(9L, 9L, 9L),
                                                                                                                              LIFE)))));
        assertThat(recordsOfStream()).as("the guard of the node's own store refuses a write of the dead life").isEmpty();
    }

    private void awaitJoinRefusedAs(CoordinatorError expected) {
        await().atMost(Duration.ofSeconds(60))
               .untilAsserted(() -> assertThat(joined().fold(Cause::message, _ -> "joined")).as("the coordinator is active and refuses the stream")
                                                                                            .isEqualTo(expected.message()));
    }

    private Result<Unit> joined() {
        return node.consumerGroupCoordinator().joinGroup("g", STREAM, 2, "c1", node.self());
    }

    private KVCommand<AetherKey> groupRecord(int partition) {
        return new KVCommand.Put<AetherKey, AetherValue>(ConsumerGroupKey.consumerGroupKey("g", STREAM, partition),
                                                         ConsumerGroupValue.consumerGroupValue(node.self(),
                                                                                              "c1",
                                                                                              Epoch.epoch(1L, 1L, 1L),
                                                                                              LIFE));
    }

    private static KVCommand<AetherKey> putStreamConfig() {
        return new KVCommand.Put<AetherKey, AetherValue>(StreamConfigKey.streamConfigKey(STREAM),
                                                         StreamConfigValue.streamConfigValue(StreamConfig.streamConfig(STREAM)
                                                                                                         .withIncarnation(LIFE)));
    }

    private List<AetherKey> recordsOfStream() {
        return node.kvStore()
                   .snapshot()
                   .keySet()
                   .stream()
                   .filter(key -> key instanceof ConsumerGroupKey group && group.streamName().equals(STREAM))
                   .toList();
    }
}
