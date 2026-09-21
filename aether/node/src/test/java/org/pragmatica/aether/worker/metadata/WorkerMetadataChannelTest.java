package org.pragmatica.aether.worker.metadata;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.node.NodeCodecs;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.FrameworkCodecs;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


class WorkerMetadataChannelTest {
    private static final NodeId CORE = new NodeId("core");
    private static final NodeId WORKER = new NodeId("worker");
    private static final NodeId FOREIGN = new NodeId("foreign");

    private static final WorkerMetadataLimits LIMITS = new WorkerMetadataLimits(64,
                                                                                8192,
                                                                                65536,
                                                                                1048576,
                                                                                8,
                                                                                32,
                                                                                org.pragmatica.lang.io.TimeSpan.timeSpan(30)
                                                                                                               .seconds(),
                                                                                org.pragmatica.lang.io.TimeSpan.timeSpan(0)
                                                                                                               .millis());

    @Test
    void manifestCannotCombineNewCommittedRevisionWithPartiallyDeliveredIndex() {
        var fixture = new Fixture();
        fixture.seed(1, "initial");
        fixture.client.tick();
        fixture.pump();
        var captured = new java.util.concurrent.atomic.AtomicBoolean();
        fixture.router.addRoute(org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut.class,
            (org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut<AetherKey, AetherValue> put) -> {
                if (captured.compareAndSet(false, true)) {
                    assertThat(fixture.core.committedRevision()).isEqualTo(2);
                    fixture.server.onManifestRequest(new WorkerMetadataMessage.ManifestRequest(WORKER, 42, 2));
                    var response = (WorkerMetadataMessage.Manifest) fixture.responses.remove();
                    assertThat(response.error()).isEqualTo("projection-unavailable-or-oversize");
                    assertThat(response.scopes()).isEmpty();
                    assertThat(fixture.worker.getTyped(fixture.configKey, AetherValue.ConfigValue.class)
                        .unwrap().value()).isEqualTo("initial");
                }
                fixture.server.put(put.cause().key(), put.cause().value());
            });
        var other = new AetherKey.ConfigKey("other", Option.none());
        List<KVCommand<AetherKey>> changes = List.of(
            new KVCommand.Put<>(fixture.configKey, new AetherValue.ConfigValue("test", "updated", 2)),
            new KVCommand.Put<>(other, new AetherValue.ConfigValue("other", "same-cut", 2)));
        fixture.core.processCommitted(fixture.core.createBatch(changes), 2);
        assertThat(captured).isTrue();
        fixture.client.tick();
        fixture.pump();
        assertThat(fixture.worker.getTyped(fixture.configKey, AetherValue.ConfigValue.class)
            .unwrap().value()).isEqualTo("updated");
        assertThat(fixture.worker.getTyped(other, AetherValue.ConfigValue.class)
            .unwrap().value()).isEqualTo("same-cut");
        assertThat(fixture.client.hasFreshProjection()).isTrue();
    }

    @Test
    void unauthorizedWorkerCannotAllocateManifestAndForeignCoreCannotInstallProjection() {
        var fixture = new Fixture();

        fixture.seed(1, "initial");
        fixture.server.onManifestRequest(new WorkerMetadataMessage.ManifestRequest(FOREIGN, 1, 0));
        assertThat(fixture.responses).isEmpty();
        assertThat(fixture.server.manifestCount()).isZero();
        fixture.client.tick();
        fixture.deliverRequest();
        var valid = (WorkerMetadataMessage.Manifest) fixture.responses.remove();

        fixture.client.onManifest(new WorkerMetadataMessage.Manifest(FOREIGN,
                                                                     valid.requestId(),
                                                                     valid.incarnation(),
                                                                     valid.generation(),
                                                                     valid.committedRevision(),
                                                                     valid.scopes(),
                                                                     ""));
        assertThat(fixture.requests).isEmpty();
        assertThat(fixture.worker.snapshot()).isEmpty();
        assertThat(fixture.client.hasFreshProjection()).isFalse();
        fixture.client.onManifest(valid);
        fixture.pump();
        assertThat(fixture.client.hasFreshProjection()).isTrue();
    }

