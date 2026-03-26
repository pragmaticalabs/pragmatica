package org.pragmatica.postgres.net;

/// Callback for PostgreSQL LISTEN/NOTIFY with full notification details.
///
/// @see Connection#subscribe(String, NotificationHandler)
@FunctionalInterface
public interface NotificationHandler {
    /// Called when a notification arrives on a subscribed channel.
    ///
    /// @param channel the channel name
    /// @param payload the notification payload
    /// @param pid     the backend process ID of the sender
    void onNotification(String channel, String payload, int pid);
}
