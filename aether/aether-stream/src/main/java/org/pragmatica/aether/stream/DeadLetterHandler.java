package org.pragmatica.aether.stream;

import org.pragmatica.lang.Contract;

import java.util.Arrays;
import java.util.List;


/// Handles dead-letter storage for events that failed processing.
///
/// When RETRY exhausts retries or SKIP drops an event, the failed event is recorded
/// here with metadata for later inspection or reprocessing.
public interface DeadLetterHandler {
    @Contract void record(String streamName,
                          int partition,
                          long offset,
                          byte[] payload,
                          String errorMessage,
                          int attemptCount);
    List<DeadLetterEntry> read(String streamName, int maxCount);

    record DeadLetterEntry(String streamName,
                           int partition,
                           long offset,
                           byte[] payload,
                           String errorMessage,
                           int attemptCount,
                           long timestamp) {
        public DeadLetterEntry {
            payload = payload.clone();
        }

        @Override public byte[] payload() {
            return payload.clone();
        }

        @Override public boolean equals(Object o) {
            return o instanceof DeadLetterEntry other && partition == other.partition && offset == other.offset && attemptCount == other.attemptCount && timestamp == other.timestamp && streamName.equals(other.streamName) && Arrays.equals(payload,
                                                                                                                                                                                                                                              other.payload) && errorMessage.equals(other.errorMessage);
        }

        @Override public int hashCode() {
            int result = streamName.hashCode();
            result = 31 * result + partition;
            result = 31 * result + Long.hashCode(offset);
            result = 31 * result + Arrays.hashCode(payload);
            result = 31 * result + errorMessage.hashCode();
            result = 31 * result + attemptCount;
            result = 31 * result + Long.hashCode(timestamp);
            return result;
        }

        public static DeadLetterEntry deadLetterEntry(String streamName,
                                                      int partition,
                                                      long offset,
                                                      byte[] payload,
                                                      String errorMessage,
                                                      int attemptCount,
                                                      long timestamp) {
            return new DeadLetterEntry(streamName, partition, offset, payload, errorMessage, attemptCount, timestamp);
        }
    }

    static DeadLetterHandler deadLetterHandler() {
        return new InMemoryDeadLetterHandler();
    }
}
