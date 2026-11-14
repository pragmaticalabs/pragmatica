package org.pragmatica.kafka;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.utils.Causes;

import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.WakeupException;


/// Typed failures of the Kafka bridge. Three classes matter to a caller, and the classes are the contract:
///
/// - [Retriable]: the operation was rejected or never handed to the network (a producer metadata wait that timed out, a
///   full submission queue, a consumer or admin call refused by a transient error); repeating it cannot duplicate an
///   effect. A produce error that comes back from the broker is never classified here: the producer retries internally
///   (`retries` is unbounded, bounded by `deliveryTimeout`), so earlier attempts may have been appended even when the
///   final error is a pre-append one such as not-enough-replicas, not-leader or unknown-topic.
/// - [OutcomeUnknown]: a produce was handed to the network and the outcome was lost (delivery timeout, disconnect,
///   forced close). The record may or may not be in the log. A retry may duplicate it; an idempotent producer
///   (acks=all) removes duplicates only within one producer session.
/// - [Fatal]: no retry can change the result (authentication, authorization, oversize record, bad input, ...).
///
/// [General] holds the fixed-text failures. `kafka-clients` exception types never escape: they live only in `origin`.
public sealed interface KafkaError extends Cause {
    /// Metadata-wait timeouts in the producer carry these message prefixes; they happen before any batch exists,
    /// so nothing reached the network. A miss here errs toward [OutcomeUnknown], the safe direction.
    String METADATA_FAILED = "Failed to update metadata after";
    String METADATA_MISSING = "Topic";
    String FORCED_CLOSE = "Producer is closed forcefully";
    /// A `send` blocked in the metadata wait when the producer closed: it never reached the network.
    String CLOSED_DURING_SEND = "Producer closed while send in progress";

    enum General implements KafkaError {
        CLOSED("Kafka client is closed"),
        THREAD_CONFINEMENT_VIOLATED("Kafka consumer touched off its owning thread"),
        SUBMISSION_QUEUE_FULL("Kafka client submission queue is full");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }

    /// Rejected or never handed to the network; safe to repeat.
    @SuppressWarnings("JBCT-CAUSE-04")
    record Retriable(Cause origin, String message) implements KafkaError, Cause.Wrapped {
        static final Fn1<Retriable, Cause> FACTORY = Causes.forOneValue("Kafka operation failed, retriable",
                                                                        Retriable::new);

        @Override
        public boolean isTransient() {
            return true;
        }

        @Override
        public boolean isTerminal() {
            return false;
        }
    }

    /// Produce handed to the network, outcome lost. Neither transient nor terminal: only the caller knows whether a
    /// duplicate is acceptable.
    @SuppressWarnings("JBCT-CAUSE-04")
    record OutcomeUnknown(Cause origin, String message) implements KafkaError, Cause.Wrapped {
        static final Fn1<OutcomeUnknown, Cause> FACTORY = Causes.forOneValue("Kafka produce outcome unknown",
                                                                             OutcomeUnknown::new);

        @Override
        public boolean isTransient() {
            return false;
        }

        @Override
        public boolean isTerminal() {
            return false;
        }
    }

    /// No retry can change the result.
    @SuppressWarnings("JBCT-CAUSE-04")
    record Fatal(Cause origin, String message) implements KafkaError, Cause.Wrapped, Cause.Terminal {
        static final Fn1<Fatal, Cause> FACTORY = Causes.forOneValue("Kafka operation failed, fatal", Fatal::new);

        @Override
        public boolean isTransient() {
            return false;
        }

        @Override
        public boolean isTerminal() {
            return true;
        }
    }

    /// Classifies a failure of a produce (`send` call or its callback).
    static KafkaError fromSend(Throwable failure) {
        return switch (failure) {
            case TimeoutException t when isMetadataTimeout(t) -> Retriable.FACTORY.apply(Causes.fromThrowable(t));
            case RetriableException t -> OutcomeUnknown.FACTORY.apply(Causes.fromThrowable(t));
            case KafkaException t when isForcedClose(t) -> OutcomeUnknown.FACTORY.apply(Causes.fromThrowable(t));
            default -> Fatal.FACTORY.apply(Causes.fromThrowable(failure));
        };
    }

    /// Classifies a failure of a `send` call that ran while the client was closing. The producer then refuses or aborts
    /// the call before any network hand-off (`IllegalStateException`, or "closed while send in progress"), which is
    /// [General#CLOSED], not [Fatal]: the record was not sent, and no state of the record makes a resend wrong.
    static KafkaError fromSendWhileClosing(Throwable failure) {
        return isCloseInduced(failure)
               ? General.CLOSED
               : fromSend(failure);
    }

    /// Classifies a failure of a consumer or admin operation (no record is in flight, so "retriable" is safe).
    static KafkaError fromOperation(Throwable failure) {
        return switch (failure) {
            case WakeupException _ -> General.CLOSED;
            case RetriableException t -> Retriable.FACTORY.apply(Causes.fromThrowable(t));
            default -> Fatal.FACTORY.apply(Causes.fromThrowable(failure));
        };
    }

    private static boolean isMetadataTimeout(TimeoutException failure) {
        var text = String.valueOf(failure.getMessage());

        return text.startsWith(METADATA_FAILED) || text.startsWith(METADATA_MISSING) && text.contains("not present in metadata");
    }

    private static boolean isCloseInduced(Throwable failure) {
        return failure instanceof IllegalStateException || failure instanceof KafkaException && String.valueOf(failure.getMessage()).startsWith(CLOSED_DURING_SEND);
    }

    private static boolean isForcedClose(KafkaException failure) {
        return String.valueOf(failure.getMessage()).startsWith(FORCED_CLOSE);
    }
}
