package org.pragmatica.aether.slice;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;

class StreamPublisherTest {

    @Nested
    class FunctionalInterfaceContract {

        @Test
        void canBeAssignedAsLambda() {
            StreamPublisher<String> publisher = event -> Promise.success(Unit.unit());

            assertThat(publisher).isNotNull();
        }

        @Test
        void canBeAssignedAsMethodReference() {
            StreamPublisher<String> publisher = StreamPublisherTest::stubPublish;

            assertThat(publisher).isNotNull();
        }

        @Test
        void publish_returnsSuccessPromise() {
            StreamPublisher<String> publisher = event -> Promise.success(Unit.unit());

            var result = publisher.publish("test-event").await();

            assertThat(result.isSuccess()).isTrue();
        }
    }

    private static Promise<Unit> stubPublish(String event) {
        return Promise.success(Unit.unit());
    }
}
