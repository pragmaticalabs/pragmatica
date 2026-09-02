// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.test.streamrepl;

import java.util.List;

import org.pragmatica.aether.slice.StreamAccess;
import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


/// Minimal stream-only slice (no database) for the 02-chaos replica-failover test.
///
/// Injects a [StreamPublisher] and [StreamAccess] both qualified to the `streams.repl-failover-events`
/// resource (declared in `resources.toml` with partitions=1, min-sync-replicas=2 and count-based
/// retention so a slow consumer is never evicted). The RF=2 / synchronous-replication config makes
/// each publish AWAIT a replica ack, so the log survives loss of the primary replica. Exposes two
/// app-HTTP routes:
///
///   - `POST /api/stream-repl/publish` — append one payload to the log (partition 0).
///   - `POST /api/stream-repl/read`    — fetch events from a caller-supplied offset (log/Kafka fan-out:
///     the server keeps NO per-consumer state; every consumer reads from its own offset).
@Slice
public interface StreamSlice {
    record PublishRequest(String payload) {
        public static Result<PublishRequest> publishRequest(String payload) {
            return success(new PublishRequest(Option.option(payload).or("")));
        }
    }

    record ReadRequest(long fromOffset, int maxEvents) {
        private static final int DEFAULT_MAX = 100;

        public static ReadRequest readRequest(long fromOffset, int maxEvents) {
            return new ReadRequest(Math.max(0, fromOffset),
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

    record StreamEvent(long offset, String payload) {
        public static <T> StreamEvent fromStreamEvent(StreamAccess.StreamEvent<T> event) {
            return new StreamEvent(event.offset(),
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
            return streamAccess.fetch(request.fromOffset(),
                                      request.maxEvents())
                               .map(events -> events.stream()
                                                    .map(StreamEvent::<String> fromStreamEvent)
                                                    .toList())
                               .map(ReadResponse::readResponse);
        }
    }
}
