package org.pragmatica.aether.example.notification;

import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Publisher qualifier for notification stream events.
@ResourceQualifier(type = StreamPublisher.class, config = "streams.notifications")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface NotificationStream {}
