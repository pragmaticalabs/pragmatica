package org.pragmatica.aether.example.notification;

import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.http.handler.security.SecurityContextHolder;
import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;
import org.pragmatica.serialization.Codec;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/// Notification service slice - sends and broadcasts notifications.
///
/// Publishes notification events to a stream for downstream analytics.
/// Demonstrates per-route security: health is public, send requires
/// authentication, broadcast requires admin role.
@Slice
public interface NotificationService {
    // === Requests ===
    @Codec
    record SendRequest(String message, String channel) {}

    @Codec
    record BroadcastRequest(String message) {}

    @Codec
    record ListRequest() {}

    @Codec
    record HealthRequest() {}

    // === Responses ===
    @Codec
    record NotificationResponse(String status, String notificationId) {
        public static NotificationResponse notificationResponse(String status, String notificationId) {
            return new NotificationResponse(status, notificationId);
        }
    }

    @Codec
    record NotificationListResponse(List<NotificationEvent> notifications) {
        public static NotificationListResponse notificationListResponse(List<NotificationEvent> notifications) {
            return new NotificationListResponse(notifications);
        }
    }

    @Codec
    record HealthResponse(String status) {
        public static HealthResponse healthResponse(String status) {
            return new HealthResponse(status);
        }
    }

    // === Operations ===
    /// Send notification to a specific channel (authenticated via routes.toml).
    Promise<NotificationResponse> send(SendRequest request);

    /// Broadcast to all channels (admin only via routes.toml).
    Promise<NotificationResponse> broadcast(BroadcastRequest request);

    /// List recent notifications (authenticated via routes.toml).
    Promise<NotificationListResponse> list(ListRequest request);

    /// Health check (public via routes.toml).
    Promise<HealthResponse> health(HealthRequest request);

    // === Factory ===
    /// Factory method with StreamPublisher dependency.
    static NotificationService notificationService(@NotificationStream StreamPublisher<NotificationEvent> publisher) {
        return new notificationService(publisher, new CopyOnWriteArrayList<>());
    }

    record notificationService(StreamPublisher<NotificationEvent> publisher,
                               CopyOnWriteArrayList<NotificationEvent> recentNotifications)
    implements NotificationService {
        private static final int MAX_RECENT = 100;
        private static final String ALL_CHANNELS = "all";

        @Override
        public Promise<NotificationResponse> send(SendRequest request) {
            return publishAndRespond(currentSenderId(), request.message(), request.channel());
        }

        @Override
        public Promise<NotificationResponse> broadcast(BroadcastRequest request) {
            return publishAndRespond(currentSenderId(), request.message(), ALL_CHANNELS);
        }

        @Override
        public Promise<NotificationListResponse> list(ListRequest request) {
            return Promise.success(NotificationListResponse.notificationListResponse(List.copyOf(recentNotifications)));
        }

        @Override
        public Promise<HealthResponse> health(HealthRequest request) {
            return Promise.success(HealthResponse.healthResponse("ok"));
        }

        private Promise<NotificationResponse> publishAndRespond(String senderId, String message, String channel) {
            var event = new NotificationEvent(senderId, message, channel, System.currentTimeMillis());
            var notificationId = UUID.randomUUID()
                                     .toString();
            return publisher.publish(event)
                            .onSuccess(_ -> addToRecent(event))
                            .map(_ -> NotificationResponse.notificationResponse("sent", notificationId));
        }

        private static String currentSenderId() {
            return SecurityContextHolder.currentContext()
                                        .or(SecurityContext::securityContext)
                                        .principal()
                                        .value();
        }

        private void addToRecent(NotificationEvent event) {
            recentNotifications.add(event);
            trimRecent();
        }

        private void trimRecent() {
            while (recentNotifications.size() > MAX_RECENT) {
                recentNotifications.removeFirst();
            }
        }
    }
}
