/*
 *  Copyright (c) 2022-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.net.smtp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import io.netty.channel.embedded.EmbeddedChannel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.net.smtp.SmtpAuth.smtpAuth;
import static org.pragmatica.net.smtp.SmtpConfig.smtpConfig;
import static org.pragmatica.net.smtp.SmtpMessage.smtpMessage;

class SmtpSessionTest {

    private Promise<String> promise;
    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        promise = Promise.promise();
        channel = new EmbeddedChannel();
    }

    private SmtpSession createSession(SmtpConfig config, SmtpMessage message) {
        var session = new SmtpSession(config, message, promise, none());
        session.setChannel(channel);
        return session;
    }

    @Nested
    class StateTransitions {
        @Test
        void handleResponse_transitionsToEhlo_afterGreeting() {
            var config = smtpConfig("localhost", 25).withTlsMode(SmtpTlsMode.NONE);
            var msg = smtpMessage("a@b.com", List.of("c@d.com"), "Sub", "Body");
            var session = createSession(config, msg);

            session.handleResponse(220, "Welcome");

            assertThat(session.state()).isEqualTo(SmtpSession.State.EHLO);
            assertThat(readOutbound()).startsWith("EHLO ");
        }

        @Test
        void handleResponse_transitionsToMailFrom_afterEhloNoAuth() {
            var config = smtpConfig("localhost", 25).withTlsMode(SmtpTlsMode.NONE);
            var msg = smtpMessage("a@b.com", List.of("c@d.com"), "Sub", "Body");
            var session = createSession(config, msg);

            session.handleResponse(220, "Welcome");
            readOutbound(); // consume EHLO
            session.handleResponse(250, "OK");

            assertThat(session.state()).isEqualTo(SmtpSession.State.MAIL_FROM);
            assertThat(readOutbound()).isEqualTo("MAIL FROM:<a@b.com>\r\n");
        }

        @Test
        void handleResponse_transitionsToAuth_afterEhloWithAuth() {
            var config = smtpConfig("localhost", 25, SmtpTlsMode.NONE, smtpAuth("user", "pass"));
            var msg = smtpMessage("a@b.com", List.of("c@d.com"), "Sub", "Body");
            var session = createSession(config, msg);

            session.handleResponse(220, "Welcome");
            readOutbound();
            session.handleResponse(250, "OK");

            assertThat(session.state()).isEqualTo(SmtpSession.State.AUTH);
            var authLine = readOutbound();
            assertThat(authLine).startsWith("AUTH PLAIN ");
        }

        @Test
        void handleResponse_completesFullSession_happyPath() {
            var config = smtpConfig("localhost", 25).withTlsMode(SmtpTlsMode.NONE);
            var msg = smtpMessage("a@b.com", List.of("c@d.com"), "Sub", "Body");
            var session = createSession(config, msg);

            session.handleResponse(220, "Welcome");
            readOutbound();
            session.handleResponse(250, "OK");         // EHLO
            readOutbound();
            session.handleResponse(250, "OK");         // MAIL FROM
            readOutbound();
            session.handleResponse(250, "OK");         // RCPT TO
            readOutbound();
            session.handleResponse(354, "Go ahead");   // DATA
            readOutbound();
            session.handleResponse(250, "Queued");      // DATA content accepted

            assertThat(promise.isResolved()).isTrue();
        }

        @Test
        void handleResponse_sendsMultipleRcptTo_multipleRecipients() {
            var config = smtpConfig("localhost", 25).withTlsMode(SmtpTlsMode.NONE);
            var msg = smtpMessage("a@b.com", List.of("c@d.com", "e@f.com"), "Sub", "Body");
            var session = createSession(config, msg);

            session.handleResponse(220, "Welcome");
            readOutbound();
            session.handleResponse(250, "OK");         // EHLO
            readOutbound();
            session.handleResponse(250, "OK");         // MAIL FROM
            var firstRcpt = readOutbound();
            assertThat(firstRcpt).isEqualTo("RCPT TO:<c@d.com>\r\n");

            session.handleResponse(250, "OK");         // RCPT TO #1
            var secondRcpt = readOutbound();
            assertThat(secondRcpt).isEqualTo("RCPT TO:<e@f.com>\r\n");
        }
    }

    @Nested
    class AuthEncoding {
        @Test
        void encodePlain_producesCorrectBase64_standardCredentials() {
            var auth = smtpAuth("user@example.com", "password123");
            var encoded = auth.encodePlain();
            var decoded = new String(Base64.getDecoder().decode(encoded));

            assertThat(decoded).isEqualTo("\0user@example.com\0password123");
        }
    }

    @Nested
    class ErrorHandling {
        @Test
        void handleResponse_failsWithConnectionFailed_greetingRejected() {
            var config = smtpConfig("localhost", 25).withTlsMode(SmtpTlsMode.NONE);
            var msg = smtpMessage("a@b.com", List.of("c@d.com"), "Sub", "Body");
            var session = createSession(config, msg);

            session.handleResponse(554, "No service");

            assertThat(promise.isResolved()).isTrue();
        }

        @Test
        void handleResponse_failsWithAuthFailed_badCredentials() {
            var config = smtpConfig("localhost", 25, SmtpTlsMode.NONE, smtpAuth("user", "bad"));
            var msg = smtpMessage("a@b.com", List.of("c@d.com"), "Sub", "Body");
            var session = createSession(config, msg);

            session.handleResponse(220, "Welcome");
            readOutbound();
            session.handleResponse(250, "OK");
            readOutbound();
            session.handleResponse(535, "Auth failed");

            assertThat(promise.isResolved()).isTrue();
            assertThat(session.state()).isEqualTo(SmtpSession.State.DONE);
        }

        @Test
        void handleResponse_failsWithRejected_mailFromRejected() {
            var config = smtpConfig("localhost", 25).withTlsMode(SmtpTlsMode.NONE);
            var msg = smtpMessage("a@b.com", List.of("c@d.com"), "Sub", "Body");
            var session = createSession(config, msg);

            session.handleResponse(220, "Welcome");
            readOutbound();
            session.handleResponse(250, "OK");
            readOutbound();
            session.handleResponse(550, "Sender rejected");

            assertThat(session.state()).isEqualTo(SmtpSession.State.DONE);
        }

        @Test
        void onDisconnect_failsPromise_sessionInProgress() {
            var config = smtpConfig("localhost", 25).withTlsMode(SmtpTlsMode.NONE);
            var msg = smtpMessage("a@b.com", List.of("c@d.com"), "Sub", "Body");
            var session = createSession(config, msg);

            session.handleResponse(220, "Welcome");
            readOutbound();
            session.onDisconnect();

            assertThat(promise.isResolved()).isTrue();
            assertThat(session.state()).isEqualTo(SmtpSession.State.DONE);
        }
    }

    private String readOutbound() {
        var msg = channel.readOutbound();
        return msg != null ? msg.toString() : "";
    }
}
