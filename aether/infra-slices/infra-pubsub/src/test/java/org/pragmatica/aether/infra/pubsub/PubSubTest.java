package org.pragmatica.aether.infra.pubsub;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PubSubTest {
    private PubSub pubSub;

    @BeforeEach
    void setUp() {
        pubSub = PubSub.inMemory();
    }

    @Test
    void createTopic_succeeds() {
        pubSub.createTopic("test-topic")
              .await()
              .onFailureRun(Assertions::fail);

        pubSub.listTopics()
              .await()
              .onFailureRun(Assertions::fail)
              .onSuccess(topics -> assertThat(topics).contains("test-topic"));
    }

    @Test
    void createTopic_fails_when_already_exists() {
        pubSub.createTopic("test-topic").await();

        pubSub.createTopic("test-topic")
              .await()
              .onSuccessRun(Assertions::fail)
              .onFailure(cause -> assertThat(cause).isInstanceOf(PubSubError.TopicAlreadyExists.class));
    }

    @Test
    void deleteTopic_succeeds() {
        pubSub.createTopic("test-topic").await();

        pubSub.deleteTopic("test-topic")
              .await()
              .onFailureRun(Assertions::fail);

        pubSub.listTopics()
              .await()
              .onSuccess(topics -> assertThat(topics).doesNotContain("test-topic"));
    }

    @Test
    void deleteTopic_fails_when_not_found() {
        pubSub.deleteTopic("nonexistent")
              .await()
              .onSuccessRun(Assertions::fail)
              .onFailure(cause -> assertThat(cause).isInstanceOf(PubSubError.TopicNotFound.class));
    }

    @Test
    void publish_fails_when_topic_not_found() {
        pubSub.publish("nonexistent", Message.message("test"))
              .await()
              .onSuccessRun(Assertions::fail)
              .onFailure(cause -> assertThat(cause).isInstanceOf(PubSubError.TopicNotFound.class));
    }

    @Test
    void subscribe_fails_when_topic_not_found() {
        pubSub.subscribe("nonexistent", msg -> Promise.unitPromise())
              .await()
              .onSuccessRun(Assertions::fail)
              .onFailure(cause -> assertThat(cause).isInstanceOf(PubSubError.TopicNotFound.class));
    }

    @Test
    void publish_delivers_to_subscriber() throws InterruptedException {
        var latch = new CountDownLatch(1);
        var received = new CopyOnWriteArrayList<Message>();

        pubSub.createTopic("test-topic").await();

        pubSub.subscribe("test-topic", msg -> {
                  received.add(msg);
                  latch.countDown();
                  return Promise.unitPromise();
              })
              .await()
              .onFailureRun(Assertions::fail);

        var message = Message.message("hello");
        pubSub.publish("test-topic", message).await();

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(1);
        assertThat(received.get(0).payloadAsString()).isEqualTo("hello");
    }

    @Test
    void publish_delivers_to_multiple_subscribers() throws InterruptedException {
        var latch = new CountDownLatch(2);
        var received1 = new CopyOnWriteArrayList<Message>();
        var received2 = new CopyOnWriteArrayList<Message>();

        pubSub.createTopic("test-topic").await();

        pubSub.subscribe("test-topic", msg -> {
                  received1.add(msg);
                  latch.countDown();
                  return Promise.unitPromise();
              })
              .await();

        pubSub.subscribe("test-topic", msg -> {
                  received2.add(msg);
                  latch.countDown();
                  return Promise.unitPromise();
              })
              .await();

        pubSub.publish("test-topic", Message.message("hello")).await();

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(received1).hasSize(1);
        assertThat(received2).hasSize(1);
    }

    @Test
    void unsubscribe_stops_delivery() throws InterruptedException {
        var received = new CopyOnWriteArrayList<Message>();

        pubSub.createTopic("test-topic").await();

        var subscription = pubSub.subscribe("test-topic", msg -> {
                                      received.add(msg);
                                      return Promise.unitPromise();
                                  })
                                  .await()
                                  .unwrap();

        pubSub.unsubscribe(subscription).await();

        pubSub.publish("test-topic", Message.message("hello")).await();

        Thread.sleep(100);
        assertThat(received).isEmpty();
    }

    @Test
    void pause_stops_delivery() throws InterruptedException {
        var received = new CopyOnWriteArrayList<Message>();

        pubSub.createTopic("test-topic").await();

        var subscription = pubSub.subscribe("test-topic", msg -> {
                                      received.add(msg);
                                      return Promise.unitPromise();
                                  })
                                  .await()
                                  .unwrap();

        subscription.pause().await();

        pubSub.publish("test-topic", Message.message("hello")).await();

        Thread.sleep(100);
        assertThat(received).isEmpty();
    }

    @Test
    void resume_restarts_delivery() throws InterruptedException {
        var latch = new CountDownLatch(1);
        var received = new CopyOnWriteArrayList<Message>();

        pubSub.createTopic("test-topic").await();

        var subscription = pubSub.subscribe("test-topic", msg -> {
                                      received.add(msg);
                                      latch.countDown();
                                      return Promise.unitPromise();
                                  })
                                  .await()
                                  .unwrap();

        subscription.pause().await();
        subscription.resume().await();

        pubSub.publish("test-topic", Message.message("hello")).await();

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(1);
    }

    @Test
    void message_factory_methods_work() {
        var msg1 = Message.message("test");
        assertThat(msg1.payloadAsString()).isEqualTo("test");
        assertThat(msg1.id()).isNotNull();
        assertThat(msg1.headers()).isEmpty();

        var msg2 = Message.message("test".getBytes(), java.util.Map.of("key", "value"));
        assertThat(msg2.headers()).containsEntry("key", "value");
    }
}
