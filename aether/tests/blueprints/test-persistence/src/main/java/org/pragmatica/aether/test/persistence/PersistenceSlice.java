package org.pragmatica.aether.test.persistence;

import org.pragmatica.aether.slice.StreamAccess;
import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.aether.test.persistence.KvPersistence.KvRow;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;

import java.util.List;

import static org.pragmatica.lang.Result.success;


@Slice public interface PersistenceSlice {
    record GetRequest(String key) {
        private static final Fn1<Cause, String> KEY_REQUIRED = Causes.forOneValue("Key is required, got: '%s'");

        public static Result<GetRequest> getRequest(String key) {
            return Verify.ensure(key, Verify.Is::notNull, KEY_REQUIRED.apply(key))
                         .filter(k -> KEY_REQUIRED.apply(k), Verify.Is::notBlank)
                         .map(String::trim)
                         .map(GetRequest::new);
        }
    }

    record PutRequest(String key, String value) {
        private static final Fn1<Cause, String> KEY_REQUIRED = Causes.forOneValue("Key is required, got: '%s'");

        private static final Fn1<Cause, String> VALUE_REQUIRED = Causes.forOneValue("Value is required, got: '%s'");

        public static Result<PutRequest> putRequest(String key, String value) {
            var validKey = Verify.ensure(key, Verify.Is::notNull, KEY_REQUIRED.apply(key))
                                 .filter(k -> KEY_REQUIRED.apply(k), Verify.Is::notBlank)
                                 .map(String::trim);
            var validValue = Verify.ensure(value, Verify.Is::notNull, VALUE_REQUIRED.apply(value));
            return Result.all(validKey, validValue).map(PutRequest::new);
        }
    }

    record PublishRequest(String payload) {
        public static Result<PublishRequest> publishRequest(String payload) {
            return success(new PublishRequest(payload == null ? "" : payload));
        }
    }

    record ReadRequest(long fromOffset, int maxEvents) {
        private static final int DEFAULT_MAX = 100;

        public static ReadRequest readRequest(long fromOffset, int maxEvents) {
            return new ReadRequest(Math.max(0, fromOffset), maxEvents <= 0 ? DEFAULT_MAX : maxEvents);
        }
    }

    record KvResponse(String key, String value) {
        public static KvResponse kvResponse(KvRow row) {
            return new KvResponse(row.key(), row.value());
        }
    }

    record KvListResponse(List<KvResponse> entries) {
        public static KvListResponse kvListResponse(List<KvRow> rows) {
            return new KvListResponse(rows.stream().map(KvResponse::kvResponse).toList());
        }
    }

    record PublishResponse(String status) {
        public static PublishResponse published() {
            return new PublishResponse("published");
        }
    }

    record StreamEvent(long offset, long timestamp, String payload) {
        public static <T> StreamEvent fromStreamEvent(StreamAccess.StreamEvent<T> event) {
            return new StreamEvent(event.offset(), event.timestamp(), String.valueOf(event.payload()));
        }
    }

    record ReadResponse(List<StreamEvent> events) {
        public static ReadResponse readResponse(List<StreamEvent> events) {
            return new ReadResponse(events);
        }
    }

    sealed interface PersistenceError extends Cause {
        enum NotFound implements PersistenceError {
            INSTANCE;
            @Override public String message() {
                return "Key not found";
            }
        }
    }

    Promise<KvResponse> get(GetRequest request);
    Promise<Unit> put(PutRequest request);
    Promise<KvListResponse> list();
    Promise<PublishResponse> publish(PublishRequest request);
    Promise<ReadResponse> read(ReadRequest request);

    static PersistenceSlice persistenceSlice(KvPersistence kv,
                                             @EventStreamPublisher StreamPublisher<String> publisher,
                                             @EventStreamReader StreamAccess<String> streamAccess) {
        return new persistenceSlice(kv, publisher, streamAccess);
    }

    record persistenceSlice(KvPersistence kv, StreamPublisher<String> publisher, StreamAccess<String> streamAccess) implements PersistenceSlice {
        @Override public Promise<KvResponse> get(GetRequest request) {
            return kv.findByKey(request.key())
                     .flatMap(opt -> opt.map(KvResponse::kvResponse)
                                        .async(PersistenceError.NotFound.INSTANCE));
        }

        @Override public Promise<Unit> put(PutRequest request) {
            return kv.upsert(request.key(), request.value());
        }

        @Override public Promise<KvListResponse> list() {
            return kv.listAll().map(KvListResponse::kvListResponse);
        }

        @Override public Promise<PublishResponse> publish(PublishRequest request) {
            return publisher.publish(request.payload()).map(_ -> PublishResponse.published());
        }

        @Override public Promise<ReadResponse> read(ReadRequest request) {
            return streamAccess.fetch(request.fromOffset(), request.maxEvents())
                               .map(events -> events.stream().map(StreamEvent::<String>fromStreamEvent).toList())
                               .map(ReadResponse::readResponse);
        }
    }
}
