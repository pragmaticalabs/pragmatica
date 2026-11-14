package org.pragmatica.kafka;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Result.all;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Parsed connection and timing settings. Timeouts bound every blocking call the bridge makes:
/// `requestTimeout` bounds one broker request and, for consumer and admin, every blocking API call
/// (`default.api.timeout.ms`); `deliveryTimeout` bounds a produce from `send` to acknowledgement; `maxBlock` bounds
/// the metadata wait inside `send`; `closeTimeout` bounds the graceful phase of a close.
public record KafkaConfig(String bootstrapServers,
                          KafkaSecurity security,
                          Acks acks,
                          String clientId,
                          TimeSpan requestTimeout,
                          TimeSpan deliveryTimeout,
                          TimeSpan maxBlock,
                          TimeSpan closeTimeout) {
    private static final Pattern SERVERS = Pattern.compile("[^\\s:,]+:\\d{1,5}(,[^\\s:,]+:\\d{1,5})*");

    public static Result<KafkaConfig> kafkaConfig(String bootstrapServers, String clientId) {
        return kafkaConfig(bootstrapServers,
                           KafkaSecurity.Plaintext.plaintext(),
                           Acks.ALL,
                           clientId,
                           timeSpan(10).seconds(),
                           timeSpan(30).seconds(),
                           timeSpan(10).seconds(),
                           timeSpan(10).seconds());
    }

    public static Result<KafkaConfig> kafkaConfig(String bootstrapServers,
                                                  KafkaSecurity security,
                                                  Acks acks,
                                                  String clientId,
                                                  TimeSpan requestTimeout,
                                                  TimeSpan deliveryTimeout,
                                                  TimeSpan maxBlock,
                                                  TimeSpan closeTimeout) {
        return all(Verify.ensure(bootstrapServers, Verify.Is::present).flatMap(KafkaConfig::serversShape),
                   Verify.ensure(security, Verify.Is::notNull),
                   Verify.ensure(acks, Verify.Is::notNull),
                   Verify.ensure(clientId, Verify.Is::present),
                   positive(requestTimeout),
                   positive(deliveryTimeout),
                   positive(maxBlock),
                   positive(closeTimeout)).flatMap(KafkaConfig::ordered);
    }

    private static Result<String> serversShape(String servers) {
        return Verify.ensure(servers,
                             v -> SERVERS.matcher(v).matches(),
                             Causes.cause("bootstrap servers must be host:port[,host:port...]"));
    }

    private static Result<TimeSpan> positive(TimeSpan span) {
        return Verify.ensure(span, Verify.Is::notNull).flatMap(s -> Verify.ensure(s,
                                                                                  t -> t.nanos() > 0,
                                                                                  Causes.cause("timeout must be positive")));
    }

    private static Result<KafkaConfig> ordered(String servers,
                                               KafkaSecurity security,
                                               Acks acks,
                                               String clientId,
                                               TimeSpan request,
                                               TimeSpan delivery,
                                               TimeSpan maxBlock,
                                               TimeSpan close) {
        return Verify.ensure(new KafkaConfig(servers, security, acks, clientId, request, delivery, maxBlock, close),
                             c -> c.deliveryTimeout.nanos() >= c.requestTimeout.nanos(),
                             Causes.cause("deliveryTimeout must be at least requestTimeout"));
    }

    Map<String, Object> commonProperties() {
        var props = new HashMap<String, Object>(security.properties());

        props.put("bootstrap.servers", bootstrapServers);
        props.put("client.id", clientId);
        props.put("request.timeout.ms", (int) requestTimeout.millis());

        return props;
    }

    Map<String, Object> producerProperties() {
        var props = commonProperties();

        props.put("acks", acks.wire());
        props.put("delivery.timeout.ms", (int) deliveryTimeout.millis());
        props.put("max.block.ms", maxBlock.millis());
        props.put("key.serializer", BYTES_SERIALIZER);
        props.put("value.serializer", BYTES_SERIALIZER);

        return props;
    }

    /// Manual assignment: `group.id` only names the commit target, `enable.auto.commit` is off, no rebalancing happens.
    Map<String, Object> consumerProperties(String groupId) {
        var props = commonProperties();

        props.put("group.id", groupId);
        props.put("enable.auto.commit", false);
        props.put("default.api.timeout.ms", (int) requestTimeout.millis());
        props.put("key.deserializer", BYTES_DESERIALIZER);
        props.put("value.deserializer", BYTES_DESERIALIZER);

        return props;
    }

    Map<String, Object> adminProperties() {
        var props = commonProperties();

        props.put("default.api.timeout.ms", (int) requestTimeout.millis());

        return props;
    }

    private static final String BYTES_SERIALIZER = "org.apache.kafka.common.serialization.ByteArraySerializer";
    private static final String BYTES_DESERIALIZER = "org.apache.kafka.common.serialization.ByteArrayDeserializer";
}
