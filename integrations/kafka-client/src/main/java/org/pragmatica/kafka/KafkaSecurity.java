package org.pragmatica.kafka;

import java.util.HashMap;
import java.util.Map;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Result.all;


/// Transport security of a Kafka connection: PLAINTEXT, SSL, SASL_PLAINTEXT or SASL_SSL. Secrets never print:
/// every record carrying a password redacts it in `toString`.
public sealed interface KafkaSecurity {
    /// Kafka client properties for this security mode.
    Map<String, Object> properties();

    enum Mechanism {
        PLAIN("PLAIN", "org.apache.kafka.common.security.plain.PlainLoginModule"),
        SCRAM_SHA_256("SCRAM-SHA-256", "org.apache.kafka.common.security.scram.ScramLoginModule"),
        SCRAM_SHA_512("SCRAM-SHA-512", "org.apache.kafka.common.security.scram.ScramLoginModule");
        private final String wire;
        private final String loginModule;
        Mechanism(String wire, String loginModule) {
            this.wire = wire;
            this.loginModule = loginModule;
        }
    }

    record Plaintext() implements KafkaSecurity {
        public static Plaintext plaintext() {
            return new Plaintext();
        }

        @Override
        public Map<String, Object> properties() {
            return Map.of("security.protocol", "PLAINTEXT");
        }
    }

    /// TLS. Without a trust store the JVM default trust is used; a key store enables mutual TLS.
    record Ssl(Option<String> trustStore,
               Option<String> trustStorePassword,
               Option<String> keyStore,
               Option<String> keyStorePassword) implements KafkaSecurity {
        public static Result<Ssl> ssl(Option<String> trustStore,
                                      Option<String> trustStorePassword,
                                      Option<String> keyStore,
                                      Option<String> keyStorePassword) {
            return all(Verify.ensureOption(trustStore, Verify.Is::present),
                       Verify.ensureOption(trustStorePassword, Verify.Is::present),
                       Verify.ensureOption(keyStore, Verify.Is::present),
                       Verify.ensureOption(keyStorePassword, Verify.Is::present)).map(Ssl::new);
        }

        @Override
        public Map<String, Object> properties() {
            return sslProperties("SSL");
        }

        Map<String, Object> sslProperties(String protocol) {
            var props = new HashMap<String, Object>(Map.of("security.protocol", protocol));

            trustStore.onPresent(v -> props.put("ssl.truststore.location", v));
            trustStorePassword.onPresent(v -> props.put("ssl.truststore.password", v));
            keyStore.onPresent(v -> props.put("ssl.keystore.location", v));
            keyStorePassword.onPresent(v -> props.put("ssl.keystore.password", v));

            return props;
        }

        @Override
        public String toString() {
            return "Ssl[trustStore=" + trustStore + ", keyStore=" + keyStore + ", passwords=redacted]";
        }
    }

    /// SASL credentials. Username and password may not contain `"` or `\`: they are embedded in a JAAS line.
    record Sasl(Mechanism mechanism, String username, String password) {
        public static Result<Sasl> sasl(Mechanism mechanism, String username, String password) {
            return all(Verify.ensure(mechanism, Verify.Is::notNull),
                       Verify.ensure(username, Verify.Is::present).flatMap(Sasl::jaasSafe),
                       Verify.ensure(password, Verify.Is::present).flatMap(Sasl::jaasSafe)).map(Sasl::new);
        }

        private static Result<String> jaasSafe(String value) {
            return Verify.ensure(value,
                                 v -> v.indexOf('"') < 0 && v.indexOf('\\') < 0,
                                 Causes.cause("SASL credential may not contain a quote or backslash"));
        }

        Map<String, Object> saslProperties() {
            return Map.of("sasl.mechanism",
                          mechanism.wire,
                          "sasl.jaas.config",
                          mechanism.loginModule
                         + " required username=\"" + username
                         + "\" password=\"" + password
                         + "\";");
        }

        @Override
        public String toString() {
            return "Sasl[mechanism=" + mechanism + ", username=" + username + ", password=redacted]";
        }
    }

    record SaslPlaintext(Sasl sasl) implements KafkaSecurity {
        public static SaslPlaintext saslPlaintext(Sasl sasl) {
            return new SaslPlaintext(sasl);
        }

        @Override
        public Map<String, Object> properties() {
            var props = new HashMap<String, Object>(sasl.saslProperties());

            props.put("security.protocol", "SASL_PLAINTEXT");

            return props;
        }
    }

    record SaslSsl(Ssl ssl, Sasl sasl) implements KafkaSecurity {
        public static SaslSsl saslSsl(Ssl ssl, Sasl sasl) {
            return new SaslSsl(ssl, sasl);
        }

        @Override
        public Map<String, Object> properties() {
            var props = new HashMap<String, Object>(ssl.sslProperties("SASL_SSL"));

            props.putAll(sasl.saslProperties());

            return props;
        }
    }
}
