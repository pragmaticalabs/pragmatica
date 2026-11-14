# Kafka Client Bridge

`Promise` bridge over `kafka-clients` 4.1.2 (#2099): `KafkaProducerClient`, `KafkaConsumerClient` (manual assignment, no group membership) and `KafkaAdmin`, with typed errors (`KafkaError`). No `kafka-clients` type appears in the public API. A native client is planned to replace the wrap (#3226).

## Guarantees per operation

- `send`: success means the broker acknowledged the append for `acks=LEADER|ALL`; with `acks=NONE` it means only that the record was handed to the producer (receipt offset -1). `Retriable` = never sent (metadata wait timed out, submitter queue full): a resend is safe. `OutcomeUnknown` = the record may be in the log, including every error the broker returns for a send, because the producer retries internally until `deliveryTimeout` and an earlier attempt may have been appended. `CLOSED` = the client was closing and the record was not sent.
- Order: records sent to one partition are appended in call order within one producer session (`ALL`: idempotent producer; `NONE`/`LEADER`: one request in flight per connection).
- Back-pressure: each client queues work for one owned thread; the queue is bounded (10,000, a marked guess). A submission past the bound fails at once as `Retriable` (origin `SUBMISSION_QUEUE_FULL`); it never blocks the caller and is never dropped silently.
- Consumer: every call runs on one owned thread. `close` wakes the running `poll`; calls queued behind it fail as `CLOSED` without running. Close ends within the remaining time of one running non-poll call (at most `requestTimeout`) plus `closeTimeout`.
- Admin: failures are `Retriable` (retriable Kafka errors) or `Fatal`; an existing topic on `createTopic` is `Fatal`.
- Consumer start position without a committed offset is `auto.offset.reset` = `latest` (Kafka default); `seek` or `seekToBeginning` explicitly.

## Dependency footprint

Runtime scope, about 20 MB: `kafka-clients` 9.8 MB plus its runtime-only codec libraries `zstd-jni` 1.5.6-10 (7.3 MB, native), `snappy-java` 1.1.10.7 (2.3 MB), `lz4-java` 1.10.1 (0.9 MB). The codec dependencies are kept (marked guess: topics of unknown compression just work). Codecs load lazily: a producer with `compression.type=none` never loads them; a consumer needs the codec of whatever the topic's batches were written with.

To opt out when every topic you read is uncompressed or uses a codec you keep, exclude them where you depend on this module (shown for Maven; leaving out a codec a topic uses fails the fetch with `NoClassDefFoundError`):

```xml
<dependency>
    <groupId>org.pragmatica-lite</groupId>
    <artifactId>kafka-client</artifactId>
    <exclusions>
        <exclusion><groupId>com.github.luben</groupId><artifactId>zstd-jni</artifactId></exclusion>
        <exclusion><groupId>org.xerial.snappy</groupId><artifactId>snappy-java</artifactId></exclusion>
        <exclusion><groupId>at.yawk.lz4</groupId><artifactId>lz4-java</artifactId></exclusion>
    </exclusions>
</dependency>
```

Exclusions on this module apply to its transitive `kafka-clients` dependencies. Verify the artifact id and the lz4 group id against `mvn dependency:tree` before relying on the snippet.
