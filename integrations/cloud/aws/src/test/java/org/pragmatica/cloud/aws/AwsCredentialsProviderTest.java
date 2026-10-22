package org.pragmatica.cloud.aws;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.http.JdkHttpOperations;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #249: the three credential sources a `[storage.<instance>.shared] kind = "s3"` tier can name. No AWS SDK is
/// in the tree, so these are resolved by [AwsCredentialsProvider] itself; every failure names what is missing
/// and never a secret value.
class AwsCredentialsProviderTest {

    @Nested
    class Environment {

        @Test
        void environment_allVariables_resolvesWithSessionToken() {
            var env = Map.of("AWS_ACCESS_KEY_ID", "AKIAENV", "AWS_SECRET_ACCESS_KEY", "env-secret", "AWS_SESSION_TOKEN", "tok");

            AwsCredentialsProvider.environment(name -> Option.option(env.get(name)))
                                  .onFailure(cause -> fail(cause.message()))
                                  .onSuccess(provider -> provider.credentials()
                                                                 .await()
                                                                 .onFailure(cause -> fail(cause.message()))
                                                                 .onSuccess(creds -> {
                                                                     assertThat(creds.accessKeyId()).isEqualTo("AKIAENV");
                                                                     assertThat(creds.secretAccessKey()).isEqualTo("env-secret");
                                                                     assertThat(creds.sessionToken()).isEqualTo(Option.some("tok"));
                                                                 }));
        }

        @Test
        void environment_missingSecret_failsNamingTheVariableNotAValue() {
            var env = Map.of("AWS_ACCESS_KEY_ID", "AKIAENV");

            AwsCredentialsProvider.environment(name -> Option.option(env.get(name)))
                                  .onSuccess(_ -> fail("Expected failure"))
                                  .onFailure(cause -> assertThat(cause.message()).contains("AWS_SECRET_ACCESS_KEY")
                                                                                 .doesNotContain("AKIAENV"));
        }

        /// A04: a variable holding only blanks is not set. Mutation that reddens it: accept the raw value.
        @Test
        void environment_blankAccessKey_failsNamingTheVariable() {
            var env = Map.of("AWS_ACCESS_KEY_ID", "   ", "AWS_SECRET_ACCESS_KEY", "env-secret");

            AwsCredentialsProvider.environment(name -> Option.option(env.get(name)))
                                  .onSuccess(_ -> fail("Expected failure"))
                                  .onFailure(cause -> assertThat(cause.message()).contains("AWS_ACCESS_KEY_ID")
                                                                                 .doesNotContain("env-secret"));
        }
    }

    @Nested
    class ProfileFile {

        @TempDir
        Path dir;

        @Test
        void profileFile_defaultProfile_resolves() throws IOException {
            var file = dir.resolve("credentials");
            Files.writeString(file, """
                # comment
                [other]
                aws_access_key_id = AKIAOTHER
                aws_secret_access_key = other-secret

                [default]
                aws_access_key_id = AKIADEFAULT
                aws_secret_access_key = default-secret
                aws_session_token = file-token
                """);

            AwsCredentialsProvider.profileFile(file)
                                  .onFailure(cause -> fail(cause.message()))
                                  .onSuccess(provider -> provider.credentials()
                                                                 .await()
                                                                 .onFailure(cause -> fail(cause.message()))
                                                                 .onSuccess(creds -> {
                                                                     assertThat(creds.accessKeyId()).isEqualTo("AKIADEFAULT");
                                                                     assertThat(creds.secretAccessKey()).isEqualTo("default-secret");
                                                                     assertThat(creds.sessionToken()).isEqualTo(Option.some("file-token"));
                                                                 }));
        }

        @Test
        void profileFile_noDefaultSecret_failsNamingTheKey() throws IOException {
            var file = dir.resolve("credentials");
            Files.writeString(file, "[default]\naws_access_key_id = AKIADEFAULT\n");

            AwsCredentialsProvider.profileFile(file)
                                  .onSuccess(_ -> fail("Expected failure"))
                                  .onFailure(cause -> assertThat(cause.message()).contains("aws_secret_access_key")
                                                                                 .doesNotContain("AKIADEFAULT"));
        }

        /// A07: the `[default]` profile ends at the next section header; a key it lacks is never borrowed from a
        /// later profile. Mutation that reddens it: read past the next `[`.
        @Test
        void profileFile_keyOnlyInALaterProfile_isMissingFromDefault() throws IOException {
            var file = dir.resolve("credentials");
            Files.writeString(file, """
                [default]
                aws_access_key_id = AKIADEFAULT

                [other]
                aws_secret_access_key = other-secret
                """);

            AwsCredentialsProvider.profileFile(file)
                                  .onSuccess(_ -> fail("a key of another profile must not complete [default]"))
                                  .onFailure(cause -> assertThat(cause.message()).contains("aws_secret_access_key"));
        }

        @Test
        void profileFile_missingFile_fails() {
            AwsCredentialsProvider.profileFile(dir.resolve("absent"))
                                  .onSuccess(_ -> fail("Expected failure"))
                                  .onFailure(cause -> assertThat(cause).isInstanceOf(AwsError.CredentialsUnavailable.class));
        }
    }

    @Nested
    class InstanceProfile {

