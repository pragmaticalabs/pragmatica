package org.pragmatica.aether.example.pricing.analytics;

import org.pragmatica.aether.slice.Subscriber;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
@ResourceQualifier(type = Subscriber.class, config = "messaging.high-value-orders") @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD) public@interface HighValueOrderSubscription{}
