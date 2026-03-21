package org.pragmatica.aether.resource.notification;

import org.junit.jupiter.api.Test;
import org.pragmatica.email.http.EmailBody;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HttpNotificationSenderTest {

    @Test
    void toEmailMessage_mapsRequiredFields() {
        var notification = Notification.Email.email(
            "sender@example.com",
            List.of("recipient@example.com"),
            "Test Subject",
            NotificationBody.Text.text("Hello")
        );

        var email = HttpNotificationSender.toEmailMessage(notification);

        assertThat(email.from()).isEqualTo("sender@example.com");
        assertThat(email.to()).containsExactly("recipient@example.com");
        assertThat(email.subject()).isEqualTo("Test Subject");
        assertThat(email.body()).isInstanceOf(EmailBody.Text.class);
        assertThat(((EmailBody.Text) email.body()).content()).isEqualTo("Hello");
    }

    @Test
    void toEmailMessage_mapsHtmlBody() {
        var notification = Notification.Email.email(
            "sender@example.com",
            List.of("to@example.com"),
            "Subject",
            NotificationBody.Html.html("<b>Hello</b>", "Hello plain")
        );

        var email = HttpNotificationSender.toEmailMessage(notification);

        assertThat(email.body()).isInstanceOf(EmailBody.Html.class);
        var html = (EmailBody.Html) email.body();
        assertThat(html.content()).isEqualTo("<b>Hello</b>");
        assertThat(html.fallback().isPresent()).isTrue();
    }

    @Test
    void toEmailMessage_mapsCcBccReplyTo() {
        var notification = Notification.Email.email(
            "sender@example.com",
            List.of("to@example.com"),
            "Subject",
            NotificationBody.Text.text("Body")
        ).withCc(List.of("cc@example.com"))
         .withBcc(List.of("bcc@example.com"))
         .withReplyTo("reply@example.com");

        var email = HttpNotificationSender.toEmailMessage(notification);

        assertThat(email.cc()).containsExactly("cc@example.com");
        assertThat(email.bcc()).containsExactly("bcc@example.com");
        assertThat(email.replyTo().isPresent()).isTrue();
    }
}