    @Test
    void reconnectBurstCannotExceedManifestOrSharedCacheBudgets() {
        var fixture = new Fixture();

        fixture.seed(1, "initial");
        var responses = new ArrayList<ProtocolMessage>();
        var server = new WorkerMetadataServer(CORE,
                                              fixture.core,
                                              fixture.codec,
                                              (_, message) -> responses.add(message),
                                              _ -> true,
                                              () -> Set.of(CORE),
                                              _ -> List.of(),
                                              LIMITS,
                                              (_, _) -> {});

        for (int index = 0; index < 100; index++) {
            server.onManifestRequest(new WorkerMetadataMessage.ManifestRequest(new NodeId("worker-" + index), 1, 0));
        }

        assertThat(server.manifestCount()).isEqualTo(LIMITS.manifests());
        assertThat(server.cachedBytes()).isLessThanOrEqualTo(LIMITS.cacheBytes());
        assertThat(responses.stream()
                            .map(WorkerMetadataMessage.Manifest.class::cast)
                            .filter(response -> response.error()
                                                        .equals("manifest-capacity"))
                            .count()).isEqualTo(92);
    }

    @Test
    void restoredCoreStateInvalidatesPreviouslyCapturedScopeIndex() {
        var fixture = new Fixture();

        fixture.seed(1, "initial");
        fixture.client.tick();
        fixture.pump();
        var restored = new java.util.HashMap<>(fixture.core.snapshot());

        restored.put(fixture.configKey, new AetherValue.ConfigValue("test", "restored", 9));
        fixture.core.restoreCommittedSnapshot(fixture.codec.encode(restored), 9).unwrap();
        fixture.server.onStateRestored();
        fixture.client.tick();
        fixture.pump();
        assertThat(fixture.worker.getTyped(fixture.configKey, AetherValue.ConfigValue.class).unwrap().value()).isEqualTo("restored");
    }

    @Test
    void projectionAdmissionStartsClosedAndExpiresIndependentlyOfCoreContact() {
        var fixture = new Fixture();

        assertThat(fixture.client.hasFreshProjection()).isFalse();
        fixture.seed(1, "initial");
        fixture.client.tick();
        fixture.pump();
        assertThat(fixture.client.hasFreshProjection()).isTrue();
        assertThat(fixture.client.hasFreshProjection(Long.MAX_VALUE)).isFalse();
    }

    @Test
    void boundedChunksInstallOneCutAndExcludeUnrelatedCommunity() {
        var fixture = new Fixture();

        fixture.seed(1, "initial");
        fixture.client.tick();
        fixture.deliverRequest();
        fixture.deliverResponse();
        assertThat(fixture.worker.snapshot()).isEmpty();
        fixture.seed(2, "later");
        fixture.pump();
        assertThat(fixture.worker.getTyped(fixture.configKey, AetherValue.ConfigValue.class).unwrap().value()).isEqualTo("initial");
        assertThat(fixture.worker.get(new AetherKey.ActivationDirectiveKey(FOREIGN)).isEmpty()).isTrue();
        assertThat(fixture.ready).hasValue(1);
        fixture.client.tick();
        fixture.pump();
        assertThat(fixture.worker.getTyped(fixture.configKey, AetherValue.ConfigValue.class).unwrap().value()).isEqualTo("later");
        assertThat(fixture.server.cachedBytes()).isLessThanOrEqualTo(LIMITS.cacheBytes());
    }

    @Test
    void corruptChunkNeverBecomesVisible() {
        var fixture = new Fixture();

        fixture.seed(1, "initial");
        fixture.client.tick();
        fixture.deliverRequest();
        fixture.deliverResponse();
        fixture.deliverRequest();
        var original = (WorkerMetadataMessage.Chunk) fixture.responses.remove();
        var bytes = original.bytes();

        bytes[0] ^= 1;
        fixture.client.onChunk(new WorkerMetadataMessage.Chunk(original.sender(),
                                                               original.requestId(),
                                                               original.incarnation(),
                                                               original.generation(),
                                                               original.scope(),
                                                               original.hash(),
                                                               original.offset(),
                                                               bytes,
                                                               ""));
        fixture.pump();
        assertThat(fixture.worker.snapshot()).isEmpty();
        assertThat(fixture.ready).hasValue(0);
        assertThat(fixture.errors).isNotEmpty();
    }

