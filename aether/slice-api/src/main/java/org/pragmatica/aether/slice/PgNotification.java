package org.pragmatica.aether.slice;
public record PgNotification( String channel, String payload, int pid) {
    public static PgNotification pgNotification(String channel, String payload, int pid) {
        return new PgNotification(channel, payload, pid);
    }
}
