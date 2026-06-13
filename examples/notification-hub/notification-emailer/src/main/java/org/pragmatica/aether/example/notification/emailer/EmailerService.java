package org.pragmatica.aether.example.notification.emailer;

import org.pragmatica.aether.example.notification.NotificationEvent;
import org.pragmatica.aether.resource.notification.Notification;
import org.pragmatica.aether.resource.notification.NotificationBody;
import org.pragmatica.aether.resource.notification.NotificationSender;
import org.pragmatica.aether.resource.notification.Notify;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.Codec;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.pragmatica.aether.example.notification.emailer.EmailerStatus.emailerStatus;


@Slice
public interface EmailerService {
    @Codec
    record StatusRequest() {}

    @NotificationConsumer
    Promise<Unit> processNotification(NotificationEvent event);

    Promise<EmailerStatus> status(StatusRequest request);

    static EmailerService emailerService(@Notify NotificationSender sender) {
        return new emailerService(sender, new AtomicLong(), new AtomicLong());
    }

    record emailerService(NotificationSender sender, AtomicLong sentCount, AtomicLong failedCount) implements EmailerService {
        private static final String FROM_ADDRESS = "notifications@notification-hub.example";

        @Override
        public Promise<Unit> processNotification(NotificationEvent event) {
            var email = Notification.Email.email(FROM_ADDRESS,
                                                 List.of(recipientFor(event)),
                                                 "Notification from " + event.senderId(),
                                                 NotificationBody.Text.text(event.message()));

            return sender.send(email)
                         .onSuccess(_ -> sentCount.incrementAndGet())
                         .onFailure(_ -> failedCount.incrementAndGet())
                         .mapToUnit();
        }

        @Override
        public Promise<EmailerStatus> status(StatusRequest request) {
            return Promise.success(emailerStatus(sentCount.get(), failedCount.get()));
        }

        private static String recipientFor(NotificationEvent event) {
            return event.channel() + "@notification-hub.example";
        }
    }
}
