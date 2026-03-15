package org.pragmatica.aether.worker.bootstrap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.messaging.MessageRouter.DelegateRouter;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.pragmatica.lang.Unit.unit;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01"})
class WorkerBootstrapTest {
    private static final NodeId SELF = NodeId.nodeId("worker-1").unwrap();
    private static final NodeId GOVERNOR = NodeId.nodeId("governor-1").unwrap();

    private DelegateRouter delegateRouter;
    private List<Message> routedMessages;

    @Mock
    private KVStore<?, ?> kvStore;

    private WorkerBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        routedMessages = new ArrayList<>();
        delegateRouter = DelegateRouter.delegate();
        var mutableRouter = MessageRouter.mutable();
        mutableRouter.addRoute(NetworkServiceMessage.Send.class, routedMessages::add);
        delegateRouter.replaceDelegate(mutableRouter);
        bootstrap = WorkerBootstrap.workerBootstrap(SELF, delegateRouter, kvStore);
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
        void requestSnapshot_withSource_sendsSendMessage() {
            bootstrap.requestSnapshot(Option.some(GOVERNOR));

            assertThat(routedMessages).hasSize(1);
            assertThat(routedMessages.getFirst()).isInstanceOf(NetworkServiceMessage.Send.class);
            var send = (NetworkServiceMessage.Send) routedMessages.getFirst();
            assertThat(send.target()).isEqualTo(GOVERNOR);
            assertThat(send.payload()).isInstanceOf(SnapshotRequest.class);
        }

        @Test
        void requestSnapshot_withoutSource_doesNotSend() {
            bootstrap.requestSnapshot(Option.empty());

            assertThat(routedMessages).isEmpty();
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
