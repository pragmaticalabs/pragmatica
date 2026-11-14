package org.pragmatica.kafka;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import org.pragmatica.lang.Cause;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.MockAdminClient;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;


class KafkaAdminTest {
    private static final KafkaConfig CONFIG = KafkaConfig.kafkaConfig("a:9092", "c").unwrap();
    private static final Node N1 = new Node(1, "h", 1);
    private static final Node N2 = new Node(2, "h", 2);

    /// Admin handle whose `describeTopics` answers from a supplier (a result, or a synchronous throw) and which records
    /// every `close(timeout)`; every other call goes to a real mock admin client.
    private static final class StubAdmin implements InvocationHandler {
        private final Admin delegate = new MockAdminClient.Builder().numBrokers(1).build();
        private final Supplier<DescribeTopicsResult> described;
        final List<Duration> closed = new CopyOnWriteArrayList<>();

        StubAdmin(Supplier<DescribeTopicsResult> described) {
            this.described = described;
        }

        Admin admin() {
            return (Admin) Proxy.newProxyInstance(Admin.class.getClassLoader(), new Class<?>[]{Admin.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "describeTopics" -> described.get();
                case "close" -> recordClose(args);
                default -> invokeOnDelegate(method, args);
            };
        }

        private Object recordClose(Object[] args) {
            if (args != null && args.length == 1) {
                closed.add((Duration) args[0]);
            }

            return null;
        }

        private Object invokeOnDelegate(Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    private static DescribeTopicsResult resultOf(KafkaFuture<Map<String, TopicDescription>> all) {
        return new DescribeTopicsResult(Map.of(), Map.of()) {
            @Override
            public KafkaFuture<Map<String, TopicDescription>> allTopicNames() {
                return all;
            }
        };
    }

    private static DescribeTopicsResult refused() {
        throw new TimeoutException("sync");
    }

    private static KafkaFuture<Map<String, TopicDescription>> failedWith(Throwable failure) {
        var future = new KafkaFutureImpl<Map<String, TopicDescription>>();

        future.completeExceptionally(failure);

        return future;
    }

    private static KafkaFuture<Map<String, TopicDescription>> describing(TopicDescription description) {
        var future = new KafkaFutureImpl<Map<String, TopicDescription>>();

        future.complete(Map.of(description.name(), description));

        return future;
    }

    private static Cause failureOf(KafkaAdmin admin) {
        return admin.describeTopic("t").await().<Cause> fold(c -> c, _ -> fail("expected a failure"));
    }

    @Test
    void describeTopic_unknownTopic_failsRetriableNotFatal() {
        var admin = KafkaAdmin.kafkaAdmin(new MockAdminClient.Builder().numBrokers(1).build(), CONFIG);
        var cause = admin.describeTopic("missing").await().<Cause> fold(c -> c, _ -> fail("expected a failure"));

        assertThat(cause).isInstanceOf(KafkaError.Retriable.class);
    }

    @Test
    void describeTopic_failureWrappedInExecutionException_isUnwrappedBeforeClassification() {
        var stub = new StubAdmin(() -> resultOf(failedWith(new ExecutionException(new TimeoutException("slow")))));
        var admin = KafkaAdmin.kafkaAdmin(stub.admin(), CONFIG);

        assertThat(failureOf(admin)).isInstanceOf(KafkaError.Retriable.class);
    }

    @Test
    void describeTopic_synchronousRetriableThrow_failsRetriable() {
        var stub = new StubAdmin(KafkaAdminTest::refused);
        var admin = KafkaAdmin.kafkaAdmin(stub.admin(), CONFIG);

        assertThat(failureOf(admin)).isInstanceOf(KafkaError.Retriable.class);
    }

    @Test
    void describeTopic_leaderlessPartition_layoutCarriesMinusOneLeaderAndReplicas() {
        var description = new TopicDescription("t",
                                               false,
                                               List.of(new TopicPartitionInfo(0, null, List.of(N1, N2), List.of(N1)),
                                                       new TopicPartitionInfo(1, N2, List.of(N2), List.of(N2))));
        var stub = new StubAdmin(() -> resultOf(describing(description)));
        var layout = KafkaAdmin.kafkaAdmin(stub.admin(), CONFIG).describeTopic("t").await().unwrap();

        assertThat(layout.partitions()).hasSize(2);
        assertThat(layout.partitions().get(0).leader()).isEqualTo(-1);
        assertThat(layout.partitions().get(0).replicas()).containsExactly(1, 2);
        assertThat(layout.partitions().get(1).leader()).isEqualTo(2);
        assertThat(layout.partitions().get(1).replicas()).containsExactly(2);
    }

    @Test
    void close_closesTheUnderlyingAdminWithTheCloseTimeout() {
        var stub = new StubAdmin(() -> resultOf(failedWith(new TimeoutException("unused"))));
        var admin = KafkaAdmin.kafkaAdmin(stub.admin(), CONFIG);

        assertThat(admin.close().await().isSuccess()).isTrue();
        assertThat(stub.closed).containsExactly(Duration.ofSeconds(10));
    }

    @Test
    void describeTopic_afterClose_failsClosedWithoutCallingTheAdmin() {
        var description = new TopicDescription("t", false, List.of(new TopicPartitionInfo(0, N1, List.of(N1), List.of(N1))));
        var stub = new StubAdmin(() -> resultOf(describing(description)));
        var admin = KafkaAdmin.kafkaAdmin(stub.admin(), CONFIG);

        admin.close().await();
        assertThat(failureOf(admin)).isSameAs(KafkaError.General.CLOSED);
    }
}