    @Test
    void laggingCoreRefusesWorkersInstalledRevision() {
        var fixture = new Fixture();

        fixture.seed(5, "newer");
        fixture.client.tick();
        fixture.pump();
        fixture.client.tick();
        var request = (WorkerMetadataMessage.ManifestRequest) fixture.requests.remove();

        assertThat(request.minimumRevision()).isEqualTo(5);
        var lagging = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), fixture.codec, fixture.codec);
        var replies = new ArrayList<ProtocolMessage>();
        var server = new WorkerMetadataServer(CORE,
                                              lagging,
                                              fixture.codec,
                                              (_, message) -> replies.add(message),
                                              WORKER::equals,
                                              () -> Set.of(CORE),
                                              _ -> List.of(),
                                              LIMITS,
                                              (_, _) -> {});

        server.onManifestRequest(request);
        var response = (WorkerMetadataMessage.Manifest) replies.getFirst();

        assertThat(response.error()).isEqualTo("core-behind-worker");
        fixture.client.onManifest(response);
        assertThat(fixture.worker.getTyped(fixture.configKey, AetherValue.ConfigValue.class).unwrap().value()).isEqualTo("newer");
    }

    @Test
    void removingOwnAssignmentClosesAdmissionEvenWhenCoreMetadataRemainsFresh() {
        var fixture = new Fixture();

        fixture.seed(1, "initial");
        fixture.client.tick();
        fixture.pump();
        assertThat(fixture.client.hasFreshProjection()).isTrue();
        var assignment = new AetherKey.ActivationDirectiveKey(WORKER);
        java.util.List<KVCommand<AetherKey>> removal = java.util.List.of(new KVCommand.Remove<>(assignment));

        fixture.core.processCommitted(fixture.core.createBatch(removal), 2);
        fixture.server.remove(assignment);
        fixture.client.tick();
        fixture.pump();
        assertThat(fixture.worker.get(assignment).isEmpty()).isTrue();
        assertThat(fixture.client.hasFreshProjection()).isFalse();
    }

    @Test
    void heterogeneousFrameworkLeaderSurvivesCoreCaptureAndWorkerProjection() {
        var fixture = new Fixture();

        fixture.seed(1, "initial");
        var leader = org.pragmatica.cluster.state.kvstore.LeaderValue.leaderValue(CORE, 1);
        java.util.Map<Object, Object> restored = new java.util.HashMap<>(fixture.core.snapshot());

        restored.put(org.pragmatica.cluster.state.kvstore.LeaderKey.INSTANCE, leader);
        fixture.core.restoreCommittedSnapshot(fixture.codec.encode(restored), 2).unwrap();
        fixture.server.onStateRestored();
        fixture.client.tick();
        fixture.pump();
        java.util.Map<?, ?> projected = fixture.worker.snapshot();

        assertThat(projected.get(org.pragmatica.cluster.state.kvstore.LeaderKey.INSTANCE)).isEqualTo(leader);
        assertThat(fixture.client.hasFreshProjection()).isTrue();
        assertThat(fixture.errors).isEmpty();
    }

    @Test
    void rejectedCoreDirectoryCannotPublishKvOrReadyState() {
        var fixture = new Fixture();

        fixture.directoryAccepted.set(false);
        fixture.seed(1, "initial");
        fixture.client.tick();
        fixture.pump();
        assertThat(fixture.worker.snapshot()).isEmpty();
        assertThat(fixture.ready).hasValue(0);
        assertThat(fixture.client.hasFreshProjection()).isFalse();
        assertThat(fixture.errors).contains("metadata projection install failed");
    }

    @Test
    void oversizedScopeReportsFailureAndClosesAdmissionWithoutTruncatingInstalledView() {
        var fixture = new Fixture();

        fixture.seed(1, "initial");
        fixture.client.tick();
        fixture.pump();
        assertThat(fixture.client.hasFreshProjection()).isTrue();
        fixture.seed(2,
                     "x".repeat(LIMITS.scopeBytes() + 1));
        fixture.client.tick();
        fixture.deliverRequest();
        var rejected = (WorkerMetadataMessage.Manifest) fixture.responses.remove();

        assertThat(rejected.error()).isEqualTo("projection-unavailable-or-oversize");
        assertThat(fixture.coreRejections).containsExactly("projection-unavailable-or-oversize");
        assertThat(rejected.scopes()).isEmpty();
        fixture.client.onManifest(rejected);
        assertThat(fixture.client.hasFreshProjection()).isFalse();
        assertThat(fixture.errors).contains("metadata manifest rejected or oversized");
        assertThat(fixture.worker.getTyped(fixture.configKey, AetherValue.ConfigValue.class).unwrap().value()).isEqualTo("initial");
        assertThat(fixture.requests).isEmpty();
    }

    @Test
    void unchangedScopeHashesAvoidChunkRetransmission() {
        var fixture = new Fixture();

        fixture.seed(1, "initial");
        fixture.client.tick();
        fixture.pump();
        fixture.client.tick();
        fixture.deliverRequest();
        fixture.deliverResponse();
        assertThat(fixture.requests).isEmpty();
        assertThat(fixture.ready).hasValue(1);
    }

    @Test
    void bandwidthBudgetRefusesEvenAnOtherwiseValidManifest() {
        var constrained = new Fixture(limits(64, 1, 32), LIMITS);
        constrained.seed(1, "initial");
        constrained.client.tick();
        constrained.deliverRequest();
        assertThat(constrained.server.manifestCount()).isEqualTo(1);
        assertThat(constrained.responses).isEmpty();
        assertThat(constrained.client.hasFreshProjection()).isFalse();
        var permitted = new Fixture();
        permitted.seed(1, "initial");
        permitted.client.tick();
        permitted.pump();
        assertThat(permitted.client.hasFreshProjection()).isTrue();
    }

    @Test
    void serverScopeLimitRefusesProjectionBeforeAllocatingManifest() {
        var fixture = new Fixture(limits(64, LIMITS.bytesPerSecond(), 2), LIMITS);
        fixture.seed(1, "initial");
        fixture.client.tick();
        fixture.deliverRequest();
        var response = (WorkerMetadataMessage.Manifest) fixture.responses.remove();
        assertThat(response.error()).isEqualTo("projection-unavailable-or-oversize");
        assertThat(response.scopes()).isEmpty();
        assertThat(fixture.server.manifestCount()).isZero();
        fixture.client.onManifest(response);
        assertThat(fixture.worker.snapshot()).isEmpty();
        assertThat(fixture.client.hasFreshProjection()).isFalse();
    }

    @Test
    void clientScopeLimitRejectsOtherwiseValidManifestWithoutRequestingChunks() {
        var fixture = new Fixture(LIMITS, limits(64, LIMITS.bytesPerSecond(), 2));
        fixture.seed(1, "initial");
        fixture.client.tick();
        fixture.deliverRequest();
        var response = (WorkerMetadataMessage.Manifest) fixture.responses.remove();
        assertThat(response.error()).isEmpty();
        assertThat(response.scopes().size()).isGreaterThan(2);
        fixture.client.onManifest(response);
        assertThat(fixture.requests).isEmpty();
        assertThat(fixture.errors).containsExactly("metadata manifest rejected or oversized");
        assertThat(fixture.worker.snapshot()).isEmpty();
        assertThat(fixture.client.hasFreshProjection()).isFalse();
    }

    @Test
    void oversizedChunkIsRejectedBeforeConsumptionEvenWhenItFitsTheScope() {
        var fixture = new Fixture(LIMITS, limits(1, LIMITS.bytesPerSecond(), 32));
        fixture.seed(1, "initial");
        fixture.client.tick();
        fixture.deliverRequest();
        var manifest = (WorkerMetadataMessage.Manifest) fixture.responses.remove();
        fixture.client.onManifest(manifest);
        fixture.deliverRequest();
        var response = (WorkerMetadataMessage.Chunk) fixture.responses.remove();
        var scope = manifest.scopes().stream().filter(value -> value.scope().equals(response.scope())).findFirst().orElseThrow();
        assertThat(response.error()).isEmpty();
        assertThat(response.bytes().length).isGreaterThan(1).isLessThanOrEqualTo(scope.length());
        fixture.client.onChunk(response);
        assertThat(fixture.errors).containsExactly("invalid metadata chunk size");
        assertThat(fixture.requests).isEmpty();
        assertThat(fixture.worker.snapshot()).isEmpty();
        assertThat(fixture.ready).hasValue(0);
        assertThat(fixture.client.hasFreshProjection()).isFalse();
    }

    private static WorkerMetadataLimits limits(int chunkBytes, long bandwidth, int scopes) {
        return new WorkerMetadataLimits(chunkBytes, LIMITS.scopeBytes(), LIMITS.cacheBytes(), bandwidth,
            LIMITS.manifests(), scopes, LIMITS.manifestTtl(), LIMITS.pollInterval());
    }

    private static final class Fixture {
        final org.pragmatica.serialization.SliceCodec codec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());

        final MessageRouter.MutableRouter router = MessageRouter.mutable();
        final KVStore<AetherKey, AetherValue> core = new KVStore<>(router, codec, codec);
        final KVStore<AetherKey, AetherValue> worker = new KVStore<>(MessageRouter.mutable(), codec, codec);
        final ArrayDeque<ProtocolMessage> requests = new ArrayDeque<>();
        final ArrayDeque<ProtocolMessage> responses = new ArrayDeque<>();
        final AtomicInteger ready = new AtomicInteger();

        final java.util.concurrent.atomic.AtomicBoolean directoryAccepted = new java.util.concurrent.atomic.AtomicBoolean(true);

        final List<String> errors = new ArrayList<>();
        final List<String> coreRejections = new ArrayList<>();
        final AetherKey.ConfigKey configKey = new AetherKey.ConfigKey("test", Option.none());

        final WorkerMetadataServer server;
        final WorkerMetadataClient client;

        Fixture() { this(LIMITS, LIMITS); }

        Fixture(WorkerMetadataLimits serverLimits, WorkerMetadataLimits clientLimits) {
        server = new WorkerMetadataServer(CORE,
                                                                     core,
                                                                     codec,
                                                                     (_, message) -> responses.add(message),
                                                                     WORKER::equals,
                                                                     () -> Set.of(CORE),
                                                                     _ -> List.of(),
                                                                     serverLimits,
                                                                     (_, reason) -> coreRejections.add(reason));

        client = new WorkerMetadataClient(WORKER,
                                                                     worker,
                                                                     codec,
                                                                     () -> Set.of(CORE),
                                                                     CORE::equals,
                                                                     (_, message) -> requests.add(message),
                                                                     _ -> directoryAccepted.get()
                                                                          ? org.pragmatica.lang.Result.success(org.pragmatica.lang.Unit.unit())
                                                                          : org.pragmatica.lang.utils.Causes.cause("invalid core directory")
                                                                                                            .result(),
                                                                     _ -> {},
                                                                     ready::incrementAndGet,
                                                                     errors::add,
                                                                     clientLimits);
        }

        void seed(long revision, String value) {
            var activation = new AetherKey.ActivationDirectiveKey(WORKER);
            var own = AetherValue.ActivationDirectiveValue.worker("ours", "");
            var foreign = new AetherKey.ActivationDirectiveKey(FOREIGN);
            var unrelated = AetherValue.ActivationDirectiveValue.worker("theirs", "");
            var config = new AetherValue.ConfigValue("test", value, revision);
            List<KVCommand<AetherKey>> commands = List.of(new KVCommand.Put<>(activation, own),
                                                          new KVCommand.Put<>(foreign, unrelated),
                                                          new KVCommand.Put<>(configKey, config));

            core.processCommitted(core.createBatch(commands), revision);
            server.put(activation, own);
            server.put(foreign, unrelated);
            server.put(configKey, config);
        }

        void deliverRequest() {
            switch (requests.remove()) {
                case WorkerMetadataMessage.ManifestRequest request -> server.onManifestRequest(request);
                case WorkerMetadataMessage.ChunkRequest request -> server.onChunkRequest(request);
                default -> org.assertj.core.api.Assertions.fail("Unexpected request");
            }
        }

        void deliverResponse() {
            switch (responses.remove()) {
                case WorkerMetadataMessage.Manifest response -> client.onManifest(response);
                case WorkerMetadataMessage.Chunk response -> client.onChunk(response);
                default -> org.assertj.core.api.Assertions.fail("Unexpected response");
            }
        }

        void pump() {
            for (var step = 0; step < 10000 && (!requests.isEmpty() || !responses.isEmpty()); step++) {
                if (!requests.isEmpty()) {
                    deliverRequest();
                }

                if (!responses.isEmpty()) {
                    deliverResponse();
                }
            }

            assertThat(requests).isEmpty();
            assertThat(responses).isEmpty();
        }
    }
}
