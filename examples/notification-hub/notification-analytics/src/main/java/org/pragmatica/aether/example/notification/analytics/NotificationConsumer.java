package org.pragmatica.aether.example.notification.analytics;

import org.pragmatica.aether.slice.StreamSubscriber;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
@ResourceQualifier(type = StreamSubscriber.class, config = "streams.notifications") @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD) public@interface NotificationConsumer{}
