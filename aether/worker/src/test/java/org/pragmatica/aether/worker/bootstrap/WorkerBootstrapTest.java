package org.pragmatica.aether.worker.bootstrap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.pragmatica.aether.worker.network.WorkerNetwork;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.pragmatica.lang.Unit.unit;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01"})
class WorkerBootstrapTest {
    private static final NodeId SELF = NodeId.nodeId("worker-1").unwrap();
    private static final NodeId GOVERNOR = NodeId.nodeId("governor-1").unwrap();

    @Mock
    private WorkerNetwork network;

    @Mock
    private KVStore<?, ?> kvStore;

    private WorkerBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        bootstrap = WorkerBootstrap.workerBootstrap(SELF, network, kvStore);
    }

    @Nested
    class InitialState {
        @Test
        void isBootstrapped_initiallyFalse() {
            assertThat(bootstrap.isBootstrapped()).isFalse();
        }

        @Test
        void snapshotSequence_initiallyMinusOne() {
            assertThat(bootstrap.snapshotSequence()).isEqualTo(-1);
        }
    }

    @Nested
    class BootstrapLifecycle {
        @Test
        void markBootstrapped_setsFlag() {
            bootstrap.markBootstrapped();

            assertThat(bootstrap.isBootstrapped()).isTrue();
        }

        @Test
        void requestSnapshot_withSource_sendsRequest() {
            bootstrap.requestSnapshot(Option.some(GOVERNOR));

            var captor = ArgumentCaptor.forClass(Object.class);
            verify(network).send(any(NodeId.class), captor.capture());
            assertThat(captor.getValue()).isInstanceOf(SnapshotRequest.class);
        }

        @Test
        void requestSnapshot_withoutSource_doesNotSend() {
            bootstrap.requestSnapshot(Option.empty());

            verifyNoInteractions(network);
        }

        @Test
        void onSnapshotReceived_appliesSnapshot_marksBootstrapped() {
            when(kvStore.restoreSnapshot(any())).thenReturn(Result.success(unit()));
            var snapshotData = new byte[]{1, 2, 3};
            var response = SnapshotResponse.snapshotResponse(snapshotData, 42);

            var result = bootstrap.onSnapshotReceived(response).await();

            result.onFailure(_ -> fail("Expected success"));
            assertThat(bootstrap.isBootstrapped()).isTrue();
            assertThat(bootstrap.snapshotSequence()).isEqualTo(42);
        }
    }

    @Nested
    class RetryCounter {
        @Test
        void incrementRetry_returnsIncrementingValues() {
            assertThat(bootstrap.incrementRetry()).isEqualTo(1);
            assertThat(bootstrap.incrementRetry()).isEqualTo(2);
            assertThat(bootstrap.incrementRetry()).isEqualTo(3);
        }
    }
}
