package org.pragmatica.aether.resource.notification;

import org.pragmatica.aether.slice.annotation.ResourceQualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Resource qualifier for injecting NotificationSender instances.
///
/// Use this annotation on factory method parameters to inject a NotificationSender
/// configured from the "notification" section of aether.toml.
///
/// @see NotificationSender
/// @see ResourceQualifier
@ResourceQualifier(type = NotificationSender.class, config = "notification")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Notify {}
