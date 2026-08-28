package org.pragmatica.jbct.slice;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Packaged-class-set tests for #712: message/event classes the manifest itself declares
/// (`publish.message.classes`, `stream.event.classes`) must reach allImplClasses() — the single
/// source PackageSlicesMojo packages the slice jar from. Omitting them fails slice activation
/// with NoClassDefFoundError when the message record lives outside the slice package.
class SliceManifestTest {

    private static SliceManifest load(String manifestText) {
        return SliceManifest.load(new ByteArrayInputStream(manifestText.getBytes(StandardCharsets.UTF_8)))
                            .unwrap();
    }

    @Test
    void allImplClasses_publishMessageClassOutsideSlicePackage_includedInPackagedSet() {
        // Mirrors ticketing-sweep-holds: publisher of a shared.event record outside the slice package
        var manifest = load("""
            slice.name=SweepHolds
            slice.package=org.pragmatica.example.ticketing.sweepholds
            impl.classes=org.pragmatica.example.ticketing.sweepholds.SweepHolds,org.pragmatica.example.ticketing.sweepholds.SweepHoldsFactory
            request.classes=org.pragmatica.example.ticketing.sweepholds.SweepRequest
            response.classes=org.pragmatica.example.ticketing.sweepholds.SweepResponse
            publish.message.classes=org.pragmatica.example.ticketing.shared.event.SeatReleased
            publish.topics.count=1
            publish.topic.0.config=seat-released
            publish.topic.0.messageType=org.pragmatica.example.ticketing.shared.event.SeatReleased
            """);

        assertTrue(manifest.allImplClasses()
                           .contains("org.pragmatica.example.ticketing.shared.event.SeatReleased"),
                   "publish.message.classes entry must be part of the packaged class set, got: "
                   + manifest.allImplClasses());
    }

    @Test
    void allImplClasses_streamEventClass_includedInPackagedSet() {
        // Mirrors notification-hub: StreamPublisher<NotificationEvent> where the event is not a
        // request/response type of any slice method
        var manifest = load("""
            slice.name=NotificationService
            slice.package=org.pragmatica.aether.example.notification
            impl.classes=org.pragmatica.aether.example.notification.NotificationService,org.pragmatica.aether.example.notification.NotificationServiceFactory
            stream.publishers.count=1
            stream.publisher.0.config=notification-stream
            stream.publisher.0.eventType=org.pragmatica.aether.example.notification.NotificationEvent
            stream.event.classes=org.pragmatica.aether.example.notification.NotificationEvent
            """);

        assertTrue(manifest.allImplClasses()
                           .contains("org.pragmatica.aether.example.notification.NotificationEvent"),
                   "stream.event.classes entry must be part of the packaged class set, got: "
                   + manifest.allImplClasses());
    }

    @Test
    void allImplClasses_messageClassAlsoRequestClass_appearsOnce() {
        var manifest = load("""
            slice.name=Echo
            slice.package=com.example.echo
            impl.classes=com.example.echo.Echo
            request.classes=com.example.echo.Ping
            publish.message.classes=com.example.echo.Ping
            """);

        var occurrences = manifest.allImplClasses()
                                  .stream()
                                  .filter("com.example.echo.Ping"::equals)
                                  .count();

        assertEquals(1, occurrences, "duplicate declarations must collapse to one packaged entry");
    }

    @Test
    void allImplClasses_manifestWithoutMessageKeys_containsExactlyImplRequestResponse() {
        // Backward compatibility: manifests produced before message/event keys existed still load
        // and package the same set as before
        var manifest = load("""
            slice.name=Plain
            slice.package=com.example.plain
            impl.classes=com.example.plain.Plain
            request.classes=com.example.plain.Req
            response.classes=com.example.plain.Resp
            """);

        assertEquals(List.of("com.example.plain.Plain", "com.example.plain.Req", "com.example.plain.Resp"),
                     manifest.allImplClasses());
    }
}
