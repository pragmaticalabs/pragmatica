package org.pragmatica.aether.example.notification.emailer;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.aether.example.notification.DeliveryStatusEvent;
import org.pragmatica.aether.example.notification.NotificationEvent;
import org.pragmatica.aether.resource.notification.Notification;
import org.pragmatica.aether.resource.notification.NotificationBody;
import org.pragmatica.aether.resource.notification.NotificationSender;
import org.pragmatica.aether.resource.notification.Notify;
import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.Codec;

import static org.pragmatica.aether.example.notification.emailer.EmailerStatus.emailerStatus;


@Slice
public interface EmailerService {
    @Codec
    record StatusRequest() {}

    @NotificationConsumer
    Promise<Unit> processNotification(NotificationEvent event);

    Promise<EmailerStatus> status(StatusRequest request);

    static EmailerService emailerService(@Notify NotificationSender sender,
                                         @DeliveryStatusPublisher Publisher<DeliveryStatusEvent> statusPublisher) {
        return new emailerService(sender, statusPublisher, new AtomicLong(), new AtomicLong());
    }

    record emailerService(NotificationSender sender,
                          Publisher<DeliveryStatusEvent> statusPublisher,
                          AtomicLong sentCount,
                          AtomicLong failedCount) implements EmailerService {
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
                         .map(_ -> true)
                         .recover(_ -> false)
                         .flatMap(delivered -> publishStatus(event, delivered));
        }

        /// The CO-DEPLOYED TOPIC publish (#1216): this slice and the analytics slice that consumes
        /// it are two distinct artifacts in one blueprint, so the bare name `delivery-status`
        /// resolves through the OWNING BLUEPRINT on both ends. Fire-and-forget by design — an
        /// analytics outage must not fail a delivery — which is exactly why a silently unroutable
        /// topic went unnoticed for so long: the publish succeeds either way.
        private Promise<Unit> publishStatus(NotificationEvent event, boolean delivered) {
            return statusPublisher.publish(new DeliveryStatusEvent(event.channel(),
                                                                   event.senderId(),
                                                                   delivered,
                                                                   System.currentTimeMillis()))
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