        private HttpServer server;
        private final AtomicInteger credentialFetches = new AtomicInteger();
        private final AtomicReference<String> sessionToken = new AtomicReference<>("");
        private final AtomicReference<Instant> expiration = new AtomicReference<>(Instant.now().plus(6, ChronoUnit.HOURS));
        private final AtomicInteger tokenStatus = new AtomicInteger(200);
        private final AtomicReference<String> roleListing = new AtomicReference<>("node-role\n");
        private String endpoint;

        @BeforeEach
        void start() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/latest/api/token", this::token);
            server.createContext("/latest/meta-data/iam/security-credentials/", this::credentials);
            server.start();
            endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @AfterEach
        void stop() {
            server.stop(0);
        }

        private void token(HttpExchange exchange) throws IOException {
            var ttl = exchange.getRequestHeaders().getFirst("X-aws-ec2-metadata-token-ttl-seconds");
            respond(exchange, "PUT".equals(exchange.getRequestMethod()) && ttl != null ? tokenStatus.get() : 400, "imds-session-token");
        }

        private void credentials(HttpExchange exchange) throws IOException {
            sessionToken.set(exchange.getRequestHeaders().getFirst("X-aws-ec2-metadata-token"));
            var path = exchange.getRequestURI().getPath();
            if (path.endsWith("/security-credentials/")) {
                respond(exchange, 200, roleListing.get());
                return;
            }
            credentialFetches.incrementAndGet();
            respond(exchange, 200, """
                {"Code":"Success","Type":"AWS-HMAC","AccessKeyId":"ASIAIMDS%d","SecretAccessKey":"imds-secret",\
                "Token":"imds-token","Expiration":"%s"}""".formatted(credentialFetches.get(), expiration.get()));
        }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Test
        void instanceProfile_imdsV2_resolvesTemporaryCredentials() {
            var provider = AwsCredentialsProvider.instanceProfile(JdkHttpOperations.jdkHttpOperations(), endpoint);

            provider.credentials()
                    .await()
                    .onFailure(cause -> fail(cause.message()))
                    .onSuccess(creds -> {
                        assertThat(creds.accessKeyId()).isEqualTo("ASIAIMDS1");
                        assertThat(creds.sessionToken()).isEqualTo(Option.some("imds-token"));
                        assertThat(creds.expiration().isPresent()).isTrue();
                    });
            assertThat(sessionToken.get()).isEqualTo("imds-session-token");
        }

        @Test
        void instanceProfile_validCredentials_areReused() {
            var provider = AwsCredentialsProvider.instanceProfile(JdkHttpOperations.jdkHttpOperations(), endpoint);

            provider.credentials().await();
            provider.credentials().await();

            assertThat(credentialFetches.get()).isEqualTo(1);
        }

        /// Mutation that reddens it: drop the `validFor` filter (never refresh) or the cache (always fetch).
        @Test
        void instanceProfile_nearExpiry_isRefreshed() {
            expiration.set(Instant.now().plus(1, ChronoUnit.MINUTES));
            var provider = AwsCredentialsProvider.instanceProfile(JdkHttpOperations.jdkHttpOperations(), endpoint);

            provider.credentials().await();
            provider.credentials()
                    .await()
                    .onSuccess(creds -> assertThat(creds.accessKeyId()).isEqualTo("ASIAIMDS2"));

            assertThat(credentialFetches.get()).isEqualTo(2);
        }

        /// A21: a non-2xx answer is a failure, never a body to use (here an error page would become the session
        /// token). Mutation that reddens it: accept any status.
        @Test
        void instanceProfile_tokenRequestRefused_failsNamingTheStatus() {
            tokenStatus.set(403);
            var provider = AwsCredentialsProvider.instanceProfile(JdkHttpOperations.jdkHttpOperations(), endpoint);

            provider.credentials()
                    .await()
                    .onSuccess(_ -> fail("a refused token request must fail"))
                    .onFailure(cause -> assertThat(cause.message()).contains("session token").contains("403"));
        }

        /// A23: blank lines around the role listing are skipped. Mutation that reddens it: take the first line even
        /// when blank (the role is then empty and the listing is fetched as if it were the credentials).
        @Test
        void instanceProfile_roleListingWithLeadingBlankLine_resolves() {
            roleListing.set("\nnode-role\n");
            var provider = AwsCredentialsProvider.instanceProfile(JdkHttpOperations.jdkHttpOperations(), endpoint);

            provider.credentials()
                    .await()
                    .onFailure(cause -> fail(cause.message()))
                    .onSuccess(creds -> assertThat(creds.accessKeyId()).isEqualTo("ASIAIMDS1"));
        }

        @Test
        void instanceProfile_noMetadataService_failsTyped() {
            server.stop(0);
            var provider = AwsCredentialsProvider.instanceProfile(JdkHttpOperations.jdkHttpOperations(), endpoint);

            provider.credentials()
                    .await()
                    .onSuccess(_ -> fail("Expected failure"));
        }
    }

    @Test
    void parseCredentials_missingField_failsNamingIt() {
        InstanceProfileCredentials.parseCredentials("{\"AccessKeyId\":\"A\",\"SecretAccessKey\":\"S\"}")
                                  .onSuccess(_ -> fail("Expected failure"))
                                  .onFailure(cause -> assertThat(cause.message()).contains("Token")
                                                                                 .doesNotContain("\"S\""));
    }

    @Test
    void credentials_toString_redactsSecrets() {
        var creds = AwsCredentials.awsCredentials("AKID", "very-secret", Option.some("very-token"), Option.none());

        assertThat(creds.toString()).doesNotContain("very-secret")
                                    .doesNotContain("very-token");
    }
}
