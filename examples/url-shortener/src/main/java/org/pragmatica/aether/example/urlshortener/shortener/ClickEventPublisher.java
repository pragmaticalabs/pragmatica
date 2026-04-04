package org.pragmatica.aether.example.urlshortener.shortener;

import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


@ResourceQualifier(type = Publisher.class, config = "messaging.click-events") @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.PARAMETER) public@interface ClickEventPublisher {}
