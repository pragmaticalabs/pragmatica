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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.net.smtp.SmtpMessage.smtpMessage;

class SmtpMessageTest {

    @Nested
    class Rfc5322Formatting {
        @Test
        void toRfc5322_containsRequiredHeaders_simpleMessage() {
            var msg = smtpMessage("sender@example.com", List.of("recipient@example.com"), "Test Subject", "Hello World");
            var rfc = msg.toRfc5322();

            assertThat(rfc).contains("From: sender@example.com\r\n");
            assertThat(rfc).contains("To: recipient@example.com\r\n");
            assertThat(rfc).contains("Subject: Test Subject\r\n");
            assertThat(rfc).contains("MIME-Version: 1.0\r\n");
            assertThat(rfc).contains("Content-Type: text/plain; charset=UTF-8\r\n");
            assertThat(rfc).contains("Date: ");
            assertThat(rfc).endsWith("\r\n\r\nHello World");
        }

        @Test
        void toRfc5322_containsMultipleRecipients_multipleToAddresses() {
            var msg = smtpMessage("sender@example.com",
                                  List.of("a@example.com", "b@example.com"),
                                  "Subject", "Body");
            var rfc = msg.toRfc5322();

            assertThat(rfc).contains("To: a@example.com, b@example.com\r\n");
        }

        @Test
        void toRfc5322_containsCcHeader_withCcRecipients() {
            var msg = smtpMessage("sender@example.com", List.of("to@example.com"), "Subject", "Body")
                .withCc(List.of("cc1@example.com", "cc2@example.com"));
            var rfc = msg.toRfc5322();

            assertThat(rfc).contains("Cc: cc1@example.com, cc2@example.com\r\n");
        }

        @Test
        void toRfc5322_omitsCcHeader_noCcRecipients() {
            var msg = smtpMessage("sender@example.com", List.of("to@example.com"), "Subject", "Body");
            var rfc = msg.toRfc5322();

            assertThat(rfc).doesNotContain("Cc:");
        }

        @Test
        void toRfc5322_containsCustomHeaders_withHeaders() {
            var msg = smtpMessage("sender@example.com", List.of("to@example.com"), "Subject", "Body")
                .withHeaders(Map.of("X-Custom", "value1"));
            var rfc = msg.toRfc5322();

            assertThat(rfc).contains("X-Custom: value1\r\n");
        }
    }

    @Nested
    class BuilderMethods {
        @Test
        void withCc_returnsNewInstance_preservingOriginal() {
            var original = smtpMessage("from@x.com", List.of("to@x.com"), "S", "B");
            var withCc = original.withCc(List.of("cc@x.com"));

            assertThat(original.cc()).isEmpty();
            assertThat(withCc.cc()).containsExactly("cc@x.com");
        }

        @Test
        void withBcc_returnsNewInstance_preservingOriginal() {
            var original = smtpMessage("from@x.com", List.of("to@x.com"), "S", "B");
            var withBcc = original.withBcc(List.of("bcc@x.com"));

            assertThat(original.bcc()).isEmpty();
            assertThat(withBcc.bcc()).containsExactly("bcc@x.com");
        }

        @Test
        void withHeaders_returnsNewInstance_preservingOriginal() {
            var original = smtpMessage("from@x.com", List.of("to@x.com"), "S", "B");
            var withHeaders = original.withHeaders(Map.of("X-Key", "val"));

            assertThat(original.headers()).isEmpty();
            assertThat(withHeaders.headers()).containsEntry("X-Key", "val");
        }
    }

    @Nested
    class AllRecipients {
        @Test
        void allRecipients_combinesAllAddresses_toAndCcAndBcc() {
            var msg = smtpMessage("from@x.com", List.of("to@x.com"), "S", "B")
                .withCc(List.of("cc@x.com"))
                .withBcc(List.of("bcc@x.com"));

            assertThat(msg.allRecipients()).containsExactly("to@x.com", "cc@x.com", "bcc@x.com");
        }

        @Test
        void allRecipients_returnsOnlyTo_noCcOrBcc() {
            var msg = smtpMessage("from@x.com", List.of("a@x.com", "b@x.com"), "S", "B");

            assertThat(msg.allRecipients()).containsExactly("a@x.com", "b@x.com");
        }
    }
}
