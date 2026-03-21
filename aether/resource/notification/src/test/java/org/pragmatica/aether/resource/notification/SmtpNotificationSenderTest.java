package org.pragmatica.aether.resource.notification;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SmtpNotificationSenderTest {

    @Test
    void toSmtpMessage_mapsRequiredFields() {
        var notification = Notification.Email.email(
            "sender@example.com",
            List.of("recipient@example.com"),
            "Test Subject",
            NotificationBody.Text.text("Hello")
        );

        var smtp = SmtpNotificationSender.toSmtpMessage(notification);

        assertThat(smtp.from()).isEqualTo("sender@example.com");
        assertThat(smtp.to()).containsExactly("recipient@example.com");
        assertThat(smtp.subject()).isEqualTo("Test Subject");
        assertThat(smtp.body()).isEqualTo("Hello");
    }

    @Test
    void toSmtpMessage_mapsCcAndBcc() {
        var notification = Notification.Email.email(
            "sender@example.com",
            List.of("to@example.com"),
            "Subject",
            NotificationBody.Text.text("Body")
        ).withCc(List.of("cc@example.com"))
         .withBcc(List.of("bcc@example.com"));

        var smtp = SmtpNotificationSender.toSmtpMessage(notification);

        assertThat(smtp.cc()).containsExactly("cc@example.com");
        assertThat(smtp.bcc()).containsExactly("bcc@example.com");
    }

    @Test
    void toSmtpMessage_htmlWithFallback_useFallbackAsBody() {
        var notification = Notification.Email.email(
            "sender@example.com",
            List.of("to@example.com"),
            "Subject",
            NotificationBody.Html.html("<b>Hello</b>", "Hello")
        );

        var smtp = SmtpNotificationSender.toSmtpMessage(notification);

        assertThat(smtp.body()).isEqualTo("Hello");
    }

    @Test
    void toSmtpMessage_htmlWithoutFallback_useHtmlAsBody() {
        var notification = Notification.Email.email(
            "sender@example.com",
            List.of("to@example.com"),
            "Subject",
            NotificationBody.Html.html("<b>Hello</b>")
        );

        var smtp = SmtpNotificationSender.toSmtpMessage(notification);

        assertThat(smtp.body()).isEqualTo("<b>Hello</b>");
    }
}
