package com.github.pgasync.message.backend;

import com.github.pgasync.message.BackendMessage;

/**
 * @author  Antti Laisi
 */
public final class NotificationResponse implements BackendMessage {

    private final int backend;
    private final String channel;
    private final String payload;

    public NotificationResponse(int backend, String channel, String payload) {
        this.backend = backend;
        this.channel = channel;
        this.payload = payload;
    }

    public String getChannel() {
        return channel;
    }

    public String getPayload() {
        return payload;
    }
}
