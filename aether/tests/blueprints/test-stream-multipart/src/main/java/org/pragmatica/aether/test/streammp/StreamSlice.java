// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.test.streammp;

import java.util.List;

import org.pragmatica.aether.slice.StreamAccess;
import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


/// Multi-partition stream-only slice (no database) for the #429 multi-partition e2e fixture and the
/// #430 publish-under-reshuffle chaos test.
///
/// Injects a [StreamPublisher] and [StreamAccess] both qualified to the `streams.multipart-events`
/// resource (declared in `resources.toml` with partitions=4, replicas=2, min-sync-replicas=2 and
/// count-based retention so a slow consumer is never evicted). The RF=2 / synchronous-replication
/// config makes each publish AWAIT a replica ack, so an ACKED write is on >=2 nodes and survives loss
/// of one owner. Exposes two app-HTTP routes:
///
///   - `POST /api/stream-mp/publish` — append one payload; a keyless publish round-robins across the
///     four partitions ([org.pragmatica.aether.stream.DefaultStreamPublisher] `resolvePartition`), so
///     driving all publishes through ONE app port spreads events deterministically over every partition.
///   - `POST /api/stream-mp/read`    — fetch events from a caller-supplied `(partition, offset)` (the
///     partition-aware [StreamAccess#fetch(int, long, int)] overload). The server keeps NO per-consumer
///     state; every consumer reads from its own offset (log/Kafka fan-out).
@Slice
public interface StreamSlice {
    record PublishRequest(String payload) {
        public static Result<PublishRequest> publishRequest(String payload) {
            return success(new PublishRequest(Option.option(payload).or("")));
        }
    }

    record ReadRequest(int partition, long fromOffset, int maxEvents) {
        private static final int DEFAULT_MAX = 100;

        public static ReadRequest readRequest(int partition, long fromOffset, int maxEvents) {
            return new ReadRequest(Math.max(0, partition),
                                   Math.max(0, fromOffset),
                                   maxEvents <= 0
                                   ? DEFAULT_MAX
                                   : maxEvents);
        }
    }

    record PublishResponse(String status) {
        public static PublishResponse published() {
            return new PublishResponse("published");
        }
    }

    record StreamEvent(long offset, int partition, String payload) {
        public static <T> StreamEvent fromStreamEvent(StreamAccess.StreamEvent<T> event) {
            return new StreamEvent(event.offset(),
                                   event.partition(),
                                   String.valueOf(event.payload()));
        }
    }

    record ReadResponse(List<StreamEvent> events) {
        public static ReadResponse readResponse(List<StreamEvent> events) {
            return new ReadResponse(events);
        }
    }

    Promise<PublishResponse> publish(PublishRequest request);
    Promise<ReadResponse> read(ReadRequest request);

    static StreamSlice streamSlice(@EventStreamPublisher StreamPublisher<String> publisher,
                                   @EventStreamReader StreamAccess<String> streamAccess) {
        return new streamSlice(publisher, streamAccess);
    }

    record streamSlice(StreamPublisher<String> publisher, StreamAccess<String> streamAccess) implements StreamSlice {
        @Override
        public Promise<PublishResponse> publish(PublishRequest request) {
            return publisher.publish(request.payload())
                            .map(_ -> PublishResponse.published());
        }

        @Override
        public Promise<ReadResponse> read(ReadRequest request) {
            return streamAccess.fetch(request.partition(),
                                      request.fromOffset(),
                                      request.maxEvents())
                               .map(events -> events.stream()
                                                    .map(StreamEvent::<String> fromStreamEvent)
                                                    .toList())
                               .map(ReadResponse::readResponse);
        }
    }
}
