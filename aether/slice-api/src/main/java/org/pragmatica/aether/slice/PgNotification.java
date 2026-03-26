package org.pragmatica.aether.slice;
/// PostgreSQL notification received via LISTEN/NOTIFY.
///
/// @param channel the channel name that was notified
/// @param payload the notification payload string (up to 8000 bytes)
/// @param pid     the process ID of the sending PostgreSQL backend
public record PgNotification(String channel, String payload, int pid) {
    public static PgNotification pgNotification(String channel, String payload, int pid) {
        return new PgNotification(channel, payload, pid);
    }
}
