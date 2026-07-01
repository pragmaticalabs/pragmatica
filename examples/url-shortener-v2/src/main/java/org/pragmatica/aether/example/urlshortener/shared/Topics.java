package org.pragmatica.aether.example.urlshortener.shared;

import org.pragmatica.aether.example.urlshortener.shortener.UrlShortener.ClickEvent;
import org.pragmatica.aether.slice.topic.Topic;


/// Single source of the `click-events` topic: its name and payload type are declared exactly once
/// here, and both the publishing slice (`UrlShortener`) and the subscribing slice (`Analytics`)
/// reference this constant by its identifier through `@ResourceQualifier(config = "CLICK_EVENTS")`.
/// The slice processor resolves that identifier to this constant, wraps the provisioned publisher in
/// a `TypedPublisher`, generates the topic name into each slice manifest, and rejects a payload-type
/// mismatch at compile time (#396).
public interface Topics {
    Topic<ClickEvent> CLICK_EVENTS = Topic.of("click-events", ClickEvent.class);
}
