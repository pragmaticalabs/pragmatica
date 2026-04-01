package org.pragmatica.aether.example.urlshortener.analytics;

import org.pragmatica.aether.slice.Subscriber;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@ResourceQualifier(type = Subscriber.class, config = "messaging.click-events") @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD) public@interface ClickEventSubscription {}
