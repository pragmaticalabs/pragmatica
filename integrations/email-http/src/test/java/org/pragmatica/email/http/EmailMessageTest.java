package org.pragmatica.email.http;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.email.http.EmailBody.Text.text;
import static org.pragmatica.email.http.EmailMessage.emailMessage;

class EmailMessageTest {
    private static final EmailMessage BASE = emailMessage(
        "sender@example.com",
        List.of("recipient@example.com"),
        "Test Subject",
        text("Hello")
    );

    @Nested
    class FactoryDefaults {
        @Test
        void emailMessage_setsRequiredFields() {
            assertThat(BASE.from()).isEqualTo("sender@example.com");
            assertThat(BASE.to()).containsExactly("recipient@example.com");
            assertThat(BASE.subject()).isEqualTo("Test Subject");
            assertThat(BASE.body()).isInstanceOf(EmailBody.Text.class);
        }

        @Test
        void emailMessage_defaultsOptionalFieldsToEmpty() {
            assertThat(BASE.cc()).isEmpty();
            assertThat(BASE.bcc()).isEmpty();
            assertThat(BASE.replyTo().isEmpty()).isTrue();
        }
    }

    @Nested
    class BuilderMethods {
        @Test
        void withCc_returnsCopyWithCcRecipients() {
            var message = BASE.withCc(List.of("cc@example.com"));

            assertThat(message.cc()).containsExactly("cc@example.com");
            assertThat(message.from()).isEqualTo(BASE.from());
        }

        @Test
        void withBcc_returnsCopyWithBccRecipients() {
            var message = BASE.withBcc(List.of("bcc@example.com"));

            assertThat(message.bcc()).containsExactly("bcc@example.com");
            assertThat(message.from()).isEqualTo(BASE.from());
        }

        @Test
        void withReplyTo_returnsCopyWithReplyTo() {
            var message = BASE.withReplyTo("reply@example.com");

            assertThat(message.replyTo().isEmpty()).isFalse();
            message.replyTo().onPresent(rt -> assertThat(rt).isEqualTo("reply@example.com"));
        }

        @Test
        void withMethods_areChainable() {
            var message = BASE.withCc(List.of("cc@example.com"))
                              .withBcc(List.of("bcc@example.com"))
                              .withReplyTo("reply@example.com");

            assertThat(message.cc()).containsExactly("cc@example.com");
            assertThat(message.bcc()).containsExactly("bcc@example.com");
            assertThat(message.replyTo().isEmpty()).isFalse();
        }
    }
}
