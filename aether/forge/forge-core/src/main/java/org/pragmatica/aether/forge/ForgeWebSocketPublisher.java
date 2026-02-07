package org.pragmatica.aether.forge;

import org.pragmatica.aether.forge.api.ForgeApiResponses.FullStatusResponse;
import org.pragmatica.http.routing.JsonCodec;
import org.pragmatica.http.routing.JsonCodecAdapter;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes full status JSON via WebSocket at regular intervals.
 * Short-circuits when no clients are connected.
 */
public class ForgeWebSocketPublisher {
    private static final Logger log = LoggerFactory.getLogger(ForgeWebSocketPublisher.class);
    private static final long BROADCAST_INTERVAL_MS = 1000;

    private final Supplier<FullStatusResponse> statusSupplier;
    private final JsonCodec jsonCodec;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ForgeWebSocketPublisher(Supplier<FullStatusResponse> statusSupplier) {
        this.statusSupplier = statusSupplier;
        this.jsonCodec = JsonCodecAdapter.defaultCodec();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "forge-ws-publisher");
            thread.setDaemon(true);
            return thread;
        });
    }

    public static ForgeWebSocketPublisher forgeWebSocketPublisher(Supplier<FullStatusResponse> statusSupplier) {
        return new ForgeWebSocketPublisher(statusSupplier);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        scheduler.scheduleAtFixedRate(this::publish,
                                      BROADCAST_INTERVAL_MS,
                                      BROADCAST_INTERVAL_MS,
                                      TimeUnit.MILLISECONDS);
        log.info("Forge WebSocket publisher started");
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        scheduler.shutdown();
        log.info("Forge WebSocket publisher stopped");
    }

    private void publish() {
        if (ForgeWebSocketHandler.connectedClients() == 0) {
            return;
        }
        try {
            var status = statusSupplier.get();
            jsonCodec.serialize(status)
                     .onSuccess(byteBuf -> {
                         var bytes = new byte[byteBuf.readableBytes()];
                         byteBuf.readBytes(bytes);
                         byteBuf.release();
                         ForgeWebSocketHandler.broadcast(new String(bytes, StandardCharsets.UTF_8));
                     })
                     .onFailure(cause -> log.error("Failed to serialize status for WebSocket: {}", cause.message()));
        } catch (Exception e) {
            log.error("Error publishing status via WebSocket", e);
        }
    }
}
