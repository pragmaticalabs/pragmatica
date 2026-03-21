package org.pragmatica.email.http;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.email.http.vendor.MailgunMapping;
import org.pragmatica.email.http.vendor.PostmarkMapping;
import org.pragmatica.email.http.vendor.ResendMapping;
import org.pragmatica.email.http.vendor.SendGridMapping;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.email.http.EmailBody.Html.html;
import static org.pragmatica.email.http.EmailBody.Text.text;
import static org.pragmatica.email.http.EmailMessage.emailMessage;
import static org.pragmatica.email.http.HttpEmailConfig.httpEmailConfig;

class VendorMappingTest {
    private static final EmailMessage MESSAGE = emailMessage(
        "sender@example.com",
        List.of("recipient@example.com"),
        "Test Subject",
        text("Hello World")
    );

    private static final EmailMessage MESSAGE_WITH_CC = MESSAGE
        .withCc(List.of("cc@example.com"))
        .withBcc(List.of("bcc@example.com"));

    private static final HttpEmailConfig CONFIG = httpEmailConfig("test", "test-api-key");

    @Nested
    class SendGrid {
        private final SendGridMapping mapping = new SendGridMapping();

        @Test
        void vendorId_returnsSendgrid() {
            assertThat(mapping.vendorId()).isEqualTo("sendgrid");
        }

        @Test
        void toRequest_producesCorrectPath() {
            var request = mapping.toRequest(MESSAGE, CONFIG);

            assertThat(request.path()).isEqualTo("/v3/mail/send");
            assertThat(request.contentType()).isEqualTo("application/json");
        }

        @Test
        void toRequest_includesBearerAuth() {
            var request = mapping.toRequest(MESSAGE, CONFIG);

            assertThat(request.headers()).containsEntry("Authorization", "Bearer test-api-key");
        }

        @Test
        void toRequest_producesPersonalizationsArray() {
            var request = mapping.toRequest(MESSAGE, CONFIG);

            assertThat(request.body()).contains("\"personalizations\":[");
            assertThat(request.body()).contains("\"to\":[{\"email\":\"recipient@example.com\"}]");
        }

        @Test
        void toRequest_includesCcAndBcc() {
            var request = mapping.toRequest(MESSAGE_WITH_CC, CONFIG);

            assertThat(request.body()).contains("\"cc\":[{\"email\":\"cc@example.com\"}]");
            assertThat(request.body()).contains("\"bcc\":[{\"email\":\"bcc@example.com\"}]");
        }

        @Test
        void toRequest_handlesHtmlBody() {
            var htmlMessage = emailMessage("from@x.com", List.of("to@x.com"), "Sub", html("<b>Hi</b>"));
            var request = mapping.toRequest(htmlMessage, CONFIG);

            assertThat(request.body()).contains("\"type\":\"text/html\"");
        }
    }

    @Nested
    class Mailgun {
        private final MailgunMapping mapping = new MailgunMapping();

        @Test
        void vendorId_returnsMailgun() {
            assertThat(mapping.vendorId()).isEqualTo("mailgun");
        }

        @Test
        void toRequest_extractsDomainForPath() {
            var request = mapping.toRequest(MESSAGE, CONFIG);

            assertThat(request.path()).isEqualTo("/v3/example.com/messages");
        }

        @Test
        void toRequest_usesFormEncoding() {
            var request = mapping.toRequest(MESSAGE, CONFIG);

            assertThat(request.contentType()).isEqualTo("application/x-www-form-urlencoded");
            assertThat(request.body()).contains("from=");
            assertThat(request.body()).contains("to=");
            assertThat(request.body()).contains("subject=");
            assertThat(request.body()).contains("text=");
        }

        @Test
        void toRequest_usesBasicAuth() {
            var request = mapping.toRequest(MESSAGE, CONFIG);

            assertThat(request.headers().get("Authorization")).startsWith("Basic ");
        }
    }

    @Nested
    class Postmark {
        private final PostmarkMapping mapping = new PostmarkMapping();

        @Test
        void vendorId_returnsPostmark() {
            assertThat(mapping.vendorId()).isEqualTo("postmark");
        }

        @Test
        void toRequest_producesCorrectPath() {
            var request = mapping.toRequest(MESSAGE, CONFIG);

            assertThat(request.path()).isEqualTo("/email");
            assertThat(request.contentType()).isEqualTo("application/json");
        }

        @Test
        void toRequest_usesPostmarkTokenHeader() {
            var request = mapping.toRequest(MESSAGE, CONFIG);

            assertThat(request.headers()).containsEntry("X-Postmark-Server-Token", "test-api-key");
        }

        @Test
        void toRequest_producesPostmarkJsonFormat() {
            var request = mapping.toRequest(MESSAGE, CONFIG);

            assertThat(request.body()).contains("\"From\":\"sender@example.com\"");
            assertThat(request.body()).contains("\"To\":\"recipient@example.com\"");
            assertThat(request.body()).contains("\"Subject\":\"Test Subject\"");
            assertThat(request.body()).contains("\"TextBody\":\"Hello World\"");
        }
    }

    @Nested
    class Resend {
        private final ResendMapping mapping = new ResendMapping();

        @Test
        void vendorId_returnsResend() {
            assertThat(mapping.vendorId()).isEqualTo("resend");
        }

        @Test
        void toRequest_producesCorrectPath() {
            var request = mapping.toRequest(MESSAGE, CONFIG);

            assertThat(request.path()).isEqualTo("/emails");
            assertThat(request.contentType()).isEqualTo("application/json");
        }

        @Test
        void toRequest_includesBearerAuth() {
            var request = mapping.toRequest(MESSAGE, CONFIG);

            assertThat(request.headers()).containsEntry("Authorization", "Bearer test-api-key");
        }

        @Test
        void toRequest_producesResendJsonFormat() {
            var request = mapping.toRequest(MESSAGE, CONFIG);

            assertThat(request.body()).contains("\"from\":\"sender@example.com\"");
            assertThat(request.body()).contains("\"to\":[\"recipient@example.com\"]");
            assertThat(request.body()).contains("\"subject\":\"Test Subject\"");
            assertThat(request.body()).contains("\"text\":\"Hello World\"");
        }
    }
}
