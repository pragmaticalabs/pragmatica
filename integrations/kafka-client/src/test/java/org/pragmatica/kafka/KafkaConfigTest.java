package org.pragmatica.kafka;

import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.assertj.core.api.Assertions.assertThat;


class KafkaConfigTest {
    @Test
    void kafkaConfig_validServers_parses() {
        assertThat(KafkaConfig.kafkaConfig("a:9092,b:9093", "client").isSuccess()).isTrue();
    }

    @Test
    void kafkaConfig_invalidInputs_fail() {
        assertThat(KafkaConfig.kafkaConfig("", "c").isFailure()).isTrue();
        assertThat(KafkaConfig.kafkaConfig("nohostport", "c").isFailure()).isTrue();
        assertThat(KafkaConfig.kafkaConfig("a:9092", " ").isFailure()).isTrue();
    }

    @Test
    void kafkaConfig_deliveryShorterThanRequest_fails() {
        var result = KafkaConfig.kafkaConfig("a:9092",
                                             KafkaSecurity.Plaintext.plaintext(),
                                             Acks.ALL,
                                             "c",
                                             timeSpan(10).seconds(),
                                             timeSpan(5).seconds(),
                                             timeSpan(1).seconds(),
                                             timeSpan(1).seconds());

        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void kafkaConfig_nonPositiveTimeout_fails() {
        var result = KafkaConfig.kafkaConfig("a:9092",
                                             KafkaSecurity.Plaintext.plaintext(),
                                             Acks.ALL,
                                             "c",
                                             timeSpan(0).seconds(),
                                             timeSpan(5).seconds(),
                                             timeSpan(1).seconds(),
                                             timeSpan(1).seconds());

        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void producerProperties_inFlightIsOneUnlessIdempotent() {
        assertThat(inFlight(Acks.NONE)).isEqualTo(1);
        assertThat(inFlight(Acks.LEADER)).isEqualTo(1);
        assertThat(inFlight(Acks.ALL)).isEqualTo(5);
    }

    private static Object inFlight(Acks acks) {
        return KafkaConfig.kafkaConfig("a:9092",
                                       KafkaSecurity.Plaintext.plaintext(),
                                       acks,
                                       "c",
                                       timeSpan(10).seconds(),
                                       timeSpan(30).seconds(),
                                       timeSpan(10).seconds(),
                                       timeSpan(10).seconds())
                          .unwrap()
                          .producerProperties()
                          .get("max.in.flight.requests.per.connection");
    }

    @Test
    void producerProperties_mapAcksAndTimeouts() {
        var props = KafkaConfig.kafkaConfig("a:9092", "c").unwrap().producerProperties();

        assertThat(props).containsEntry("acks", "all")
                  .containsEntry("bootstrap.servers", "a:9092")
                  .containsEntry("client.id", "c")
                  .containsEntry("delivery.timeout.ms", 30_000);
    }

    @Test
    void consumerProperties_disableAutoCommit() {
        var props = KafkaConfig.kafkaConfig("a:9092", "c").unwrap().consumerProperties("g");

        assertThat(props).containsEntry("group.id", "g").containsEntry("enable.auto.commit", false);
    }

    @Test
    void security_saslSsl_rendersJaasAndRedactsSecrets() {
        var sasl = KafkaSecurity.Sasl.sasl(KafkaSecurity.Mechanism.SCRAM_SHA_256, "user", "s3cret").unwrap();
        var ssl = KafkaSecurity.Ssl.ssl(Option.some("/ts.jks"),
                                        Option.some("tp"),
                                        Option.none(),
                                        Option.none())
                                   .unwrap();
        var props = KafkaSecurity.SaslSsl.saslSsl(ssl, sasl).properties();

        assertThat(props).containsEntry("security.protocol", "SASL_SSL")
                  .containsEntry("sasl.mechanism", "SCRAM-SHA-256")
                  .containsEntry("ssl.truststore.location", "/ts.jks");
        assertThat(props.get("sasl.jaas.config").toString()).contains("ScramLoginModule").contains("s3cret");
        assertThat(sasl.toString()).doesNotContain("s3cret");
        assertThat(ssl.toString()).doesNotContain("tp");
    }

    @Test
    void sasl_credentialWithQuote_fails() {
        assertThat(KafkaSecurity.Sasl.sasl(KafkaSecurity.Mechanism.PLAIN, "us\"er", "p").isFailure()).isTrue();
    }
}
