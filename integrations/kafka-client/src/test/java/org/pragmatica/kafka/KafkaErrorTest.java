package org.pragmatica.kafka;

import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.DisconnectException;
import org.apache.kafka.common.errors.NotEnoughReplicasAfterAppendException;
import org.apache.kafka.common.errors.NotEnoughReplicasException;
import org.apache.kafka.common.errors.NotLeaderOrFollowerException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.errors.WakeupException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaErrorTest {
    @Test
    void fromSend_metadataTimeout_isRetriable() {
        assertThat(KafkaError.fromSend(new TimeoutException("Failed to update metadata after 1000 ms."))).isInstanceOf(KafkaError.Retriable.class);
        assertThat(KafkaError.fromSend(new TimeoutException("Topic t not present in metadata after 1000 ms."))).isInstanceOf(KafkaError.Retriable.class);
    }

    @Test
    void fromSend_deliveryTimeout_isOutcomeUnknown() {
        assertThat(KafkaError.fromSend(new TimeoutException("Expiring 1 record(s) for t-0: 3000 ms has passed since batch creation")))
            .isInstanceOf(KafkaError.OutcomeUnknown.class);
    }

    @Test
    void fromSend_brokerRejectionBeforeAppend_isRetriable() {
        assertThat(KafkaError.fromSend(new NotEnoughReplicasException("isr"))).isInstanceOf(KafkaError.Retriable.class);
        assertThat(KafkaError.fromSend(new NotLeaderOrFollowerException("leader"))).isInstanceOf(KafkaError.Retriable.class);
        assertThat(KafkaError.fromSend(new UnknownTopicOrPartitionException("topic"))).isInstanceOf(KafkaError.Retriable.class);
    }

    @Test
    void fromSend_otherRetriable_isOutcomeUnknown() {
        assertThat(KafkaError.fromSend(new DisconnectException("gone"))).isInstanceOf(KafkaError.OutcomeUnknown.class);
        assertThat(KafkaError.fromSend(new NotEnoughReplicasAfterAppendException("after"))).isInstanceOf(KafkaError.OutcomeUnknown.class);
    }

    @Test
    void fromSend_forcedClose_isOutcomeUnknown() {
        assertThat(KafkaError.fromSend(new KafkaException("Producer is closed forcefully."))).isInstanceOf(KafkaError.OutcomeUnknown.class);
    }

    @Test
    void fromSend_nonRetriable_isFatal() {
        assertThat(KafkaError.fromSend(new AuthenticationException("bad"))).isInstanceOf(KafkaError.Fatal.class);
        assertThat(KafkaError.fromSend(new RecordTooLargeException("big"))).isInstanceOf(KafkaError.Fatal.class);
        assertThat(KafkaError.fromSend(new KafkaException("other"))).isInstanceOf(KafkaError.Fatal.class);
        assertThat(KafkaError.fromSend(new IllegalStateException("not kafka"))).isInstanceOf(KafkaError.Fatal.class);
    }

    @Test
    void fromOperation_classifiesWakeupRetriableAndFatal() {
        assertThat(KafkaError.fromOperation(new WakeupException())).isSameAs(KafkaError.General.CLOSED);
        assertThat(KafkaError.fromOperation(new TimeoutException("t"))).isInstanceOf(KafkaError.Retriable.class);
        assertThat(KafkaError.fromOperation(new AuthenticationException("a"))).isInstanceOf(KafkaError.Fatal.class);
    }

    @Test
    void classification_flagsMatchTheContract() {
        var origin = new TimeoutException("t");
        var retriable = KafkaError.fromOperation(origin);
        var unknown = KafkaError.fromSend(new DisconnectException("d"));
        var fatal = KafkaError.fromSend(new AuthenticationException("a"));

        assertThat(retriable.isTransient()).isTrue();
        assertThat(retriable.isTerminal()).isFalse();
        assertThat(unknown.isTransient()).isFalse();
        assertThat(unknown.isTerminal()).isFalse();
        assertThat(fatal.isTransient()).isFalse();
        assertThat(fatal.isTerminal()).isTrue();
    }

    @Test
    void wrappedErrors_carryTheOriginatingFailure() {
        var cause = KafkaError.fromSend(new RecordTooLargeException("big"));

        assertThat(cause.source().isPresent()).isTrue();
        assertThat(cause.message()).doesNotContain("big");
    }
}
